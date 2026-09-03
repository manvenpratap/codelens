package com.example.trading.config;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class FeatureFlagManager {
    private final Map<String, Boolean> flags = new ConcurrentHashMap<>();

    public void setFlag(String flagName, boolean enabled) {
        flags.put(flagName, enabled);
    }

    public boolean isEnabled(String flagName) {
        return flags.getOrDefault(flagName, false);
    }
}
