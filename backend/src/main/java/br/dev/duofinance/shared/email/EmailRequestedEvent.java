package br.dev.duofinance.shared.email;

import java.util.Map;

public record EmailRequestedEvent(String to, EmailTemplate template, String locale, Map<String, Object> model) {}
