package com.tcs.bancs.DP;

import java.io.Serializable;

/**
 * TCS BaNCS Java 17 Immutable Record: InterestAccrualSnapshot
 */
public record InterestAccrualSnapshot(String depositId, double accrued, String period) implements Serializable {
}
