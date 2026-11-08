package br.dev.duofinance.shared.email;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Extracts the distinct http(s) links of an e-mail body, in order of appearance. */
public final class EmailLinks {

    private static final Pattern LINK = Pattern.compile("https?://[^\s\"'<>]+");

    private EmailLinks() {}

    public static List<String> extract(String html) {
        List<String> links = new ArrayList<>();
        Matcher m = LINK.matcher(html);
        while (m.find()) {
            String link = m.group().replace("&amp;", "&");
            if (!links.contains(link)) {
                links.add(link);
            }
        }
        return links;
    }
}
