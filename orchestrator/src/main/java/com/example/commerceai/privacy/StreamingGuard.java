package com.example.commerceai.privacy;

import java.util.function.Consumer;

/**
 * Applies the output guard to streamed model text. Text is held back until a safe cut point, so a
 * PII value or placeholder split across deltas is always checked whole, never emitted half-masked.
 *
 * A safe cut is whitespace with no digit, '(', ')', '+' or '-' on either side. Emails, PAN and
 * placeholders never contain whitespace; card and phone numbers only contain it between those
 * characters. So no PII pattern can span a safe cut, and guarding each chunk gives the same
 * result as guarding the whole text.
 */
public final class StreamingGuard {

    private final OutputGuard guard;
    private final String sessionId;
    private final Consumer<String> sink;
    private final StringBuilder pending = new StringBuilder();
    private boolean emitted;

    public StreamingGuard(OutputGuard guard, String sessionId, Consumer<String> sink) {
        this.guard = guard;
        this.sessionId = sessionId;
        this.sink = sink;
    }

    public void accept(String delta) {
        pending.append(delta);
        int cut = lastSafeCut(pending);
        if (cut > 0) {
            emit(pending.substring(0, cut));
            pending.delete(0, cut);
        }
    }

    /** Emits whatever is still held back. Call when the model has finished writing. */
    public void flush() {
        if (!pending.isEmpty()) {
            emit(pending.toString());
            pending.setLength(0);
        }
    }

    /** Drops held-back text and reports whether anything had already reached the client. */
    public boolean discard() {
        pending.setLength(0);
        boolean any = emitted;
        emitted = false;
        return any;
    }

    private void emit(String chunk) {
        emitted = true;
        sink.accept(guard.finalizeForUser(sessionId, chunk).text());
    }

    /** Index just after the last safe whitespace, or 0 if there is none yet. */
    static int lastSafeCut(CharSequence s) {
        for (int i = s.length() - 2; i > 0; i--) {
            if (Character.isWhitespace(s.charAt(i)) && !joinsPii(s.charAt(i - 1)) && !joinsPii(s.charAt(i + 1))) {
                return i + 1;
            }
        }
        return 0;
    }

    private static boolean joinsPii(char c) {
        return Character.isDigit(c) || "()+-".indexOf(c) >= 0;
    }
}
