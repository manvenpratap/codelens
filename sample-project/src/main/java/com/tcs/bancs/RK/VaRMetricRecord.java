package com.tcs.bancs.RK;

import java.io.Serializable;

/**
 * TCS BaNCS Java 17 Immutable Record: VaRMetricRecord
 */
public record VaRMetricRecord(String portfolioId, double var99, double var95) implements Serializable {
}
