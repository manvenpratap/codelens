package com.tcs.bancs.DP;

import java.io.Serializable;

/**
 * TCS BaNCS Java 17 Immutable Record: TdsDeductionRecord
 */
public record TdsDeductionRecord(String depositId, double taxAmount, long time) implements Serializable {
}
