package br.dev.duofinance.support;

import br.dev.duofinance.shared.email.EmailMessage;
import br.dev.duofinance.shared.email.EmailSender;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
