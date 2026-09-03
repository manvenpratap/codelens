package com.tcs.bancs.common;

import java.io.Serializable;

public record MetricSnapshotRecord(String metricName, double value, long timestamp) implements Serializable {
}
