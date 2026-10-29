package com.example.commerceai.privacy;

import org.springframework.stereotype.Component;

/**
 * Last check before text leaves the system: masks any raw PII the model produced on its own,
 * then restores placeholders for the session owner only.
 */
@Component
public class OutputGuard {

    private final PiiRedactor redactor;
    private final PiiVault vault;

    public OutputGuard(PiiRedactor redactor, PiiVault vault) {
        this.redactor = redactor;
        this.vault = vault;
    }

    public record Result(String text, boolean leakBlocked) {}

    public Result finalizeForUser(String sessionId, String modelText) {
        boolean leak = redactor.containsPii(modelText);
        String safe = leak ? redactor.mask(modelText) : modelText;
        return new Result(vault.restore(sessionId, safe), leak);
    }
}
