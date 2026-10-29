package com.example.commerceai.agent;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.DirectCaller;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.anthropic.models.messages.ToolUseBlock;
import com.example.commerceai.config.AppProperties;
import com.example.commerceai.llm.BudgetExceededException;
import com.example.commerceai.llm.LlmGateway;
import com.example.commerceai.llm.ModelPricing;
import com.example.commerceai.llm.UsageLedger;
import com.example.commerceai.privacy.OutputGuard;
import com.example.commerceai.privacy.PiiRedactor;
import com.example.commerceai.privacy.StreamingGuard;
import com.example.commerceai.routing.IntentRouter;
import com.example.commerceai.routing.PrefetchPlanner;
import com.example.commerceai.routing.ResponseCache;
import com.example.commerceai.routing.Route;
import com.example.commerceai.tools.CommerceTools;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/**
 * One user turn, end to end:
 * redact -> cache -> route -> (template | agent loop with tools) -> output guard -> answer.
 */
@Service
public class AgentService {

    private static final Logger log = LoggerFactory.getLogger(AgentService.class);

    static final String HANDOFF = "I'd like a member of our support team to help with this. I've passed your conversation on, and they'll reply here shortly.";
    static final String BUDGET_MESSAGE = "Our assistant is temporarily limited. You can still track orders by number, or a support agent can help.";

    private final IntentRouter router;
    private final ResponseCache cache;
    private final PiiRedactor redactor;
    private final OutputGuard outputGuard;
    private final TemplateResponder templates;
    private final LlmGateway gateway;
    private final CommerceTools tools;
    private final PrefetchPlanner prefetch;
    private final SystemPrompt systemPrompt;
    private final ConversationStore conversations;
    private final UsageLedger ledger;
    private final AppProperties props;
    private final ExecutorService toolPool = Executors.newFixedThreadPool(32);

    public AgentService(IntentRouter router, ResponseCache cache, PiiRedactor redactor, OutputGuard outputGuard,
                        TemplateResponder templates, LlmGateway gateway, CommerceTools tools, PrefetchPlanner prefetch, SystemPrompt systemPrompt,
                        ConversationStore conversations, UsageLedger ledger, AppProperties props) {
        this.router = router;
        this.cache = cache;
        this.redactor = redactor;
        this.outputGuard = outputGuard;
        this.templates = templates;
        this.gateway = gateway;
        this.tools = tools;
        this.prefetch = prefetch;
        this.systemPrompt = systemPrompt;
        this.conversations = conversations;
        this.ledger = ledger;
        this.props = props;
    }

    @PreDestroy
    void shutdown() {
        toolPool.shutdown();
    }

    /** Mutable per-turn accounting, reported to the client as metadata. */
    private static final class Turn {
        final long startNanos = System.nanoTime();
        long firstTokenNanos;
        Route route = Route.FAST;
        String reason = "";
        String model = "none";
        long inputTokens;
        long outputTokens;
        long cacheReadTokens;
        long cacheWriteTokens;
        double costUsd;
        int piiRedactions;
        boolean fromCache;
        boolean cacheable = true;
        final Set<String> toolsUsed = new LinkedHashSet<>();
        final List<String> prefetched = new ArrayList<>();
    }

    public void handle(String sessionId, String userId, String message, AgentEvents events) {
        Turn turn = new Turn();
        PiiRedactor.Result redacted = redactor.redact(sessionId, message);
        turn.piiRedactions = redacted.redactions();
        String question = redacted.text();

        if (conversations.tokensUsed(sessionId) > props.budget().maxTokensPerSession()) {
            turn.reason = "session token cap reached";
            finish(sessionId, userId, question, HANDOFF, turn, events);
            return;
        }

        Optional<String> cached = cache.get(userId, question);
        if (cached.isPresent()) {
            turn.fromCache = true;
            turn.reason = "response cache hit";
            finish(sessionId, userId, question, cached.get(), turn, events);
            return;
        }

        IntentRouter.Decision decision = router.route(question);
        turn.route = decision.route();
        turn.reason = decision.reason();

        if (decision.route() == Route.TEMPLATE) {
            Optional<String> answer = templates.orderStatus(userId, decision.orderId().orElseThrow());
            if (answer.isPresent()) {
                finish(sessionId, userId, question, answer.get(), turn, events);
                return;
            }
            turn.route = Route.FAST;
            turn.reason = "template could not answer; using model";
        }

        String answer;
        try {
            turn.route = gateway.effectiveRoute(turn.route);
            answer = runAgent(sessionId, userId, question, decision.orderId(), turn, events);
        } catch (BudgetExceededException e) {
            log.warn(e.getMessage());
            turn.reason = "budget exceeded";
            answer = BUDGET_MESSAGE;
        }
        finish(sessionId, userId, question, answer, turn, events);
    }

