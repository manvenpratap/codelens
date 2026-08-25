package com.example.trading.audit;

import com.example.trading.api.ExecutionReport;
import com.example.trading.api.OrderRequest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Regulatory compliance audit journal capturing all order events.
 */
public class AuditLogger {

    private final List<String> auditTrail = new ArrayList<>();

    public synchronized void logOrderAttempt(OrderRequest request) {
        String entry = String.format("[AUDIT-ATTEMPT] ID=%s ACC=%s SYM=%s QTY=%d Px=%.2f TYPE=%s",
            request.orderId(), request.accountId(), request.symbol(), request.quantity(),
            request.limitPrice(), request.orderType());
        auditTrail.add(entry);
    }

    public synchronized void logExecution(ExecutionReport report) {
        String entry = String.format("[AUDIT-EXEC] ID=%s EXEC=%s SYM=%s FILLED=%d Px=%.2f STATUS=%s",
            report.orderId(), report.execId(), report.symbol(), report.filledQuantity(),
            report.averageFillPrice(), report.status());
        auditTrail.add(entry);
    }

    public synchronized void logRejection(String orderId, String reason) {
        String entry = String.format("[AUDIT-REJECT] ID=%s REASON=%s", orderId, reason);
        auditTrail.add(entry);
    }

    public synchronized List<String> getAuditTrail() {
        return Collections.unmodifiableList(auditTrail);
    }
}
