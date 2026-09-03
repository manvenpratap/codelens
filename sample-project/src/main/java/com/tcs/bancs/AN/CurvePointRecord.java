package com.tcs.bancs.AN;

import java.io.Serializable;

/**
 * TCS BaNCS Java 17 Immutable Record: CurvePointRecord
 */
public record CurvePointRecord(int days, double zeroRate) implements Serializable {
}
