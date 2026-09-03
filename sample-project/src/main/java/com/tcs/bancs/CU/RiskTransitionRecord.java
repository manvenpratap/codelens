package com.tcs.bancs.CU;

import java.io.Serializable;

/**
 * TCS BaNCS Java 17 Immutable Record: RiskTransitionRecord
 */
public record RiskTransitionRecord(String customerId, String oldRating, String newRating) implements Serializable {
}
