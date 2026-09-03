package com.example.trading.reporting;

import java.util.List;

public class AuditTrailExporter {
    public String exportAuditTrail(List<String> entries) {
        return String.join("\n", entries);
    }
}
