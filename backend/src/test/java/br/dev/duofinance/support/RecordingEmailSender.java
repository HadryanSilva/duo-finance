package br.dev.duofinance.support;

import br.dev.duofinance.shared.email.EmailMessage;
import br.dev.duofinance.shared.email.EmailSender;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.awaitility.Awaitility;

/** Thread-safe test sender that captures every e-mail (listeners are async). */
public class RecordingEmailSender implements EmailSender {

    private final List<EmailMessage> sent = new CopyOnWriteArrayList<>();

    @Override
    public void send(EmailMessage message) {
        sent.add(message);
    }

    public List<EmailMessage> sent() {
        return List.copyOf(sent);
    }

    public void clear() {
        sent.clear();
    }

    /** Most recent message sent to the given address (case-insensitive), or null. */
    public EmailMessage lastTo(String email) {
        EmailMessage last = null;
        for (EmailMessage m : sent) {
            if (m.to().equalsIgnoreCase(email)) {
                last = m;
            }
        }
        return last;
    }

    /**
     * Waits for a message to the address (case-insensitive) that carries a link under the path prefix and returns the
     * most recent such message. Filtering inside the wait keeps a late, unrelated e-mail (e.g. the async verification
     * mail of a fresh sign-up) from being picked up instead.
     */
    public EmailMessage awaitMessageWithLink(String email, String pathPrefix) {
        Supplier<EmailMessage> latest = () -> {
            EmailMessage match = null;
            for (EmailMessage m : sent) {
                if (m.to().equalsIgnoreCase(email) && extractLink(m, pathPrefix) != null) {
                    match = m;
                }
            }
            return match;
        };
        return Awaitility.await().until(latest::get, Objects::nonNull);
    }

    /** The link under the path prefix from {@link #awaitMessageWithLink}. */
    public String awaitLink(String email, String pathPrefix) {
        return extractLink(awaitMessageWithLink(email, pathPrefix), pathPrefix);
    }

    /** First http(s) link in the HTML whose path starts with the prefix, e.g. "/verify-email". */
    public String extractLink(EmailMessage message, String pathPrefix) {
        Matcher m = Pattern.compile("https?://[^\\s\"'<>]+").matcher(message.html());
        while (m.find()) {
            String link = m.group().replace("&amp;", "&");
            String path = java.net.URI.create(link).getPath();
            if (path != null && path.startsWith(pathPrefix)) {
                return link;
            }
        }
        return null;
    }
}
