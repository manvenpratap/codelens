package com.tcs.bancs.TR;

import java.util.*;
import com.tcs.bancs.common.*;
import com.tcs.bancs.CL.*;
import com.tcs.bancs.RK.*;
import com.tcs.bancs.AN.*;

/**
 * TCS BaNCS Core Domain Service: OrderValidationService
 * Implements transaction methods (ET/BT), tasks (TO/TC), and domain calculations.
 */
public class OrderValidationService {

    private final TRDGOrderBookGetter dataGetter;

    public OrderValidationService() {
        this.dataGetter = new TRDGOrderBookGetter();
    }

    public OrderValidationService(TRDGOrderBookGetter dataGetter) {
        this.dataGetter = dataGetter;
    }

    public boolean validateTransactionPreconditions(String contextId) {
        if (contextId == null || contextId.isEmpty()) {
            return false;
        }
        return this.dataGetter.exists(contextId);
    }

    public double calculateInterestOrCharges(double baseAmount, double rate) {
        if (baseAmount <= 0.0 || rate < 0.0) {
            return 0.0;
        }
        return (baseAmount * rate) / 100.0;
    }

    public void executeBatchProcessingCycle(String batchName, int recordCount) {
        AuditTrailService.logAuditEvent("SERVICE_BATCH_CYCLE", "OrderValidationService", batchName, "RECORDS=" + recordCount);
        TelemetryRecorder.recordMetric("OrderValidationService." + batchName + ".records", (double) recordCount);
    }

    public TradeExecution inspectAndReconcile(String entityId) {
        TradeExecution entity = this.dataGetter.fetchTradeExecutionById(entityId);
        if (entity != null) {
            entity.Modify("RECONCILED");
        }
        return entity;
    }

    /**
     * TCS BaNCS Own Task delegate: TRTOUpdatePortfolioPosition
     */
    public boolean TRTOUpdatePortfolioPosition(String correlationId) {
        AuditTrailService.logAuditEvent("TASK_OWN", "TRTOUpdatePortfolioPosition", correlationId, "SECONDARY_SERVICE");
        return this.dataGrabber != null && this.dataGetter.exists(correlationId);
    }

    /**
     * TCS BaNCS Common Task delegate: TRTCNotifyChannel
     */
    public void TRTCNotifyChannel(String operation, String correlationId) {
        AuditTrailService.logAuditEvent("TASK_COMMON", "TRTCNotifyChannel", correlationId, operation);
    }
}
