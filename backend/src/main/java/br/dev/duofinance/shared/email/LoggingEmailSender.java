package br.dev.duofinance.shared.email;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.email.provider", havingValue = "LOG")
public class LoggingEmailSender implements EmailSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingEmailSender.class);
    private static final Pattern LINK = Pattern.compile("https?://[^\\s\"'<>]+");

    @Override
    public void send(EmailMessage message) {
        List<String> links = new ArrayList<>();
        Matcher m = LINK.matcher(message.html());
        while (m.find()) {
            String link = m.group().replace("&amp;", "&");
            if (!links.contains(link)) {
                links.add(link);
            }
        }
        log.info("Email to={} subject=\"{}\" links={}", message.to(), message.subject(), links);
    }
}
