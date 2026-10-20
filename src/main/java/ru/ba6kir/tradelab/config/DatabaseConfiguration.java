package ru.ba6kir.tradelab.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class DatabaseConfiguration {

    @Bean
    @ConfigurationProperties("spring.datasource.hikari")
    HikariDataSource dataSource(DatabaseProperties properties) {
        return DataSourceBuilder.create()
                .type(HikariDataSource.class)
                .url(properties.jdbcUrl())
                .username(properties.username())
                .password(properties.password())
                .build();
    }
}
