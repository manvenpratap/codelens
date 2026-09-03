package com.tcs.bancs.common;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.DoubleAdder;

/**
 * Telemetry and latency metric recorder.
 */
public class TelemetryRecorder {
    private static final Map<String, DoubleAdder> METRICS = new ConcurrentHashMap<>();

    public static void recordMetric(String metricName, double value) {
        METRICS.computeIfAbsent(metricName, k -> new DoubleAdder()).add(value);
    }

    public static double getMetric(String metricName) {
        DoubleAdder adder = METRICS.get(metricName);
        return adder != null ? adder.sum() : 0.0;
    }
}
