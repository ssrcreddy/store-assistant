package com.example.commerceai.privacy;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Replaces PII in text with session-scoped placeholders before anything reaches the LLM.
 * Regex covers structured PII (email, card, phone, SSN, PAN). Names and street addresses need
 * NER: put a Presidio sidecar behind this class as the next step.
 */
@Component
public class PiiRedactor {

    private record Rule(String type, Pattern pattern) {}

    // Order matters: cards before phones, because a 16-digit card also looks like a long phone number.
    private static final List<Rule> RULES = List.of(
            new Rule("EMAIL", Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")),
            new Rule("CARD", Pattern.compile("(?<![\\w-])(?:\\d[ -]?){12,18}\\d(?![\\w-])")),
            new Rule("SSN", Pattern.compile("(?<!\\d)\\d{3}-\\d{2}-\\d{4}(?!\\d)")),
            new Rule("PAN", Pattern.compile("(?<![A-Za-z0-9])[A-Z]{5}\\d{4}[A-Z](?![A-Za-z0-9])")),
            new Rule("PHONE", Pattern.compile("(?<![\\w-])\\+?\\(?\\d[\\d ()-]{8,15}\\d(?![\\w-])")));

    private final PiiVault vault;

    public PiiRedactor(PiiVault vault) {
        this.vault = vault;
    }

    public record Result(String text, int redactions) {}

    public Result redact(String sessionId, String text) {
        if (text == null || text.isEmpty()) {
            return new Result(text, 0);
        }
        String current = text;
        int count = 0;
        for (Rule rule : RULES) {
            Matcher m = rule.pattern().matcher(current);
            StringBuilder out = new StringBuilder();
            while (m.find()) {
                String value = m.group();
                if (!isRealMatch(rule.type(), value)) {
                    m.appendReplacement(out, Matcher.quoteReplacement(value));
                    continue;
                }
                count++;
                m.appendReplacement(out, Matcher.quoteReplacement(vault.placeholderFor(sessionId, rule.type(), value)));
            }
            m.appendTail(out);
            current = out.toString();
        }
        return new Result(current, count);
    }

    /** Detects raw PII without storing it (used by the output guard). */
    public boolean containsPii(String text) {
        if (text == null) {
            return false;
        }
        for (Rule rule : RULES) {
            Matcher m = rule.pattern().matcher(text);
            while (m.find()) {
                if (isRealMatch(rule.type(), m.group())) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Masks raw PII with a fixed marker; nothing is stored. */
    public String mask(String text) {
        String current = text;
        for (Rule rule : RULES) {
            Matcher m = rule.pattern().matcher(current);
            StringBuilder out = new StringBuilder();
            while (m.find()) {
                String replacement = isRealMatch(rule.type(), m.group()) ? "[REDACTED]" : m.group();
                m.appendReplacement(out, Matcher.quoteReplacement(replacement));
            }
            m.appendTail(out);
            current = out.toString();
        }
        return current;
    }

    private static boolean isRealMatch(String type, String value) {
        String digits = value.replaceAll("\\D", "");
        return switch (type) {
            case "CARD" -> digits.length() >= 13 && digits.length() <= 19 && luhnValid(digits);
            case "PHONE" -> digits.length() >= 10 && digits.length() <= 13;
            default -> true;
        };
    }

    static boolean luhnValid(String digits) {
        int sum = 0;
        boolean alternate = false;
        for (int i = digits.length() - 1; i >= 0; i--) {
            int n = digits.charAt(i) - '0';
            if (alternate) {
                n *= 2;
                if (n > 9) {
                    n -= 9;
                }
            }
            sum += n;
            alternate = !alternate;
        }
        return sum % 10 == 0;
    }
}
