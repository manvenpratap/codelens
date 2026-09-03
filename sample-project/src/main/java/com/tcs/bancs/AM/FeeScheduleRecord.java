package com.tcs.bancs.AM;

import java.io.Serializable;

/**
 * TCS BaNCS Java 17 Immutable Record: FeeScheduleRecord
 */
public record FeeScheduleRecord(String scheduleId, String accountType, double feeAmount) implements Serializable {
}
