package br.dev.duofinance.imports.service;

/** Where a planned row's suggested category came from. */
public enum SuggestionSource {
    MEMORY,
    SIMILAR,
    AI,
    DEFAULT,
    NONE
}
