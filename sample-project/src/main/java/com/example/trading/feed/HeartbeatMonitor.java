package com.example.trading.feed;

public class HeartbeatMonitor {
    private long lastHeartbeatTime = System.currentTimeMillis();

    public void ping() { lastHeartbeatTime = System.currentTimeMillis(); }
    public boolean isAlive(long timeoutMs) {
        return (System.currentTimeMillis() - lastHeartbeatTime) < timeoutMs;
    }
}
