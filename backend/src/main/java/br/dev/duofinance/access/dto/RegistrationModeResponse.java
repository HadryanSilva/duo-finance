package br.dev.duofinance.access.dto;

import br.dev.duofinance.shared.config.AppProperties.RegistrationMode;
import org.jspecify.annotations.Nullable;

public record RegistrationModeResponse(RegistrationMode mode, @Nullable String googleClientId) {}
