package com.tcs.bancs.AM;

import java.io.Serializable;

/**
 * TCS BaNCS Java 17 Immutable Record: InterestPostingRecord
 */
public record InterestPostingRecord(String accountNumber, double accruedInterest, String period) implements Serializable {
}
