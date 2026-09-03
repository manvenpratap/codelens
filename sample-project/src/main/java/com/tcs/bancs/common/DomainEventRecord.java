package com.tcs.bancs.common;

import java.io.Serializable;

public record DomainEventRecord(String eventId, String eventType, String entityId, long timestamp) implements Serializable {
}
