package com.example.trading.security;

public record UserSession(
    String sessionId,
    String userId,
    long loginTime,
    long expiryTime,
    boolean active
) {}
