package com.example.trading.security;

import java.util.HashSet;
import java.util.Set;

public class AccessControlList {
    private final Set<String> allowedIpAddresses = new HashSet<>();

    public void addAllowedIp(String ip) {
        allowedIpAddresses.add(ip);
    }

    public boolean isAllowed(String ip) {
        return allowedIpAddresses.isEmpty() || allowedIpAddresses.contains(ip);
    }
}
