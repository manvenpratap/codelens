package com.tcs.bancs.PM;

import java.io.Serializable;

/**
 * TCS BaNCS Java 17 Immutable Record: MandateExecutionRecord
 */
public record MandateExecutionRecord(String mandateId, double amt, boolean success) implements Serializable {
}
