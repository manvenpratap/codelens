package com.tcs.bancs.SC;

import java.io.Serializable;

/**
 * TCS BaNCS Java 17 Immutable Record: LtvCovenantRecord
 */
public record LtvCovenantRecord(String facilityId, double ltvPct, boolean inBreach) implements Serializable {
}
