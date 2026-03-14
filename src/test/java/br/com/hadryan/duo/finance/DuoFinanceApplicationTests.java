package br.com.hadryan.duo.finance;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.aot.DisabledInAotMode;

@SpringBootTest
@Import(TestConfig.class)
@ActiveProfiles("test")
@DisabledInAotMode
class DuoFinanceApplicationTests {

	@DynamicPropertySource
	static void configureProperties(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url",      SharedPostgresContainer.INSTANCE::getJdbcUrl);
		registry.add("spring.datasource.username", SharedPostgresContainer.INSTANCE::getUsername);
		registry.add("spring.datasource.password", SharedPostgresContainer.INSTANCE::getPassword);
	}

	@Test
	void contextLoads() {}
}