    private String runAgent(String sessionId, String userId, String question, Optional<String> orderId,
                            Turn turn, AgentEvents events) {
        String model = gateway.modelFor(turn.route);
        turn.model = model;
        OutputConfig.Effort effort = turn.route == Route.SMART ? OutputConfig.Effort.MEDIUM : OutputConfig.Effort.LOW;

        // Within a turn the message list is append-only: earlier assistant content (including
        // thinking blocks) is passed back unchanged, as the API requires.
        List<MessageParam> messages = new ArrayList<>(conversations.history(sessionId));
        messages.add(MessageParam.builder().role(MessageParam.Role.USER).content(question).build());
        ledger.recordLlmInput(question);
        addPrefetchedResults(messages, question, orderId, sessionId, userId, turn, events);

        for (int round = 0; ; round++) {
            MessageCreateParams.Builder params = requestFor(model, gateway.maxTokensFor(turn.route), effort, messages);

            StreamingGuard draft = new StreamingGuard(outputGuard, sessionId, text -> {
                if (turn.firstTokenNanos == 0) {
                    turn.firstTokenNanos = System.nanoTime();
                }
                events.delta(text);
            });
            Message response = gateway.stream(params, model, draft::accept);
            account(turn, model, response);
            StopReason stop = response.stopReason().orElse(StopReason.END_TURN);

            if (!stop.equals(StopReason.END_TURN) && !stop.equals(StopReason.MAX_TOKENS)) {
                // Text before a tool call is a preamble, and a refusal is replaced by the handoff.
                if (draft.discard()) {
                    events.resetDraft();
                    turn.firstTokenNanos = 0;
                }
            } else {
                draft.flush();
            }

            if (stop.equals(StopReason.TOOL_USE)) {
                if (round >= props.agent().maxToolRounds()) {
                    turn.reason = "tool round limit reached";
                    turn.cacheable = false;
                    return HANDOFF;
                }
                messages.add(response.toParam());
                messages.add(MessageParam.builder()
                        .role(MessageParam.Role.USER)
                        .contentOfBlockParams(runTools(toolCalls(response), sessionId, userId, turn, events))
                        .build());
                continue;
            }
            if (stop.equals(StopReason.REFUSAL)) {
                turn.reason = "model declined";
                turn.cacheable = false;
                return HANDOFF;
            }
            String text = response.content().stream()
                    .flatMap(b -> b.text().stream())
                    .map(t -> t.text())
                    .collect(Collectors.joining("\n"))
                    .trim();
            return text.isEmpty() ? HANDOFF : text;
        }
    }

    /** Runs every tool call from one response in parallel and returns all results in one message. */
    /** Same system prompt and tools on every request, so the prompt prefix stays cacheable. */
    private MessageCreateParams.Builder requestFor(String model, long maxTokens, OutputConfig.Effort effort,
                                                   List<MessageParam> messages) {
        MessageCreateParams.Builder params = MessageCreateParams.builder()
                .model(model)
                .maxTokens(maxTokens)
                .systemOfTextBlockParams(List.of(systemPrompt.cachedBlock()))
                .outputConfig(OutputConfig.builder().effort(effort).build())
                .messages(messages);
        for (Tool t : tools.definitions()) {
            params.addTool(t);
        }
        return params;
    }

    /**
     * Pays the one-time costs (JIT, TLS to the model API and the legacy APIs, prompt-cache write)
     * before real traffic arrives. One tiny fast-model request with the real prefix; nothing is
     * shown to a user or stored in a conversation.
     */
    public void warmUp() {
        tools.warmUp();
        List<MessageParam> ping = List.of(MessageParam.builder()
                .role(MessageParam.Role.USER).content("Reply with the single word OK.").build());
        String model = gateway.modelFor(Route.FAST);
        gateway.stream(requestFor(model, 16, OutputConfig.Effort.LOW, ping), model, text -> {});
    }

    /**
     * Runs the read-only calls the question obviously needs before the first model call and adds
     * them as a tool-use exchange, so the model can answer in its first round. The system prompt
     * and tool definitions are untouched, so the cached prompt prefix still applies.
     */
    private void addPrefetchedResults(List<MessageParam> messages, String question, Optional<String> orderId,
                                      String sessionId, String userId, Turn turn, AgentEvents events) {
        List<PrefetchPlanner.PlannedCall> planned = prefetch.plan(question, orderId);
        if (planned.isEmpty()) {
            return;
        }
        List<ToolUseBlock> calls = new ArrayList<>();
        for (int i = 0; i < planned.size(); i++) {
            calls.add(ToolUseBlock.builder()
                    .id("toolu_prefetch_" + (i + 1))
                    .name(planned.get(i).tool())
                    .input(JsonValue.from(planned.get(i).input()))
                    .caller(DirectCaller.builder().build())
                    .build());
        }
        messages.add(MessageParam.builder()
                .role(MessageParam.Role.ASSISTANT)
                .contentOfBlockParams(calls.stream().map(c -> ContentBlockParam.ofToolUse(c.toParam())).toList())
                .build());
        messages.add(MessageParam.builder()
                .role(MessageParam.Role.USER)
                .contentOfBlockParams(runTools(calls, sessionId, userId, turn, events))
                .build());
        calls.forEach(c -> turn.prefetched.add(c.name()));
    }

