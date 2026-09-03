package com.example.trading.config;

public class RateLimiterConfig {
    private final int permitsPerSecond;

    public RateLimiterConfig(int permitsPerSecond) {
        this.permitsPerSecond = permitsPerSecond;
    }

    public int getPermitsPerSecond() { return permitsPerSecond; }
}
