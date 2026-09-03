package com.example.trading.config;

public class CacheConfiguration {
    private final int maxEntries;
    private final long ttlSeconds;

    public CacheConfiguration(int maxEntries, long ttlSeconds) {
        this.maxEntries = maxEntries;
        this.ttlSeconds = ttlSeconds;
    }

    public int getMaxEntries() { return maxEntries; }
    public long getTtlSeconds() { return ttlSeconds; }
}
