package br.dev.duofinance.shared.email;

public enum EmailTemplate {
    VERIFY_EMAIL("verify-email"),
    RESET_PASSWORD("reset-password"),
    COUPLE_INVITATION("couple-invitation"),
    ACCESS_CODE("access-code");

    private final String templateName;

    EmailTemplate(String templateName) {
        this.templateName = templateName;
    }

    public String templateName() {
        return templateName;
    }
}
