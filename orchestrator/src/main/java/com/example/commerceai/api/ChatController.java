package com.example.commerceai.api;

import com.example.commerceai.agent.AgentEvents;
import com.example.commerceai.agent.AgentService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ExecutorService;

/**
 * POST /api/chat streams Server-Sent Events: "status" (progress), "action" (needs confirmation),
 * "answer" (final text + metadata) and "error".
 *
 * Demo auth: the user ID comes from the X-User-Id header. In production it comes from a verified
 * JWT (Amazon Cognito) via Spring Security's resource server support, never from the client.
 */
@RestController
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    public record ChatRequest(@NotBlank @Size(max = 64) String sessionId, @NotBlank @Size(max = 2000) String message) {}

    private final AgentService agent;
    private final ExecutorService agentExecutor;

    public ChatController(AgentService agent, ExecutorService agentExecutor) {
        this.agent = agent;
        this.agentExecutor = agentExecutor;
    }

    @PostMapping(value = "/api/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(@RequestHeader("X-User-Id") String userId, @Valid @RequestBody ChatRequest req) {
        SseEmitter emitter = new SseEmitter(60_000L);
        agentExecutor.execute(() -> {
            try {
                agent.handle(req.sessionId(), userId, req.message(), new SseEvents(emitter));
                emitter.complete();
            } catch (Exception e) {
                log.error("Chat turn failed for session {}", req.sessionId(), e);
                send(emitter, "error", Map.of("message", "Something went wrong on our side. Please try again."));
                emitter.complete();
            }
        });
        return emitter;
    }

    private record SseEvents(SseEmitter emitter) implements AgentEvents {
        @Override
        public void status(String message) {
            send(emitter, "status", Map.of("message", message));
        }

        @Override
        public void actionRequired(String actionId, String type, String orderId, String summary) {
            send(emitter, "action", Map.of("actionId", actionId, "type", type, "orderId", orderId, "summary", summary));
        }

        @Override
        public void answer(String text, Map<String, Object> meta) {
            send(emitter, "answer", Map.of("text", text, "meta", meta));
        }
    }

    private static void send(SseEmitter emitter, String name, Object data) {
        try {
            emitter.send(SseEmitter.event().name(name).data(data, MediaType.APPLICATION_JSON));
        } catch (IOException | IllegalStateException e) {
            log.debug("Client disconnected before '{}' event", name);
        }
    }
}
