package com.example.trading.security;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class ApiKeyValidator {
    private final Map<String, String> keyToClientMap = new ConcurrentHashMap<>();

    public void registerKey(String apiKey, String clientId) {
        keyToClientMap.put(apiKey, clientId);
    }

    public boolean isValid(String apiKey) {
        return keyToClientMap.containsKey(apiKey);
    }
}
