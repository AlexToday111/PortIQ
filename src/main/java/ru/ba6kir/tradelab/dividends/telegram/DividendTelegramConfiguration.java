package ru.ba6kir.tradelab.dividends.telegram;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import ru.ba6kir.tradelab.dividends.application.service.DividendService;

@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(prefix="tradelab.telegram",name="enabled",havingValue="true")
public class DividendTelegramConfiguration {
    @Bean DividendCommand dividendsCommand(DividendService service) { return new DividendCommand("dividends",service); }
    @Bean DividendCommand divcalendarCommand(DividendService service) { return new DividendCommand("divcalendar",service); }
    @Bean DividendCommand divrefreshCommand(DividendService service) { return new DividendCommand("divrefresh",service); }
    @Bean DividendCommand divhistoryCommand(DividendService service) { return new DividendCommand("divhistory",service); }
}
