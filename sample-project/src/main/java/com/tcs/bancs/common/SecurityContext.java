package com.tcs.bancs.common;

/**
 * Security context for BaNCS transaction execution.
 */
public class SecurityContext {
    private static final ThreadLocal<String> CURRENT_USER = ThreadLocal.withInitial(() -> "SYSTEM_USER");
    private static final ThreadLocal<String> CURRENT_BRANCH = ThreadLocal.withInitial(() -> "BR001");
    private static final ThreadLocal<String> CURRENT_TENANT = ThreadLocal.withInitial(() -> "DEFAULT_BANK");

    public static String getCurrentUser() { return CURRENT_USER.get(); }
    public static void setCurrentUser(String user) { CURRENT_USER.set(user); }
    public static String getCurrentBranch() { return CURRENT_BRANCH.get(); }
    public static void setCurrentBranch(String branch) { CURRENT_BRANCH.set(branch); }
    public static String getCurrentTenant() { return CURRENT_TENANT.get(); }
    public static void setCurrentTenant(String tenant) { CURRENT_TENANT.set(tenant); }
    public static void clear() { CURRENT_USER.remove(); CURRENT_BRANCH.remove(); CURRENT_TENANT.remove(); }
}
