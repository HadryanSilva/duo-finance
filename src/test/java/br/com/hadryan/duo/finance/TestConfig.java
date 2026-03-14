package br.com.hadryan.duo.finance;

import com.cloudinary.Cloudinary;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.mail.internet.MimeMessage;
import org.mockito.Mockito;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.mail.javamail.JavaMailSender;

@TestConfiguration
public class TestConfig {

    @Bean
    @Primary
    public ObjectMapper objectMapper() {
        return new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    @Bean
    @Primary
    public JavaMailSender javaMailSender() {
        JavaMailSender mock = Mockito.mock(JavaMailSender.class);
        Mockito.when(mock.createMimeMessage())
                .thenReturn(Mockito.mock(MimeMessage.class));
        return mock;
    }

    @Bean
    @Primary
    public Cloudinary cloudinary() {
        return Mockito.mock(Cloudinary.class);
    }
}