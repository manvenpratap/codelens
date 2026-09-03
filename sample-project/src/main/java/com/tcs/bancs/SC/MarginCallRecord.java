package com.tcs.bancs.SC;

import java.io.Serializable;

/**
 * TCS BaNCS Java 17 Immutable Record: MarginCallRecord
 */
public record MarginCallRecord(String callId, String facilityId, double deficit) implements Serializable {
}
