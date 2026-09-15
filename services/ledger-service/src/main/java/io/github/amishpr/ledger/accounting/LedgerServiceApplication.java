package io.github.amishpr.ledger.accounting;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class LedgerServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(LedgerServiceApplication.class, args);
    }
}
