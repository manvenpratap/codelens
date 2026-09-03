package com.tcs.bancs.RK;

import java.io.Serializable;

/**
 * TCS BaNCS Java 17 Immutable Record: AmlScoreRecord
 */
public record AmlScoreRecord(String txnId, double score, String verdict) implements Serializable {
}
