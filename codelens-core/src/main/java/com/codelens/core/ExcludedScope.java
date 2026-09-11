package com.codelens.core;

import java.time.Instant;

/**
 * Represents a class or package that has been removed/excluded from the analysis scope.
 */
public record ExcludedScope(
    String id,
    String entityType,  // "CLASS" or "PACKAGE"
    String fqn,
    String simpleName,
    String sourceFile,
    long excludedAt
) {
    public ExcludedScope {
        if (id == null || id.isBlank()) {
            id = (entityType != null ? entityType : "ENTITY") + ":" + fqn;
        }
        if (excludedAt <= 0) {
            excludedAt = Instant.now().toEpochMilli();
        }
    }
}
