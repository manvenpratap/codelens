package com.tcs.bancs.TR;

import java.util.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Process Before Batch (Pre-run validation): TRPBPreMarkValidation
 */
public class TRPBPreMarkValidation {

    private final TRDGTradeGrabber dataGrabber;
    private final OrderRoutingService service;
    private boolean isExecutionRunning = false;

    public TRPBPreMarkValidation() {
        this.dataGrabber = new TRDGTradeGrabber();
        this.service = new OrderRoutingService();
    }

    public TRPBPreMarkValidation(TRDGTradeGrabber dataGrabber, OrderRoutingService service) {
        this.dataGrabber = dataGrabber;
        this.service = service;
    }

    /**
     * Primary batch execution method: TRPBPreMarkValidationProcess
     */
    public synchronized int TRPBPreMarkValidationProcess() {
        this.isExecutionRunning = true;
        int processedRecords = 0;
        try {
            AuditTrailService.logAuditEvent("BATCH_START", "TRPBPreMarkValidation", "PB", "START");
            List<?> records = this.dataGrabber.retrieveAll();
            processedRecords = records != null ? records.size() : 0;
            this.service.executeBatchProcessingCycle("TRPBPreMarkValidation", processedRecords);
            AuditTrailService.logAuditEvent("BATCH_COMPLETE", "TRPBPreMarkValidation", "PB", "PROCESSED=" + processedRecords);
        } finally {
            this.isExecutionRunning = false;
        }
        return processedRecords;
    }

    public boolean isRunning() {
        return this.isExecutionRunning;
    }
}
