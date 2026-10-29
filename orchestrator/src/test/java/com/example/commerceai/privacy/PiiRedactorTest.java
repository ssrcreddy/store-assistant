package com.example.commerceai.privacy;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PiiRedactorTest {

    private final PiiVault vault = new PiiVault();
    private final PiiRedactor redactor = new PiiRedactor(vault);
    private final OutputGuard guard = new OutputGuard(redactor, vault);

    @Test
    void redactsEmailPhoneAndCard() {
        var r = redactor.redact("s1", "Email me at jane.doe@example.com or call +1 415-555-0132. Card 4111 1111 1111 1111.");

        assertThat(r.text()).doesNotContain("jane.doe@example.com", "415-555-0132", "4111 1111 1111 1111");
        assertThat(r.text()).contains("<EMAIL_1>", "<PHONE_1>", "<CARD_1>");
        assertThat(r.redactions()).isEqualTo(3);
    }

    @Test
    void leavesOrderIdsSkusAndDatesAlone() {
        String text = "Where is ORD-1001? I also want SKU-HEADPHONES, delivered 2026-11-02, total 129.99";

        assertThat(redactor.redact("s1", text).text()).isEqualTo(text);
    }

    @Test
    void ignoresDigitRunsThatFailLuhn() {
        var r = redactor.redact("s1", "Reference 1234 5678 9012 3456");

        assertThat(r.text()).doesNotContain("<CARD_");
    }

    @Test
    void sameValueGetsSamePlaceholderWithinSession() {
        String first = redactor.redact("s1", "a@b.com").text();
        String second = redactor.redact("s1", "again a@b.com").text();

        assertThat(second).isEqualTo("again " + first);
    }

    @Test
    void outputGuardRestoresPlaceholdersOnlyForTheSameSession() {
        String redacted = redactor.redact("s1", "my email is jane@example.com").text();
        String modelReply = "We'll send the receipt to " + redacted.substring(redacted.indexOf('<'));

        assertThat(guard.finalizeForUser("s1", modelReply).text()).contains("jane@example.com");
        assertThat(guard.finalizeForUser("other-session", modelReply).text()).doesNotContain("jane@example.com");
    }

    @Test
    void outputGuardMasksPiiTheModelInvented() {
        var result = guard.finalizeForUser("s1", "Your card 5555 5555 5555 4444 was charged.");

        assertThat(result.leakBlocked()).isTrue();
        assertThat(result.text()).contains("[REDACTED]").doesNotContain("5555 5555 5555 4444");
    }
}
