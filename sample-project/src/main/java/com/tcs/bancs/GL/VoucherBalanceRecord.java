package com.tcs.bancs.GL;

import java.io.Serializable;

/**
 * TCS BaNCS Java 17 Immutable Record: VoucherBalanceRecord
 */
public record VoucherBalanceRecord(String voucherId, double dr, double cr, boolean balanced) implements Serializable {
}
