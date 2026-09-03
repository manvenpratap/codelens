package com.tcs.bancs.TR;

import java.io.Serializable;

/**
 * TCS BaNCS Java 17 Immutable Record: OrderAuditEventRecord
 */
public record OrderAuditEventRecord(String orderId, String action, String user) implements Serializable {
}
