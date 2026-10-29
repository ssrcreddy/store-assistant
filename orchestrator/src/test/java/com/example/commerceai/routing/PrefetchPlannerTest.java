package com.example.commerceai.routing;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class PrefetchPlannerTest {

    private final PrefetchPlanner planner = new PrefetchPlanner();

    private List<String> tools(String text) {
        return planner.plan(text, IntentRouter.findOrderId(text)).stream().map(PrefetchPlanner.PlannedCall::tool).toList();
    }

    @Test
    void orderIdFetchesTheOrder() {
        var calls = planner.plan("Please cancel ORD-1002, I ordered by mistake", Optional.of("ORD-1002"));

        assertThat(calls).containsExactly(new PrefetchPlanner.PlannedCall("get_order", Map.of("order_id", "ORD-1002")));
    }

    @Test
    void paymentQuestionAlsoFetchesPaymentStatus() {
        assertThat(tools("My payment failed for ORD-1004, what now?")).containsExactly("get_order", "get_payment_status");
    }

    @Test
    void skuFetchesInventoryOncePerSku() {
        var calls = planner.plan("Is sku-keyboard in stock? And SKU-KEYBOARD in black?", Optional.empty());

        assertThat(calls).containsExactly(new PrefetchPlanner.PlannedCall("check_inventory", Map.of("sku", "SKU-KEYBOARD")));
    }

    @Test
    void myOrdersListsOrdersOnlyWithoutAnOrderId() {
        assertThat(tools("Show me my recent orders")).containsExactly("list_orders");
        assertThat(tools("Is ORD-1001 one of my orders?")).containsExactly("get_order");
    }

    @Test
    void policyQuestionSearchesPolicies() {
        assertThat(tools("What is the return window for electronics?")).containsExactly("search_policy");
    }

    @Test
    void neverPrefetchesWriteToolsAndCapsTheCount() {
        var calls = tools("Refund and cancel ORD-1003, payment for SKU-A, SKU-B and SKU-C, return policy?");

        assertThat(calls).hasSize(PrefetchPlanner.MAX_CALLS).doesNotContain("cancel_order", "create_payment_link");
    }

    @Test
    void smallTalkFetchesNothing() {
        assertThat(tools("Hi, can you help me?")).isEmpty();
    }
}
