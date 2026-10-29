package com.example.commerceai.agent;

import java.util.Map;

/** Callbacks the agent uses to stream progress to the client (SSE in ChatController). */
public interface AgentEvents {

    void status(String message);

    void actionRequired(String actionId, String type, String orderId, String summary);

    void answer(String text, Map<String, Object> meta);
}
