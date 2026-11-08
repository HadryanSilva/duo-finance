package br.dev.duofinance.account.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpdateAccountRequest(@NotBlank @Size(max = 60) String name) {}
