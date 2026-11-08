package br.dev.duofinance.recurring.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Turns on {@code @Scheduled}; disabled in tests, which call the job directly. */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "app.recurring.scheduler-enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {}
