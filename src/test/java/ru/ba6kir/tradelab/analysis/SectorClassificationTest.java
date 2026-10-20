package ru.ba6kir.tradelab.analysis;

import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import ru.ba6kir.tradelab.analysis.application.port.AnalysisStore;
import ru.ba6kir.tradelab.analysis.application.service.SectorClassificationService;
import ru.ba6kir.tradelab.analysis.domain.sector.*;
import static ru.ba6kir.tradelab.analysis.domain.sector.SectorClassification.*;
import static ru.ba6kir.tradelab.analysis.domain.metric.AnalysisMetric.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static ru.ba6kir.tradelab.analysis.AnalysisTestData.*;

class SectorClassificationTest {
    final AnalysisStore store=mock(AnalysisStore.class);
    SectorClassificationService service(List<SectorClassification> seeds) { return new SectorClassificationService(store,seeds,CLOCK,Duration.ofDays(365)); }
    SectorClassification manual(Sector s,BusinessType b,boolean override) { return new SectorClassification(ASSET,"issuer:0",s,b,Source.MANUAL_VERIFIED,NOW.minusSeconds(60),override,"synthetic manually verified",null); }
    @Test void missingAndBroadLabelsRemainUnknownWithoutTickerOrNameGuessing() {
        assertThat(service(List.of()).classify(identity(UID,null)).sector()).isEqualTo(Sector.UNKNOWN);
        assertThat(service(List.of()).classify(identity(UID,"energy")).sector()).isEqualTo(Sector.UNKNOWN);
        verify(store,never()).saveClassification(any());
    }
    @Test void providerFinancialSectorNeverProvesBankBusinessType() {
        var c=service(List.of()).classify(identity(UID,"financial"));
        assertThat(c.sector()).isEqualTo(Sector.FINANCIALS); assertThat(c.businessType()).isEqualTo(BusinessType.UNKNOWN);
        assertThat(MetricProfile.defaults(c)).isNull();
    }
    @Test void manualRefinementDistinguishesBankAndNonBankFinancialCompanies() {
        var bank=service(List.of(manual(Sector.FINANCIALS,BusinessType.BANK,false))).classify(identity(UID,"financial"));
        assertThat(bank.source()).isEqualTo(Source.PROVIDER_AND_MANUAL); assertThat(bank.businessType()).isEqualTo(BusinessType.BANK);
        assertThat(MetricProfile.defaults(bank).metrics()).contains(ROE,PB).doesNotContain(DEBT_EBITDA,NET_DEBT_EBITDA);
        var nonBank=manual(Sector.FINANCIALS,BusinessType.NON_BANK_FINANCIAL,false);
        assertThat(MetricProfile.defaults(nonBank)).isNull();
    }
    @Test void providerHasPriorityUnlessVerifiedOverrideExplicitlySelectsManualClassification() {
        assertThat(service(List.of(manual(Sector.OIL_AND_GAS,BusinessType.NON_FINANCIAL,false))).classify(identity(UID,"it")).sector()).isEqualTo(Sector.INFORMATION_TECHNOLOGY);
        assertThat(service(List.of(manual(Sector.OIL_AND_GAS,BusinessType.NON_FINANCIAL,true))).classify(identity(UID,"it")).sector()).isEqualTo(Sector.OIL_AND_GAS);
    }
    @Test void sameAssetSharesClassificationAcrossOrdinaryAndPreferredSecurityContexts() {
        var service=service(List.of(classification(0)));
        assertThat(service.classify(identity(UID,"energy"))).isEqualTo(service.classify(identity(UID2,"energy")));
    }
    @Test void profileReadinessNeedsMultipleValidFamiliesAndRecognizesStaleInputs() {
        var profile=MetricProfile.defaults(classification(0));
        assertThat(profile.readiness(List.of(obs(PE,"5")),true)).isNotEqualTo(MetricProfile.Readiness.READY);
        assertThat(profile.readiness(List.of(obs(EV_EBITDA,"5"),obs(NET_DEBT_EBITDA,"2"),obs(FREE_CASH_FLOW,"-10")),true)).isEqualTo(MetricProfile.Readiness.READY);
        assertThat(profile.readiness(List.of(obs(EV_EBITDA,"5")),true)).isEqualTo(MetricProfile.Readiness.PARTIAL);
        assertThat(profile.readiness(List.of(obs(EV_EBITDA,"5")),false)).isEqualTo(MetricProfile.Readiness.STALE_DATA);
    }
    @Test void expiredOverrideIsVisibleAsStaleAndDoesNotSilentlyChangeBusinessClassification() {
        var expired=new SectorClassification(ASSET,"issuer:0",Sector.FINANCIALS,BusinessType.BANK,Source.MANUAL_VERIFIED,
                NOW.minus(Duration.ofDays(366)),true,"synthetic old verified override",null);
        var service=service(List.of(expired)); var c=service.classify(identity(UID,"it"));
        assertThat(c.businessType()).isEqualTo(BusinessType.BANK); assertThat(service.fresh(c)).isFalse();
        assertThat(MetricProfile.defaults(c).readiness(List.of(obs(PE,"5")),service.fresh(c))).isEqualTo(MetricProfile.Readiness.STALE_DATA);
    }
}
