package br.dev.duofinance.category.service;

import br.dev.duofinance.category.model.CategoryKind;
import java.util.List;

/** Default categories seeded for a new account. Icons are Lucide names; colors are palette hex tokens. */
public final class DefaultCategories {

    private static final String GREEN = "#16A34A";
    private static final String INDIGO = "#4F46E5";
    private static final String BLUE = "#2563EB";
    private static final String ORANGE = "#EA580C";
    private static final String RED = "#DC2626";
    private static final String PINK = "#DB2777";
    private static final String PURPLE = "#7C3AED";
    private static final String TEAL = "#0D9488";
    private static final String GRAY = "#6B7280";
    private static final String AMBER = "#D97706";

    private DefaultCategories() {}

    public static List<DefaultCategory> forLocale(String locale) {
        boolean pt = "pt-BR".equals(locale);
        return List.of(
                expense(pt ? "Mercado" : "Groceries", GREEN, "shopping-cart"),
                expense(pt ? "Moradia" : "Housing", INDIGO, "house"),
                expense(pt ? "Transporte" : "Transport", BLUE, "car"),
                expense(pt ? "Restaurantes" : "Restaurants", ORANGE, "utensils"),
                expense(pt ? "Saúde" : "Health", RED, "heart-pulse"),
                expense(pt ? "Lazer" : "Leisure", PINK, "party-popper"),
                expense(pt ? "Assinaturas" : "Subscriptions", PURPLE, "repeat"),
                expense(pt ? "Educação" : "Education", TEAL, "graduation-cap"),
                expense(pt ? "Outros" : "Other", GRAY, "ellipsis"),
                income(pt ? "Salário" : "Salary", GREEN, "banknote"),
                income("Freelance", TEAL, "laptop"),
                income(pt ? "Rendimentos" : "Investment income", AMBER, "trending-up"),
                income(pt ? "Outras receitas" : "Other income", GRAY, "plus"));
    }

    private static DefaultCategory expense(String name, String color, String icon) {
        return new DefaultCategory(name, CategoryKind.EXPENSE, color, icon);
    }

    private static DefaultCategory income(String name, String color, String icon) {
        return new DefaultCategory(name, CategoryKind.INCOME, color, icon);
    }

    public record DefaultCategory(String name, CategoryKind kind, String color, String icon) {}
}
