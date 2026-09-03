package com.tcs.bancs.PM;

import java.io.Serializable;

/**
 * TCS BaNCS Java 17 Immutable Record: ClearingSettlementAckRecord
 */
public record ClearingSettlementAckRecord(String paymentId, String clearingRef) implements Serializable {
}
