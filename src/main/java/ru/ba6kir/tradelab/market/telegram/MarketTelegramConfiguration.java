package ru.ba6kir.tradelab.market.telegram;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ru.ba6kir.tradelab.market.application.InstrumentService;
import ru.ba6kir.tradelab.market.application.price.MarketPriceService;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "tradelab.telegram", name = "enabled", havingValue = "true")
public class MarketTelegramConfiguration {
    @Bean MarketCommand stockCommand(InstrumentService service, MarketPriceService prices) { return new MarketCommand(service, false, prices); }
    @Bean MarketCommand searchCommand(InstrumentService service) { return new MarketCommand(service, true); }
    @Bean PriceCommand priceCommand(MarketPriceService prices) { return new PriceCommand("price",prices); }
    @Bean PriceCommand historyCommand(MarketPriceService prices) { return new PriceCommand("history",prices); }
    @Bean PriceCommand refreshCommand(MarketPriceService prices) { return new PriceCommand("refresh",prices); }
}
