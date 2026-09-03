package com.example.trading.security;

import java.util.Set;

public class AuthorizationFilter {
    public boolean checkPermission(Set<String> userRoles, String requiredPermission) {
        if (userRoles.contains("ADMIN")) return true;
        return userRoles.contains(requiredPermission);
    }
}
