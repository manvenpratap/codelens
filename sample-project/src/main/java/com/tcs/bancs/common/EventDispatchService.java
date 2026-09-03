package com.tcs.bancs.common;

import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * In-memory domain event dispatcher.
 */
public class EventDispatchService {
    private static final Queue<DomainEventRecord> EVENTS = new ConcurrentLinkedQueue<>();

    public static void dispatchDomainEvent(String eventType, String entityId, String payload) {
        EVENTS.offer(new DomainEventRecord(UUID.randomUUID().toString(), eventType, entityId, System.currentTimeMillis()));
    }

    public static int getEventCount() {
        return EVENTS.size();
    }
}
