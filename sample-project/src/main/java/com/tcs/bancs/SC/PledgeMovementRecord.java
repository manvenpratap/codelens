package com.tcs.bancs.SC;

import java.io.Serializable;

/**
 * TCS BaNCS Java 17 Immutable Record: PledgeMovementRecord
 */
public record PledgeMovementRecord(String pledgeId, double amount, long time) implements Serializable {
}
