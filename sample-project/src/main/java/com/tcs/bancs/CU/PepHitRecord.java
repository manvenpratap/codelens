package com.tcs.bancs.CU;

import java.io.Serializable;

/**
 * TCS BaNCS Java 17 Immutable Record: PepHitRecord
 */
public record PepHitRecord(String customerId, String matchDetails, boolean cleared) implements Serializable {
}
