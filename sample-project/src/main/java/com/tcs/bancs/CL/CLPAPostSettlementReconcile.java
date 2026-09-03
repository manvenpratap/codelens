package com.tcs.bancs.CL;

import java.util.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Process After Batch (Post-run reconciliation): CLPAPostSettlementReconcile
 */
public class CLPAPostSettlementReconcile {

    private final CLDGSettlementGrabber dataGrabber;
    private final ClearingHouseGatewayService service;
    private boolean isExecutionRunning = false;

    public CLPAPostSettlementReconcile() {
        this.dataGrabber = new CLDGSettlementGrabber();
        this.service = new ClearingHouseGatewayService();
    }

    public CLPAPostSettlementReconcile(CLDGSettlementGrabber dataGrabber, ClearingHouseGatewayService service) {
        this.dataGrabber = dataGrabber;
        this.service = service;
    }

    /**
     * Primary batch execution method: CLPAPostSettlementReconcileProcess
     */
    public synchronized int CLPAPostSettlementReconcileProcess() {
        this.isExecutionRunning = true;
        int processedRecords = 0;
        try {
            AuditTrailService.logAuditEvent("BATCH_START", "CLPAPostSettlementReconcile", "PA", "START");
            List<?> records = this.dataGrabber.retrieveAll();
            processedRecords = records != null ? records.size() : 0;
            this.service.executeBatchProcessingCycle("CLPAPostSettlementReconcile", processedRecords);
            AuditTrailService.logAuditEvent("BATCH_COMPLETE", "CLPAPostSettlementReconcile", "PA", "PROCESSED=" + processedRecords);
        } finally {
            this.isExecutionRunning = false;
        }
        return processedRecords;
    }

    public boolean isRunning() {
        return this.isExecutionRunning;
    }
}
