package ru.ba6kir.tradelab.fundamentals.telegram;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import ru.ba6kir.tradelab.fundamentals.application.service.FundamentalsService;

@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(prefix="tradelab.telegram",name="enabled",havingValue="true")
public class FundamentalsTelegramConfiguration {
    @Bean FundamentalsCommand fundamentalsCommand(FundamentalsService service) { return new FundamentalsCommand("fundamentals",service); }
    @Bean FundamentalsCommand reportsCommand(FundamentalsService service) { return new FundamentalsCommand("reports",service); }
    @Bean FundamentalsCommand fundrefreshCommand(FundamentalsService service) { return new FundamentalsCommand("fundrefresh",service); }
    @Bean FundamentalsCommand fundstatusCommand(FundamentalsService service) { return new FundamentalsCommand("fundstatus",service); }
}
