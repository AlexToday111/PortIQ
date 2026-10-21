package ru.ba6kir.tradelab.portfolio.infrastructure.persistence;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("tradelab.portfolio")
public record PortfolioProperties(boolean brokerSyncEnabled,Duration syncCooldown,int maxPages,Duration quoteMaxAge){
    public PortfolioProperties{if(syncCooldown==null || syncCooldown.compareTo(Duration.ofSeconds(10))<0 || syncCooldown.compareTo(Duration.ofHours(1))>0
            ||maxPages<1 ||maxPages>50 ||quoteMaxAge==null ||quoteMaxAge.isNegative() ||quoteMaxAge.isZero() ||quoteMaxAge.compareTo(Duration.ofDays(7))>0)throw new IllegalArgumentException("INVALID_PORTFOLIO_CONFIGURATION");}
}
