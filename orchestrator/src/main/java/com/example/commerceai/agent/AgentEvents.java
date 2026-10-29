package com.example.commerceai.agent;

import java.util.Map;

/** Callbacks the agent uses to stream progress to the client (SSE in ChatController). */
public interface AgentEvents {

    void status(String message);

    void actionRequired(String actionId, String type, String orderId, String summary);

    /** A chunk of answer text that has already passed the output guard. */
    void delta(String text);

    /** The text streamed so far was not the answer (the model went on to call tools); drop it. */
    void resetDraft();

    void answer(String text, Map<String, Object> meta);
}
