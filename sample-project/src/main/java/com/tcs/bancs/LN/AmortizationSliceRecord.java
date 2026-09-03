package com.tcs.bancs.LN;

import java.io.Serializable;

/**
 * TCS BaNCS Java 17 Immutable Record: AmortizationSliceRecord
 */
public record AmortizationSliceRecord(int month, double principal, double interest, double balance) implements Serializable {
}