    private static List<ToolUseBlock> toolCalls(Message response) {
        return response.content().stream().flatMap(b -> b.toolUse().stream()).toList();
    }

    /** Runs tool calls in parallel and returns all results for one message. */
    private List<ContentBlockParam> runTools(List<ToolUseBlock> calls, String sessionId, String userId, Turn turn, AgentEvents events) {
        calls.stream().map(c -> CommerceTools.statusMessage(c.name())).distinct().forEach(events::status);

        List<CompletableFuture<CommerceTools.Outcome>> futures = calls.stream()
                .map(c -> CompletableFuture.supplyAsync(() -> tools.execute(c, userId, sessionId), toolPool))
                .toList();

        List<ContentBlockParam> results = new ArrayList<>();
        for (int i = 0; i < calls.size(); i++) {
            ToolUseBlock call = calls.get(i);
            CommerceTools.Outcome outcome = futures.get(i).join();
            turn.toolsUsed.add(call.name());
            if (call.name().equals("cancel_order") || call.name().equals("create_payment_link")) {
                turn.cacheable = false;
            }
            outcome.pendingAction().ifPresent(a -> events.actionRequired(a.id(), a.type(), a.orderId(), a.summary()));
            ledger.recordLlmInput(outcome.content());
            results.add(ContentBlockParam.ofToolResult(ToolResultBlockParam.builder()
                    .toolUseId(call.id())
                    .content(outcome.content())
                    .isError(outcome.isError())
                    .build()));
        }
        return results;
    }

    private void account(Turn turn, String model, Message response) {
        var u = response.usage();
        turn.inputTokens += u.inputTokens();
        turn.outputTokens += u.outputTokens();
        turn.cacheReadTokens += u.cacheReadInputTokens().orElse(0L);
        turn.cacheWriteTokens += u.cacheCreationInputTokens().orElse(0L);
        turn.costUsd += ModelPricing.costUsd(model, u);
    }

    private void finish(String sessionId, String userId, String question, String modelText, Turn turn, AgentEvents events) {
        OutputGuard.Result guarded = outputGuard.finalizeForUser(sessionId, modelText);
        String storedAnswer = guarded.leakBlocked() ? redactor.mask(modelText) : modelText;
        long latencyMs = (System.nanoTime() - turn.startNanos) / 1_000_000;

        if (!turn.fromCache) {
            long tokens = turn.inputTokens + turn.outputTokens + turn.cacheReadTokens + turn.cacheWriteTokens;
            conversations.append(sessionId, question, storedAnswer, tokens, props.agent().historyTurns());
            if (turn.cacheable && turn.route != Route.TEMPLATE && !storedAnswer.equals(HANDOFF)) {
                cache.put(userId, question, storedAnswer);
            }
        }
        ledger.recordPii(turn.piiRedactions, guarded.leakBlocked());
        ledger.recordRequest(turn.route, latencyMs, turn.fromCache);

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("route", turn.fromCache ? "CACHE" : turn.route.name());
        meta.put("reason", turn.reason);
        meta.put("model", turn.model);
        meta.put("promptVersion", SystemPrompt.VERSION);
        meta.put("latencyMs", latencyMs);
        // Time until the first streamed answer text; equal to latency when nothing was streamed.
        meta.put("firstTokenMs", turn.firstTokenNanos == 0 ? latencyMs : (turn.firstTokenNanos - turn.startNanos) / 1_000_000);
        meta.put("inputTokens", turn.inputTokens);
        meta.put("outputTokens", turn.outputTokens);
        meta.put("cacheReadTokens", turn.cacheReadTokens);
        meta.put("costUsd", Math.round(turn.costUsd * 1_000_000.0) / 1_000_000.0);
        meta.put("tools", List.copyOf(turn.toolsUsed));
        meta.put("prefetched", List.copyOf(turn.prefetched));
        meta.put("piiRedactions", turn.piiRedactions);
        meta.put("outputLeakBlocked", guarded.leakBlocked());
        events.answer(guarded.text(), meta);
    }
}
