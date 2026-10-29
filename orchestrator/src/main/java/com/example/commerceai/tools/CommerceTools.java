package com.example.commerceai.tools;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.ToolUseBlock;
import com.example.commerceai.privacy.PiiRedactor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Tool adapter layer: tool definitions for the model, and execution against the legacy APIs.
 * Results are trimmed projections (no names, addresses, emails or card data) and pass through
 * the redactor again as a second line of defence.
 */
@Component
public class CommerceTools {

    private static final List<String> ORDER_STATUSES = List.of("any", "placed", "packed", "shipped", "delivered", "cancelled");

    private final CommerceApiClient api;
    private final PolicyEngine policy;
    private final PolicyKnowledgeBase kb;
    private final PiiRedactor redactor;
    private final ObjectMapper json;
    private final List<Tool> definitions;

    public CommerceTools(CommerceApiClient api, PolicyEngine policy, PolicyKnowledgeBase kb,
                         PiiRedactor redactor, ObjectMapper json) {
        this.api = api;
        this.policy = policy;
        this.kb = kb;
        this.redactor = redactor;
        this.json = json;
        this.definitions = buildDefinitions();
    }

    public record Outcome(String content, boolean isError, Optional<PendingAction> pendingAction) {
        static Outcome ok(String content) {
            return new Outcome(content, false, Optional.empty());
        }

        static Outcome error(String content) {
            return new Outcome(content, true, Optional.empty());
        }
    }

    /** Stable order and content: tool definitions are part of the cached prompt prefix. */
    public List<Tool> definitions() {
        return definitions;
    }

    public static String statusMessage(String toolName) {
        return switch (toolName) {
            case "get_order", "list_orders" -> "Checking your orders";
            case "check_inventory" -> "Checking stock";
            case "get_payment_status", "create_payment_link" -> "Checking payment";
            case "cancel_order" -> "Preparing cancellation";
            case "search_policy" -> "Looking up our policies";
            default -> "Working on it";
        };
    }

    /** Opens the HTTP connection to the legacy APIs. The SKU doesn't exist; a 404 is expected and ignored. */
    public void warmUp() {
        try {
            api.getInventory("SKU-WARMUP");
        } catch (RuntimeException ignored) {
            // Only the connection matters here.
        }
    }

