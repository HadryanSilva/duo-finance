package br.dev.duofinance.shared.email;

import java.util.Locale;
import java.util.Map;
import org.springframework.context.MessageSource;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.stereotype.Component;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

/** Renders e-mails with a private Thymeleaf engine, isolated from Boot's web view resolution. */
@Component
public class EmailTemplateRenderer {

    public record RenderedEmail(String subject, String html) {}

    private static final Locale PT_BR = Locale.forLanguageTag("pt-BR");

    private final SpringTemplateEngine engine = new SpringTemplateEngine();
    private final MessageSource messages;

    public EmailTemplateRenderer() {
        ResourceBundleMessageSource source = new ResourceBundleMessageSource();
        source.setBasename("i18n/email");
        source.setDefaultEncoding("UTF-8");
        source.setFallbackToSystemLocale(false);
        this.messages = source;

        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/email/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode(TemplateMode.HTML);
        resolver.setCharacterEncoding("UTF-8");
        engine.setTemplateResolver(resolver);
        engine.setTemplateEngineMessageSource(source);
    }

    public RenderedEmail render(EmailTemplate template, String locale, Map<String, Object> model) {
        Locale resolved = "en".equalsIgnoreCase(locale) ? Locale.ENGLISH : PT_BR;
        Context context = new Context(resolved, model);
        String html = engine.process(template.templateName(), context);
        Object[] args = model.get("inviterName") == null ? new Object[0] : new Object[] {model.get("inviterName")};
        String subject = messages.getMessage(template.templateName() + ".subject", args, resolved);
        return new RenderedEmail(subject, html);
    }
}
