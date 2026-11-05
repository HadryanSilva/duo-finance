package br.dev.duofinance;

import org.springframework.boot.SpringApplication;

public class TestDuoFinanceApplication {

    public static void main(String[] args) {
        SpringApplication.from(DuoFinanceApplication::main)
                .with(TestcontainersConfiguration.class)
                .run(args);
    }
}
