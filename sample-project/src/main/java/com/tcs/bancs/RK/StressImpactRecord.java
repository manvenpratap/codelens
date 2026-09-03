package com.tcs.bancs.RK;

import java.io.Serializable;

/**
 * TCS BaNCS Java 17 Immutable Record: StressImpactRecord
 */
public record StressImpactRecord(String scenarioId, double lossEstimate) implements Serializable {
}
