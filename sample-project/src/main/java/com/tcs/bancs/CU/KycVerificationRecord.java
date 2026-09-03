package com.tcs.bancs.CU;

import java.io.Serializable;

/**
 * TCS BaNCS Java 17 Immutable Record: KycVerificationRecord
 */
public record KycVerificationRecord(String docId, String status, String officer) implements Serializable {
}
