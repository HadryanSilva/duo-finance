package br.dev.duofinance.user.dto;

import jakarta.validation.constraints.NotBlank;

public record DeleteMeRequest(@NotBlank String password) {}
