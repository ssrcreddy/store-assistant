package com.example.commerceai.routing;

import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rules-first router: cheap, predictable and easy to test. Add a Haiku classifier for the
 * ambiguous middle once the eval set shows where the rules miss.
 */
@Component
public class IntentRouter {

    public static final Pattern ORDER_ID = Pattern.compile("\\bORD-\\d{4,}\\b", Pattern.CASE_INSENSITIVE);

    private static final Pattern SMART_INTENT = Pattern.compile(
            "\\b(cancel|refund|charged|charge|payment failed|complain|complaint|damaged|broken|wrong item|not happy|angry|escalate)\\b",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern STATUS_INTENT = Pattern.compile(
            "\\b(where is|where's|status|track|tracking|when will)\\b", Pattern.CASE_INSENSITIVE);

    public record Decision(Route route, String reason, Optional<String> orderId) {}

    public Decision route(String redactedText) {
        String text = redactedText == null ? "" : redactedText;
        Optional<String> orderId = findOrderId(text);

        if (SMART_INTENT.matcher(text).find()) {
            return new Decision(Route.SMART, "write action or sensitive topic", orderId);
        }
        if (orderId.isPresent() && STATUS_INTENT.matcher(text).find() && text.length() <= 120) {
            return new Decision(Route.TEMPLATE, "single order status lookup", orderId);
        }
        return new Decision(Route.FAST, "default lookup or FAQ", orderId);
    }

    static Optional<String> findOrderId(String text) {
        Matcher m = ORDER_ID.matcher(text);
        return m.find() ? Optional.of(m.group().toUpperCase()) : Optional.empty();
    }
}
