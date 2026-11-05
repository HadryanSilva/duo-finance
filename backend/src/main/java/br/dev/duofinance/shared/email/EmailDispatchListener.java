package br.dev.duofinance.shared.email;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class EmailDispatchListener {

    private static final Logger log = LoggerFactory.getLogger(EmailDispatchListener.class);

    private final EmailTemplateRenderer renderer;
    private final EmailSender sender;

    public EmailDispatchListener(EmailTemplateRenderer renderer, EmailSender sender) {
        this.renderer = renderer;
        this.sender = sender;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void on(EmailRequestedEvent event) {
        try {
            var rendered = renderer.render(event.template(), event.locale(), event.model());
            sender.send(new EmailMessage(event.to(), rendered.subject(), rendered.html()));
        } catch (Exception e) {
            log.error("Failed to send {} email to {}", event.template(), event.to(), e);
        }
    }
}
