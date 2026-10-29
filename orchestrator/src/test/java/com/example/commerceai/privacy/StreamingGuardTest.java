package com.example.commerceai.privacy;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StreamingGuardTest {

    private final PiiVault vault = new PiiVault();
    private final PiiRedactor redactor = new PiiRedactor(vault);
    private final OutputGuard guard = new OutputGuard(redactor, vault);

    private List<String> stream(String sessionId, String... deltas) {
        List<String> out = new ArrayList<>();
        StreamingGuard sg = new StreamingGuard(guard, sessionId, out::add);
        for (String d : deltas) {
            sg.accept(d);
        }
        sg.flush();
        return out;
    }

    @Test
    void cardSplitAcrossDeltasIsNeverEmittedRaw() {
        List<String> out = stream("s1", "Your card is 4111 11", "11 1111 1", "111 and that is all.");

        assertThat(String.join("", out)).doesNotContain("4111").contains("[REDACTED]");
        assertThat(out).noneMatch(chunk -> chunk.matches(".*\\d{4}.*"));
    }

    @Test
    void phoneWithParenthesesSplitAtSpaceIsMasked() {
        List<String> out = stream("s1", "Call (415) ", "555-0132 today, ", "please.");

        assertThat(String.join("", out)).doesNotContain("555-0132");
    }

    @Test
    void placeholderSplitAcrossDeltasIsRestoredForOwner() {
        String placeholder = redactor.redact("s1", "jane.doe@example.com").text();
        List<String> out = stream("s1", "We emailed ", placeholder.substring(0, 4), placeholder.substring(4) + " just now.");

        assertThat(String.join("", out)).isEqualTo("We emailed jane.doe@example.com just now.");
    }

    @Test
    void streamedTextMatchesGuardingTheWholeText() {
        String text = "Order ORD-1002 ships soon. Reach us at help@shop.example or +1 415-555-0132 anytime.";
        List<String> deltas = new ArrayList<>();
        for (int i = 0; i < text.length(); i += 3) {
            deltas.add(text.substring(i, Math.min(text.length(), i + 3)));
        }

        String streamed = String.join("", stream("s2", deltas.toArray(String[]::new)));

        assertThat(streamed).isEqualTo(guard.finalizeForUser("s2", text).text());
    }

    @Test
    void emitsChunksBeforeTheEnd() {
        List<String> out = new ArrayList<>();
        StreamingGuard sg = new StreamingGuard(guard, "s1", out::add);
        sg.accept("Your order has shipped and ");

        assertThat(out).isNotEmpty();
    }
}
