package com.tcs.bancs.AN;

import java.io.Serializable;

/**
 * TCS BaNCS Java 17 Immutable Record: CapitalRatioRecord
 */
public record CapitalRatioRecord(String tier, double ratioPct) implements Serializable {
}
