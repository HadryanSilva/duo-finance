package br.dev.duofinance.imports.service;

import java.util.List;

public record ImportPlan(List<String> products, List<PlannedRow> rows, List<SkippedRow> skipped) {}
