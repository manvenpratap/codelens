package com.tcs.bancs.TR;

import java.util.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Core Domain Service: OrderRoutingService
 * Implements business calculation logic, validations, and domain rules.
 */
public class OrderRoutingService {

    private final TRDGTradeGrabber dataGrabber;

    public OrderRoutingService() {
        this.dataGrabber = new TRDGTradeGrabber();
    }

    public OrderRoutingService(TRDGTradeGrabber dataGrabber) {
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
        AuditTrailService.logAuditEvent("SERVICE_BATCH_CYCLE", "OrderRoutingService", batchName, "RECORDS=" + recordCount);
        TelemetryRecorder.recordMetric("OrderRoutingService." + batchName + ".records", (double) recordCount);
    }

    public OrderEntity inspectAndReconcile(String entityId) {
        OrderEntity entity = this.dataGrabber.fetchOrderEntityById(entityId);
        if (entity != null) {
            entity.Modify("RECONCILED");
        }
        return entity;
    }
}
