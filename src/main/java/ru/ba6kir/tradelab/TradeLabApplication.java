package ru.ba6kir.tradelab;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class TradeLabApplication {

    public static void main(String[] args) {
        SpringApplication.run(TradeLabApplication.class, args);
    }
}
