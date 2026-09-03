package com.tcs.bancs.TR;

import java.io.Serializable;

/**
 * TCS BaNCS Java 17 Immutable Record: ExecutionSnapshotRecord
 */
public record ExecutionSnapshotRecord(String execId, String orderId, double price, int qty) implements Serializable {
}
