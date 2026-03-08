package br.com.hadryan.duo.finance;

import br.com.hadryan.duo.finance.auth.jwt.JwtProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@EnableConfigurationProperties({JwtProperties.class})
@SpringBootApplication
public class DuoFinanceApplication {

	public static void main(String[] args) {
		SpringApplication.run(DuoFinanceApplication.class, args);
	}

}
