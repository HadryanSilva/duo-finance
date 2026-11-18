package br.dev.duofinance.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

@TestConfiguration(proxyBeanMethods = false)
public class TestMerchantCategorizerConfiguration {

    @Bean
    @Primary
    RecordingMerchantCategorizer recordingMerchantCategorizer() {
        return new RecordingMerchantCategorizer();
    }
}
