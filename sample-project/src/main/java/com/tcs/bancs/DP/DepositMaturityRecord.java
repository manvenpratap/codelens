package com.tcs.bancs.DP;

import java.io.Serializable;

/**
 * TCS BaNCS Java 17 Immutable Record: DepositMaturityRecord
 */
public record DepositMaturityRecord(String depositId, double finalAmount, String date) implements Serializable {
}
