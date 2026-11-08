package br.dev.duofinance.shared.email;

import static br.dev.duofinance.shared.email.EmailTemplate.*;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class EmailTemplateRendererTest {

    private final EmailTemplateRenderer renderer = new EmailTemplateRenderer();

    @Test
    void render_usesLocaleForSubjectAndBody() {
        var model = Map.<String, Object>of("name", "Ana", "link", "https://x/verify?token=t");
        var pt = renderer.render(VERIFY_EMAIL, "pt-BR", model);
        var en = renderer.render(VERIFY_EMAIL, "en", model);

        assertThat(pt.subject()).isEqualTo("Confirme seu e-mail no Duo Finance");
        assertThat(en.subject()).isEqualTo("Confirm your email on Duo Finance");
        assertThat(pt.html())
                .contains("https://x/verify?token=t")
                .contains("Ana")
                .contains("Confirmar e-mail");
        assertThat(en.html()).contains("Confirm email");
    }

    @Test
    void render_unknownLocaleFallsBackToPtBr() {
        var model = Map.<String, Object>of("name", "Ana", "link", "https://x/r");
        assertThat(renderer.render(RESET_PASSWORD, "fr", model).subject()).isEqualTo("Redefina sua senha");
        assertThat(renderer.render(RESET_PASSWORD, "en", model).subject()).isEqualTo("Reset your password");
    }

    @Test
    void render_invitationSubjectUsesInviterName() {
        var model = Map.<String, Object>of("inviterName", "Bia", "accountName", "Casa", "link", "https://x/i");
        assertThat(renderer.render(COUPLE_INVITATION, "pt-BR", model).subject())
                .isEqualTo("Bia convidou você para o Duo Finance");
        assertThat(renderer.render(COUPLE_INVITATION, "en", model).subject())
                .isEqualTo("Bia invited you to Duo Finance");
    }

    @Test
    void render_accessCodeShowsCodeAndLink() {
        var model = Map.<String, Object>of("code", "ABCD-1234", "link", "https://x/register?code=ABCD-1234");
        var r = renderer.render(ACCESS_CODE, "pt-BR", model);
        assertThat(r.subject()).isEqualTo("Seu acesso ao Duo Finance chegou");
        assertThat(r.html()).contains("ABCD-1234").contains("https://x/register?code=ABCD-1234");
    }
}
