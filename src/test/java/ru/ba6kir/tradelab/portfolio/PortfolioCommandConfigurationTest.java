package ru.ba6kir.tradelab.portfolio;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import ru.ba6kir.tradelab.portfolio.application.port.PortfolioStore;
import ru.ba6kir.tradelab.portfolio.application.service.*;
import ru.ba6kir.tradelab.portfolio.infrastructure.persistence.PortfolioProperties;
import ru.ba6kir.tradelab.portfolio.telegram.*;
import ru.ba6kir.tradelab.telegram.adapter.TelegramMessageSender;
import ru.ba6kir.tradelab.telegram.command.*;
import ru.ba6kir.tradelab.telegram.dispatcher.TelegramCommandDispatcher;
import ru.ba6kir.tradelab.telegram.security.TelegramAccessGuard;
import ru.ba6kir.tradelab.telegram.service.TelegramBotState;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static ru.ba6kir.tradelab.portfolio.PortfolioTestData.*;
import static ru.ba6kir.tradelab.telegram.TelegramTestData.*;

class PortfolioCommandConfigurationTest {
    final PortfolioService portfolios=mock(PortfolioService.class);final PortfolioTransactionService transactions=mock(PortfolioTransactionService.class);final PortfolioSynchronizationService sync=mock(PortfolioSynchronizationService.class);
    PortfolioCommand command(String name){return new PortfolioCommand(name,portfolios,transactions,sync,HASHES);}
    @Test void allPortfolioCommandsUseOwnerPrivateChatAuthorizationAndDynamicHelp(){
        var commands=List.of("portfolio","positions","position","transactions","trade","cash","income","opening","reverse","confirm","accounts","sync","reconcile","portstatus").stream().map(this::command).map(c->(TelegramCommand)c).toList();var sender=mock(TelegramMessageSender.class);
        var dispatcher=new TelegramCommandDispatcher(new TelegramAccessGuard(properties()),sender,new TelegramBotState(),commands);for(var command:commands){var outsider=update("/"+command.name());outsider.getMessage().getFrom().setId(100L);dispatcher.dispatch(outsider);var group=update("/"+command.name());group.getMessage().getChat().setType("group");dispatcher.dispatch(group);}
        verifyNoInteractions(portfolios,transactions,sync,sender);assertThat(new HelpCommand(()->commands).execute(List.of())).contains("/portfolio","/trade","/cash","/income","/sync","/confirm");}
    @Test void transportRequestKeyIsPassedOnlyAfterAuthorizationAndMissingUpdateIdCannotWrite(){var sender=mock(TelegramMessageSender.class);var dispatcher=new TelegramCommandDispatcher(new TelegramAccessGuard(properties()),sender,new TelegramBotState(),List.of(command("trade")));
        var bad=update("/trade buy "+UID+" 10 250 2");bad.setUpdateId(null);dispatcher.dispatch(bad);verifyNoInteractions(transactions);
        when(transactions.prepare(any(),any(),any())).thenReturn(new PortfolioStore.Pending(id(1),"request",HASHES.hash("x"),tx(1,ru.ba6kir.tradelab.portfolio.domain.model.LedgerTransaction.Type.BUY,"10","250",null,"2"),NOW.plusSeconds(900),null));
        var valid=update("/trade buy "+UID+" 10 250 2");valid.setUpdateId(77);dispatcher.dispatch(valid);verify(transactions).prepare(any(),eq("telegram:"+OWNER+":77"),any());}
    @Test void strictInputParsingRejectsNegativeScientificOrMalformedValuesAndDatesBeforePreparation(){for(var args:List.of(List.of("buy",UID,"-1","250","2"),List.of("buy",UID,"1e3","250","2"),List.of("sell",UID,"10","250","2","not-a-date")))
        assertThat(command("trade").execute(args,"test")).contains("Запись не выполнена");verifyNoInteractions(transactions);assertThat(PortfolioInput.time("2026-10-20")).isEqualTo(java.time.Instant.parse("2026-10-19T21:00:00Z"));assertThat(PortfolioInput.decimal("0,125",false)).isEqualByComparingTo("0.125");}
    @Test void previewUsesSharesAndExecutionTimestampWithoutRecordingBeforeConfirmation(){var draft=tx(2,ru.ba6kir.tradelab.portfolio.domain.model.LedgerTransaction.Type.BUY,"10","250",null,"2");when(transactions.prepare(any(),any(),any())).thenReturn(new PortfolioStore.Pending(id(2),"request",HASHES.hash("x"),draft,NOW.plusSeconds(900),null));
        var reply=command("trade").execute(List.of("buy",UID,"10","250","2"),"request");assertThat(reply).contains("10 акций","250 RUB/акция","комиссия: 2 RUB","/confirm", "не заявка брокеру","Europe/Moscow");
        var parsed=ArgumentCaptor.forClass(PortfolioTransactionService.Command.class);verify(transactions).prepare(parsed.capture(),eq("request"),any());assertThat(parsed.getValue().quantity()).isEqualByComparingTo("10");assertThat(parsed.getValue().executedAt()).isNull();verify(transactions,never()).confirm(any());}
    @Test void invalidDatesNeverLeakErrorsAndAccountDiscoveryNeverExposesExternalIds(){when(sync.discover()).thenReturn(List.of(Map.of("id",id(1),"name","SYNTHETIC ACCOUNT","account_type","ACCOUNT_TYPE_TINKOFF","read_only",true)));assertThat(command("accounts").execute(List.of("discover"),"discovery")).contains("локальные","/accounts link").doesNotContain("externalAccountId");
        when(portfolios.value()).thenThrow(new IllegalStateException("password=private-secret"));assertThat(command("portfolio").execute(List.of(),"test")).doesNotContain("private-secret");}
    @Test void defaultConfigurationDisablesBrokerIntegrationAndBoundsWindows(){var runner=new ApplicationContextRunner().withInitializer(new ConfigDataApplicationContextInitializer()).withUserConfiguration(Config.class);
        runner.run(c->{assertThat(c).hasNotFailed();assertThat(c.getBean(PortfolioProperties.class).brokerSyncEnabled()).isFalse();assertThat(c.getBean(PortfolioProperties.class).maxPages()).isEqualTo(20);});
        for(var property:List.of("PORTFOLIO_SYNC_COOLDOWN=1s","PORTFOLIO_SYNC_MAX_PAGES=0","PORTFOLIO_SYNC_MAX_PAGES=51","PORTFOLIO_QUOTE_MAX_AGE=8d"))runner.withPropertyValues(property).run(c->assertThat(c).hasFailed());}
    @Configuration(proxyBeanMethods=false) @EnableConfigurationProperties(PortfolioProperties.class)static class Config{}
}
