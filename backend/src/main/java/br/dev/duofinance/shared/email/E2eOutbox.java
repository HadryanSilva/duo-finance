package br.dev.duofinance.shared.email;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedList;
import java.util.List;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Keeps the last sent e-mails in memory (and still logs them) so browser end-to-end tests can read links. */
@Component
@Primary
@Profile("e2e")
public class E2eOutbox implements EmailSender {

    static final int CAPACITY = 200;

    private final LoggingEmailSender logging;
    private final Deque<EmailMessage> messages = new LinkedList<>();

    public E2eOutbox(LoggingEmailSender logging) {
        this.logging = logging;
    }

    @Override
    public void send(EmailMessage message) {
        synchronized (messages) {
            messages.addFirst(message);
            while (messages.size() > CAPACITY) {
                messages.removeLast();
            }
        }
        logging.send(message);
    }

    /** Messages to the address (case-insensitive), newest first. */
    public List<EmailMessage> sentTo(String email) {
        synchronized (messages) {
            List<EmailMessage> result = new ArrayList<>();
            for (EmailMessage m : messages) {
                if (m.to().equalsIgnoreCase(email)) {
                    result.add(m);
                }
            }
            return Collections.unmodifiableList(result);
        }
    }
}
