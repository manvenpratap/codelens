package com.tcs.bancs.AM;

import java.io.Serializable;

/**
 * TCS BaNCS Java 17 Immutable Record: AccountSnapshotRecord
 */
public record AccountSnapshotRecord(String accountNumber, double balance, long snapshotTimestamp) implements Serializable {
}
