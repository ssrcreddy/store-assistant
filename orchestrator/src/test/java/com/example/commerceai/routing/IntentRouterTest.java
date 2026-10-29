package com.example.commerceai.routing;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class IntentRouterTest {

    private final IntentRouter router = new IntentRouter();

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "Where is my order ORD-1001?                          | TEMPLATE",
            "track ord-1002                                       | TEMPLATE",
            "Please cancel ORD-1001, I ordered by mistake         | SMART",
            "I was charged twice for ORD-1003                     | SMART",
            "Is SKU-HEADPHONES in stock?                          | FAST",
            "What is your return window for electronics?          | FAST",
            "My payment failed for ORD-1004                       | SMART",
            "Show me my recent orders                             | FAST",
    })
    void routesByIntent(String text, Route expected) {
        assertThat(router.route(text.trim()).route()).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "where is ORD-1001                                    | ORD-1001",
            "status of ord-2002 please                            | ORD-2002",
    })
    void extractsOrderId(String text, String expected) {
        assertThat(router.route(text.trim()).orderId()).contains(expected);
    }
}
