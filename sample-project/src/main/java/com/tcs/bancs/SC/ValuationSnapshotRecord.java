package com.tcs.bancs.SC;

import java.io.Serializable;

/**
 * TCS BaNCS Java 17 Immutable Record: ValuationSnapshotRecord
 */
public record ValuationSnapshotRecord(String collateralId, double val, String date) implements Serializable {
}
