package com.example.trading.security;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class RoleManager {
    private final Map<String, Set<String>> userRoles = new ConcurrentHashMap<>();

    public void assignRoles(String userId, Set<String> roles) {
        userRoles.put(userId, roles);
    }

    public Set<String> getRoles(String userId) {
        return userRoles.getOrDefault(userId, Set.of("TRADER"));
    }
}
