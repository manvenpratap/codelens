package com.tcs.bancs.common;

/**
 * Entity not found exception.
 */
public class EntityNotFoundException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public EntityNotFoundException(String entityName, String id) {
        super("Entity " + entityName + " with key " + id + " was not found");
    }
}
