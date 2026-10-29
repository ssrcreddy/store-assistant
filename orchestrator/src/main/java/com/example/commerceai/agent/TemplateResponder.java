package com.example.commerceai.agent;

import com.example.commerceai.tools.CommerceApiClient;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;

import java.util.Optional;

/**
 * The no-LLM path: answers simple order-status questions straight from the API with a template.
 * Returns empty when it can't answer, and the request falls through to the model.
 */
@Component
public class TemplateResponder {

    private final CommerceApiClient api;

    public TemplateResponder(CommerceApiClient api) {
        this.api = api;
    }

    public Optional<String> orderStatus(String userId, String orderId) {
        JsonNode o;
        try {
            o = api.getOrder(userId, orderId);
        } catch (HttpClientErrorException.NotFound e) {
            return Optional.of("I couldn't find order " + orderId + " on your account. Please check the order number.");
        } catch (RestClientException e) {
            return Optional.empty();
        }
        String status = o.path("status").asText();
        String eta = o.path("eta").asText(null);
        String carrier = o.path("carrier").asText(null);
        String text = switch (status) {
            case "placed", "packed" -> "Order " + orderId + " is " + status + " and hasn't shipped yet."
                    + (eta != null ? " Expected delivery: " + eta + "." : "");
            case "shipped" -> "Order " + orderId + " has shipped" + (carrier != null ? " with " + carrier : "") + "."
                    + (eta != null ? " Expected delivery: " + eta + "." : "");
            case "delivered" -> "Order " + orderId + " was delivered" + (eta != null ? " on " + eta : "") + ".";
            case "cancelled" -> "Order " + orderId + " was cancelled. Any payment is refunded to the original method.";
            default -> null;
        };
        return Optional.ofNullable(text);
    }
}
