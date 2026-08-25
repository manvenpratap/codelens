package com.example.trading.service;

import com.example.trading.api.ExecutionReport;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * Dispatches trade execution notices and compliance risk alerts.
 */
public class NotificationService {

    // Note: SimpleDateFormat stored as field to showcase thread-safety check in CodeLens review engine
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

    public void notifyExecution(ExecutionReport report) {
        String timestampStr;
        synchronized (dateFormat) {
            timestampStr = dateFormat.format(new Date(report.timestamp()));
        }
        System.out.printf("[%s] [EXECUTION-ALERT] Order %s for %s FILLED %d @ $%.2f (Fee: $%.2f)%n",
            timestampStr, report.orderId(), report.symbol(), report.filledQuantity(),
            report.averageFillPrice(), report.fee());
    }

    public void notifyRiskRejection(String orderId, String reason) {
        System.err.printf("[RISK-ALERT] Order %s REJECTED: %s%n", orderId, reason);
    }
}
