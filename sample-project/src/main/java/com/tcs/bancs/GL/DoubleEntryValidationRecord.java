package com.tcs.bancs.GL;

import java.io.Serializable;

/**
 * TCS BaNCS Java 17 Immutable Record: DoubleEntryValidationRecord
 */
public record DoubleEntryValidationRecord(String voucherId, boolean valid, String msg) implements Serializable {
}
