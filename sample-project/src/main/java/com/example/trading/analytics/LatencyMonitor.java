package com.example.trading.analytics;

import java.util.concurrent.atomic.AtomicLong;

public class LatencyMonitor {
    private final AtomicLong totalLatencyMicros = new AtomicLong(0);
    private final AtomicLong sampledOrders = new AtomicLong(0);

    public void recordLatency(long micros) {
        totalLatencyMicros.addAndGet(micros);
        sampledOrders.incrementAndGet();
    }

    public double getAverageLatencyMicros() {
        long count = sampledOrders.get();
        return count == 0 ? 0.0 : (double) totalLatencyMicros.get() / count;
    }
}
