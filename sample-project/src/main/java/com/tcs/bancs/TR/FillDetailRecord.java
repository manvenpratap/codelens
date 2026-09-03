package com.tcs.bancs.TR;

import java.io.Serializable;

/**
 * TCS BaNCS Java 17 Immutable Record: FillDetailRecord
 */
public record FillDetailRecord(String fillId, int fillQty, double fillPrice, long timestamp) implements Serializable {
}