    public Outcome execute(ToolUseBlock call, String userId, String sessionId) {
        Map<String, Object> in;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = call._input().convert(Map.class);
            in = parsed;
        } catch (RuntimeException e) {
            return Outcome.error("Invalid tool input: " + e.getMessage());
        }
        try {
            Outcome outcome = switch (call.name()) {
                case "get_order" -> Outcome.ok(write(orderView(api.getOrder(userId, str(in, "order_id")))));
                case "list_orders" -> Outcome.ok(write(listView(api.listOrders(userId, str(in, "status")))));
                case "check_inventory" -> Outcome.ok(write(inventoryView(api.getInventory(str(in, "sku")))));
                case "get_payment_status" -> Outcome.ok(write(paymentView(api.getPayment(userId, str(in, "order_id")))));
                case "create_payment_link" -> Outcome.ok(write(api.createPaymentLink(userId, str(in, "order_id"))));
                case "cancel_order" -> proposeCancel(userId, sessionId, str(in, "order_id"), str(in, "reason"));
                case "search_policy" -> Outcome.ok(String.join("\n", kb.search(str(in, "query"), 3)));
                default -> Outcome.error("Unknown tool " + call.name());
            };
            return new Outcome(redactor.redact(sessionId, outcome.content()).text(), outcome.isError(), outcome.pendingAction());
        } catch (HttpClientErrorException.NotFound e) {
            return Outcome.error("Not found on this customer's account.");
        } catch (HttpClientErrorException e) {
            return Outcome.error("The service rejected the request (" + e.getStatusCode().value() + ").");
        } catch (ResourceAccessException e) {
            return Outcome.error("The service is not responding right now. Tell the user to try again shortly.");
        }
    }

    private Outcome proposeCancel(String userId, String sessionId, String orderId, String reason) {
        PolicyEngine.Proposal p = policy.proposeCancel(userId, sessionId, orderId, reason);
        if (p instanceof PolicyEngine.Proposal.Accepted accepted) {
            return new Outcome("{\"status\":\"confirmation_required\",\"message\":\"A confirmation button is now shown to the user. "
                    + "The order is NOT cancelled until they press it.\"}", false, Optional.of(accepted.action()));
        }
        return Outcome.error(((PolicyEngine.Proposal.Rejected) p).reason());
    }

    // ---- projections: only the fields the model needs ----

    private ObjectNode orderView(JsonNode o) {
        ObjectNode v = json.createObjectNode();
        v.put("order_id", o.path("id").asText());
        v.put("status", o.path("status").asText());
        v.put("placed_at", o.path("placedAt").asText());
        v.put("eta", o.path("eta").asText(null));
        v.put("carrier", o.path("carrier").asText(null));
        v.put("total", o.path("total").asText() + " " + o.path("currency").asText());
        ArrayNode items = v.putArray("items");
        for (JsonNode i : o.path("items")) {
            items.addObject().put("sku", i.path("sku").asText()).put("name", i.path("name").asText()).put("qty", i.path("qty").asInt());
        }
        return v;
    }

    private ArrayNode listView(JsonNode list) {
        ArrayNode out = json.createArrayNode();
        int n = 0;
        for (JsonNode o : list) {
            if (n++ == 5) {
                break;
            }
            out.addObject().put("order_id", o.path("id").asText()).put("status", o.path("status").asText())
                    .put("placed_at", o.path("placedAt").asText()).put("total", o.path("total").asText() + " " + o.path("currency").asText());
        }
        return out;
    }

    private ObjectNode inventoryView(JsonNode i) {
        return json.createObjectNode().put("sku", i.path("sku").asText()).put("name", i.path("name").asText())
                .put("available", i.path("available").asInt()).put("restock_date", i.path("restockDate").asText(null));
    }

    private ObjectNode paymentView(JsonNode p) {
        return json.createObjectNode().put("order_id", p.path("orderId").asText()).put("status", p.path("status").asText())
                .put("method", p.path("method").asText()).put("amount", p.path("amount").asText() + " " + p.path("currency").asText());
    }

    private String write(JsonNode node) {
        try {
            return json.writeValueAsString(node);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String str(Map<String, Object> in, String key) {
        Object v = in.get(key);
        if (v == null) {
            throw new IllegalArgumentException("Missing " + key);
        }
        return v.toString().trim();
    }

    // ---- definitions ----

    private static List<Tool> buildDefinitions() {
        Map<String, Object> orderId = prop("string", "Order number, e.g. ORD-1001");
        return List.of(
                tool("get_order", "Get one order's status, items, delivery estimate and carrier.",
                        props("order_id", orderId)),
                tool("list_orders", "List the customer's most recent orders (max 5), optionally filtered by status.",
                        props("status", enumProp(ORDER_STATUSES, "Use 'any' for all statuses"))),
                tool("check_inventory", "Check stock and restock date for a product SKU.",
                        props("sku", prop("string", "Product SKU, e.g. SKU-HEADPHONES"))),
                tool("get_payment_status", "Get the payment status and method for an order. Never returns card numbers.",
                        props("order_id", orderId)),
                tool("create_payment_link", "Create a secure payment link for an order with a failed or pending payment.",
                        props("order_id", orderId)),
                tool("cancel_order", "Propose cancelling an order. This does NOT cancel it: the user must confirm in the app.",
                        props("order_id", orderId, "reason", prop("string", "Short reason in the customer's words"))),
                tool("search_policy", "Search the store's return, refund, shipping and payment policies.",
                        props("query", prop("string", "What to look up, e.g. 'return window for electronics'"))));
    }

    private static Tool tool(String name, String description, Map<String, Object> properties) {
        Tool.InputSchema.Properties.Builder pb = Tool.InputSchema.Properties.builder();
        properties.forEach((k, v) -> pb.putAdditionalProperty(k, JsonValue.from(v)));
        return Tool.builder()
                .name(name)
                .description(description)
                .strict(true)
                .inputSchema(Tool.InputSchema.builder()
                        .properties(pb.build())
                        .required(List.copyOf(properties.keySet()))
                        .putAdditionalProperty("additionalProperties", JsonValue.from(false))
                        .build())
                .build();
    }

    /** LinkedHashMap, not Map.of: Map.of iteration order varies between JVMs and would break prompt caching across pods. */
    private static Map<String, Object> props(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    private static Map<String, Object> prop(String type, String description) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type);
        m.put("description", description);
        return m;
    }

    private static Map<String, Object> enumProp(List<String> values, String description) {
        Map<String, Object> m = prop("string", description);
        m.put("enum", values);
        return m;
    }
}
