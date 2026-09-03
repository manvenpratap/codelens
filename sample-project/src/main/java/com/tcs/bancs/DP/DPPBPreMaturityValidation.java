package com.tcs.bancs.DP;

import java.util.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Process Before Batch (Pre-run validation): DPPBPreMaturityValidation
 */
public class DPPBPreMaturityValidation {

    private final DPDGDepositGrabber dataGrabber;
    private final DepositBookingService service;
    private boolean isExecutionRunning = false;

    public DPPBPreMaturityValidation() {
        this.dataGrabber = new DPDGDepositGrabber();
        this.service = new DepositBookingService();
    }

    public DPPBPreMaturityValidation(DPDGDepositGrabber dataGrabber, DepositBookingService service) {
        this.dataGrabber = dataGrabber;
        this.service = service;
    }

    /**
     * Primary batch execution method: DPPBPreMaturityValidationProcess
     */
    public synchronized int DPPBPreMaturityValidationProcess() {
        this.isExecutionRunning = true;
        int processedRecords = 0;
        try {
            AuditTrailService.logAuditEvent("BATCH_START", "DPPBPreMaturityValidation", "PB", "START");
            List<?> records = this.dataGrabber.retrieveAll();
            processedRecords = records != null ? records.size() : 0;
            this.service.executeBatchProcessingCycle("DPPBPreMaturityValidation", processedRecords);
            AuditTrailService.logAuditEvent("BATCH_COMPLETE", "DPPBPreMaturityValidation", "PB", "PROCESSED=" + processedRecords);
        } finally {
            this.isExecutionRunning = false;
        }
        return processedRecords;
    }

    public boolean isRunning() {
        return this.isExecutionRunning;
    }
}
