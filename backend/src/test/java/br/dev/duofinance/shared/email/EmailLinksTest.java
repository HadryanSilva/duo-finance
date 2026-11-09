package br.dev.duofinance.shared.email;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class EmailLinksTest {

    @Test
    void extract_endsLinkAtWhitespaceAndQuotes() {
        String html = "see https://a.test/x?y=1&amp;z=2\nnext https://b.test/p more <a href=\"https://c.test/q\">c</a>"
                + " https://a.test/x?y=1&z=2";

        assertThat(EmailLinks.extract(html))
                .containsExactly("https://a.test/x?y=1&z=2", "https://b.test/p", "https://c.test/q");
    }

    @Test
    void extract_doesNotSwallowTheTextAfterANewline() {
        assertThat(EmailLinks.extract("https://a.test/x\nverify-email")).containsExactly("https://a.test/x");
    }
}
