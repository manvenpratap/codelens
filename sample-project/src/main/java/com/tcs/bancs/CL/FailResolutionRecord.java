package com.tcs.bancs.CL;

import java.io.Serializable;

/**
 * TCS BaNCS Java 17 Immutable Record: FailResolutionRecord
 */
public record FailResolutionRecord(String failId, String resolution, long timestamp) implements Serializable {
}
