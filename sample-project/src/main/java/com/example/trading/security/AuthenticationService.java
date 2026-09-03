package com.example.trading.security;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class AuthenticationService {
    private final Map<String, UserSession> activeSessions = new ConcurrentHashMap<>();

    public UserSession login(String userId, String passwordHash) {
        String token = "SES-" + UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        UserSession session = new UserSession(token, userId, now, now + 3600_000, true);
        activeSessions.put(token, session);
        return session;
    }

    public boolean validateToken(String token) {
        UserSession session = activeSessions.get(token);
        return session != null && session.active() && System.currentTimeMillis() < session.expiryTime();
    }
}
