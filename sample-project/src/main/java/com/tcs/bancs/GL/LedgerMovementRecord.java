package com.tcs.bancs.GL;

import java.io.Serializable;

/**
 * TCS BaNCS Java 17 Immutable Record: LedgerMovementRecord
 */
public record LedgerMovementRecord(String glCode, double movement, String side) implements Serializable {
}
