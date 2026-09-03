package com.tcs.bancs.AN;

import java.io.Serializable;

/**
 * TCS BaNCS Java 17 Immutable Record: PnLBucketRecord
 */
public record PnLBucketRecord(String category, double amount) implements Serializable {
}
