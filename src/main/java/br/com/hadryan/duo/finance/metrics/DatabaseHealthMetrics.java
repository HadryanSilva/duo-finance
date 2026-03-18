package br.com.hadryan.duo.finance.metrics;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.boot.health.contributor.Status;
import org.springframework.stereotype.Component;

/**
 * Expõe o status do banco de dados como gauge Micrometer.
 *
 * Métrica gerada: health_db_status
 *   1.0 = UP (banco acessível)
 *   0.0 = DOWN / UNKNOWN (banco inacessível)
 *
 * Usada no painel "Status do Banco" do Grafana.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DatabaseHealthMetrics {

    private final MeterRegistry meterRegistry;
    private final HealthEndpoint healthEndpoint;

    @PostConstruct
    public void registerGauge() {
        Gauge.builder("health_db_status", this, DatabaseHealthMetrics::dbStatus)
                .description("Status do banco de dados: 1 = UP, 0 = DOWN")
                .tag("component", "database")
                .register(meterRegistry);
    }

    private double dbStatus() {
        try {
            var health = healthEndpoint.healthForPath("db");
            if (health == null) return 0.0;
            return Status.UP.equals(health.getStatus()) ? 1.0 : 0.0;
        } catch (Exception e) {
            log.warn("Erro ao verificar health do banco para métrica: {}", e.getMessage());
            return 0.0;
        }
    }
}

