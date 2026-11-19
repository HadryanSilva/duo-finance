package br.dev.duofinance.auth.google;

/** A verified Google account. {@code email} is already normalized; {@code name} may be null. */
public record GoogleIdentity(String subject, String email, String name) {}
