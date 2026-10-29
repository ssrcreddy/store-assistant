package com.example.commerceai.routing;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Picks the read-only tool calls a question obviously needs, so they run before the first model
 * call instead of costing a model round. Write tools (cancel_order, create_payment_link) are never
 * prefetched: they stay with the model and the confirmation step.
 */
@Component
public class PrefetchPlanner {

    static final int MAX_CALLS = 3;

    private static final Pattern SKU = Pattern.compile("\\bSKU-[A-Z0-9]+(?:-[A-Z0-9]+)*\\b", Pattern.CASE_INSENSITIVE);

    private static final Pattern PAYMENT = Pattern.compile(
            "\\b(pay|paid|payment|charged|charge|billing)\\b", Pattern.CASE_INSENSITIVE);

    private static final Pattern MY_ORDERS = Pattern.compile(
            "\\b(my|recent|past|all) orders\\b|\\border history\\b", Pattern.CASE_INSENSITIVE);

    private static final Pattern POLICY = Pattern.compile(
            "\\b(return|returns|refunds?|warranty|exchange|shipping|policy|policies)\\b", Pattern.CASE_INSENSITIVE);

    public record PlannedCall(String tool, Map<String, Object> input) {}

    public List<PlannedCall> plan(String redactedText, Optional<String> orderId) {
        String text = redactedText == null ? "" : redactedText;
        List<PlannedCall> calls = new ArrayList<>();

        orderId.ifPresent(id -> {
            calls.add(call("get_order", "order_id", id));
            if (PAYMENT.matcher(text).find()) {
                calls.add(call("get_payment_status", "order_id", id));
            }
        });
        if (orderId.isEmpty() && MY_ORDERS.matcher(text).find()) {
            calls.add(call("list_orders", "status", "any"));
        }
        Set<String> skus = new LinkedHashSet<>();
        Matcher m = SKU.matcher(text);
        while (m.find()) {
            skus.add(m.group().toUpperCase());
        }
        skus.forEach(sku -> calls.add(call("check_inventory", "sku", sku)));
        if (POLICY.matcher(text).find()) {
            calls.add(call("search_policy", "query", text));
        }
        return calls.size() > MAX_CALLS ? List.copyOf(calls.subList(0, MAX_CALLS)) : calls;
    }

    private static PlannedCall call(String tool, String key, String value) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put(key, value);
        return new PlannedCall(tool, input);
    }
}
