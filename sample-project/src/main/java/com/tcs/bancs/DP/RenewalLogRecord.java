package com.tcs.bancs.DP;

import java.io.Serializable;

/**
 * TCS BaNCS Java 17 Immutable Record: RenewalLogRecord
 */
public record RenewalLogRecord(String depositId, int extraDays, double newRate) implements Serializable {
}
