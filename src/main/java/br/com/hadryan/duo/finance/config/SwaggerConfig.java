package br.com.hadryan.duo.finance.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SwaggerConfig {

    public static final String API_TITLE = "Duo Finance API";
    public static final String API_DESCRIPTION = "API para gerenciamento financeiro pessoal, incluindo controle de despesas, receitas e relatórios financeiros.";
    public static final String API_VERSION = "1.0.0";
    public static final String API_TERMS_OF_SERVICE_URL = "https://example.com/terms";
    public static final String API_CONTACT_NAME = "Suporte Duo Finance";
    public static final String API_CONTACT_URL = "https://example.com/support";

    @Bean
    public OpenAPI customOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title(API_TITLE)
                        .description(API_DESCRIPTION)
                        .version(API_VERSION)
                        .termsOfService(API_TERMS_OF_SERVICE_URL)
                        .contact(new Contact()
                                .name(API_CONTACT_NAME)
                                .url(API_CONTACT_URL)));
    }

}
