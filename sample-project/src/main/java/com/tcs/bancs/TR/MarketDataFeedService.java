package com.tcs.bancs.TR;

import java.util.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Core Domain Service: MarketDataFeedService
 * Implements business calculation logic, validations, and domain rules.
 */
public class MarketDataFeedService {

    private final TRDGOrderBookGrabber dataGrabber;

    public MarketDataFeedService() {
        this.dataGrabber = new TRDGOrderBookGrabber();
    }

    public MarketDataFeedService(TRDGOrderBookGrabber dataGrabber) {
        this.dataGrabber = dataGrabber;
    }

    public boolean validateTransactionPreconditions(String contextId) {
        if (contextId == null || contextId.isEmpty()) {
            return false;
        }
        return this.dataGrabber.exists(contextId);
    }

    public double calculateInterestOrCharges(double baseAmount, double rate) {
        if (baseAmount <= 0.0 || rate < 0.0) {
            return 0.0;
        }
        return (baseAmount * rate) / 100.0;
    }

    public void executeBatchProcessingCycle(String batchName, int recordCount) {
        AuditTrailService.logAuditEvent("SERVICE_BATCH_CYCLE", "MarketDataFeedService", batchName, "RECORDS=" + recordCount);
        TelemetryRecorder.recordMetric("MarketDataFeedService." + batchName + ".records", (double) recordCount);
    }

    public TradeExecution inspectAndReconcile(String entityId) {
        TradeExecution entity = this.dataGrabber.fetchTradeExecutionById(entityId);
        if (entity != null) {
            entity.Modify("RECONCILED");
        }
        return entity;
    }
}
