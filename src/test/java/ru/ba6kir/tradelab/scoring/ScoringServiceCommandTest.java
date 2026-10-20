package ru.ba6kir.tradelab.scoring;

import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import ru.ba6kir.tradelab.analysis.scoring.application.service.*;
import ru.ba6kir.tradelab.analysis.scoring.infrastructure.configuration.ScoringFingerprints;
import ru.ba6kir.tradelab.analysis.scoring.telegram.ScoreCommand;
import ru.ba6kir.tradelab.analysis.scoring.domain.model.ScoringResult;
import ru.ba6kir.tradelab.market.application.InstrumentService;
import ru.ba6kir.tradelab.telegram.adapter.TelegramMessageSender;
import ru.ba6kir.tradelab.telegram.command.*;
import ru.ba6kir.tradelab.telegram.dispatcher.TelegramCommandDispatcher;
import ru.ba6kir.tradelab.telegram.security.TelegramAccessGuard;
import ru.ba6kir.tradelab.telegram.service.TelegramBotState;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static ru.ba6kir.tradelab.scoring.ScoringTestData.*;
import static ru.ba6kir.tradelab.telegram.TelegramTestData.*;

class ScoringServiceCommandTest {
    final ScoringInputProvider inputs=mock(ScoringInputProvider.class); final ScoringResultRepository repository=mock(ScoringResultRepository.class);
    final ScoringFingerprints hashes=new ScoringFingerprints(new ObjectMapper().findAndRegisterModules());
    final FundamentalScoringService service=new FundamentalScoringService(inputs,repository,model(),hashes,CLOCK);
    final InstrumentService instruments=mock(InstrumentService.class);
    ScoreCommand command(String name) { return new ScoreCommand(name,service,instruments); }
    void setup() { when(inputs.instrument(UID)).thenReturn(input("OIL_AND_GAS")); when(repository.save(any(),any())).thenAnswer(i -> ((ScoringResult)i.getArgument(0)).withId(1)); }
    @Test void dryRunEligibilityHasNoPersistenceAndSaveReturnsTypedResult() {
        setup(); var dry=service.getScoringEligibility(UID); assertThat(dry.status()).isEqualTo(ScoringResult.Status.SCORED); assertThat(dry.id()).isNull();
        verifyNoInteractions(repository); var saved=service.evaluateInstrument(UID); assertThat(saved.id()).isEqualTo(1L); verify(repository).save(any(),eq(model()));
    }
    @Test void assetUseCaseDoesNotChooseBetweenDifferentSecurities() {
        when(inputs.asset(ASSET)).thenThrow(new IllegalStateException("ASSET_INSTRUMENT_AMBIGUOUS"));
        var r=service.evaluateAsset(ASSET); assertThat(r.status()).isEqualTo(ScoringResult.Status.FAILED); assertThat(r.eligibilityReasons()).contains("ASSET_INSTRUMENT_AMBIGUOUS"); verifyNoInteractions(repository);
    }
    @Test void failedCaptureAndFailedPersistenceAreSanitizedAndDoNotInventAnOverallScore() {
        when(inputs.instrument(UID)).thenThrow(new IllegalStateException("private-host password=secret"));
        assertThat(service.evaluateInstrument(UID).eligibilityReasons()).containsExactly("INPUT_CAPTURE_FAILED");
        reset(inputs); setup(); doThrow(new IllegalStateException("private-db token=secret")).when(repository).save(any(),any());
        var failed=service.evaluateInstrument(UID); assertThat(failed.status()).isEqualTo(ScoringResult.Status.FAILED); assertThat(failed.overallScore()).isNull();
        assertThat(failed.toString()).doesNotContain("secret","private-db");
    }
    @Test void scoreAndScoreinfoShowScoresOrPreciseReasonsWithProvenance() {
        setup(); assertThat(command("score").execute(List.of(UID))).contains("79.975","Q: 81","F: 83.5","SCORED","SYNTHETIC TEST COMPANY","не инвестиционная рекомендация");
        assertThat(command("scoreinfo").execute(List.of(UID))).contains("ABSOLUTE_PIECEWISE_LINEAR_V1","Хэш входов","Режим проверки без сохранения","synthetic:ROIC");
        verifyNoInteractions(instruments);
    }
    @Test void historyKeepsMethodologyVersionsExplicitAndLatestHistoryApiUsesRepository() {
        setup(); var r=service.evaluateInstrument(UID); when(repository.history(UID,20)).thenReturn(List.of(r)); when(repository.latest(UID)).thenReturn(Optional.of(r));
        assertThat(command("scorehistory").execute(List.of(UID))).contains(r.modelVersion(),r.configurationVersion(),"напрямую не сопоставляются");
        assertThat(service.getLatestScore(UID)).contains(r);
    }
    @Test void commandsRejectWrongArgumentsAndCommonGuardBlocksUnauthorizedAndGroupRequests() {
        var commands=List.<TelegramCommand>of(command("score"),command("scoreinfo"),command("scorehistory"));
        var sender=mock(TelegramMessageSender.class); var dispatcher=new TelegramCommandDispatcher(new TelegramAccessGuard(properties()),sender,new TelegramBotState(),commands);
        for (var c:commands) {
            assertThat(c.execute(List.of())).contains("Используйте");
            var outsider=update("/"+c.name()+" "+UID); outsider.getMessage().getFrom().setId(123L); dispatcher.dispatch(outsider);
            var group=update("/"+c.name()+" "+UID); group.getMessage().getChat().setType("group"); dispatcher.dispatch(group);
        }
        verifyNoInteractions(inputs,repository,instruments,sender);
        assertThat(new HelpCommand(() -> commands).execute(List.of())).contains("/score","/scoreinfo","/scorehistory");
    }
    @Test void fingerprintsCoverChangedInputsAndConfigurationAndIgnoreSetIterationOrder() {
        var input=input("OIL_AND_GAS");
        assertThat(hashes.hash(input)).isEqualTo(hashes.hash(input("OIL_AND_GAS")));
        assertThat(hashes.hash(input)).isNotEqualTo(hashes.hash(replace(input,ru.ba6kir.tradelab.analysis.domain.metric.AnalysisMetric.PE,observation(ru.ba6kir.tradelab.analysis.domain.metric.AnalysisMetric.PE,"10"))));
        var m=model(); var newer=new ru.ba6kir.tradelab.analysis.scoring.domain.profile.ScoringModel(m.modelVersion(),"synthetic-config-2",m.weights(),new LinkedHashSet<>(List.of(ScoringResult.Pillar.D,ScoringResult.Pillar.V,ScoringResult.Pillar.F,ScoringResult.Pillar.Q)),m.observationMaxAge(),m.financialPeriodMaxAge(),1095,3,m.extremeYield(),m.highLeverage(),m.profiles());
        assertThat(hashes.hash(m)).isNotEqualTo(hashes.hash(newer));
        var reordered=new ru.ba6kir.tradelab.analysis.scoring.domain.profile.ScoringModel(m.modelVersion(),m.configurationVersion(),m.weights(),new LinkedHashSet<>(List.of(ScoringResult.Pillar.D,ScoringResult.Pillar.V,ScoringResult.Pillar.F,ScoringResult.Pillar.Q)),m.observationMaxAge(),m.financialPeriodMaxAge(),1095,3,m.extremeYield(),m.highLeverage(),m.profiles());
        assertThat(hashes.hash(m)).isEqualTo(hashes.hash(reordered));
    }
}
