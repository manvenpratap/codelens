package com.tcs.bancs.TR;

import java.util.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Process After Batch (Post-run reconciliation): TRPAPostTradeReconcile
 */
public class TRPAPostTradeReconcile {

    private final TRDGTradeGrabber dataGrabber;
    private final OrderRoutingService service;
    private boolean isExecutionRunning = false;

    public TRPAPostTradeReconcile() {
        this.dataGrabber = new TRDGTradeGrabber();
        this.service = new OrderRoutingService();
    }

    public TRPAPostTradeReconcile(TRDGTradeGrabber dataGrabber, OrderRoutingService service) {
        this.dataGrabber = dataGrabber;
        this.service = service;
    }

    /**
     * Primary batch execution method: TRPAPostTradeReconcileProcess
     */
    public synchronized int TRPAPostTradeReconcileProcess() {
        this.isExecutionRunning = true;
        int processedRecords = 0;
        try {
            AuditTrailService.logAuditEvent("BATCH_START", "TRPAPostTradeReconcile", "PA", "START");
            List<?> records = this.dataGrabber.retrieveAll();
            processedRecords = records != null ? records.size() : 0;
            this.service.executeBatchProcessingCycle("TRPAPostTradeReconcile", processedRecords);
            AuditTrailService.logAuditEvent("BATCH_COMPLETE", "TRPAPostTradeReconcile", "PA", "PROCESSED=" + processedRecords);
        } finally {
            this.isExecutionRunning = false;
        }
        return processedRecords;
    }

    public boolean isRunning() {
        return this.isExecutionRunning;
    }
}
