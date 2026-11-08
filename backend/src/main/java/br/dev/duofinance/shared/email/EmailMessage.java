package br.dev.duofinance.shared.email;

public record EmailMessage(String to, String subject, String html) {}
