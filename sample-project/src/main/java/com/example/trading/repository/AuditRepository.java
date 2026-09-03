package com.example.trading.repository;

import java.util.List;
import java.util.ArrayList;

public class AuditRepository {
    private final List<String> auditTrail = new ArrayList<>();

    public synchronized void append(String logMessage) { auditTrail.add(logMessage); }
    public synchronized int logCount() { return auditTrail.size(); }
}
