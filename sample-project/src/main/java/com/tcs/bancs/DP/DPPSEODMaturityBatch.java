package com.tcs.bancs.DP;

import java.util.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Batch Processor (EOD / Periodic Execution): DPPSEODMaturityBatch
 */
public class DPPSEODMaturityBatch {

    private final DPDGDepositGrabber dataGrabber;
    private final DepositBookingService service;
    private boolean isExecutionRunning = false;

    public DPPSEODMaturityBatch() {
        this.dataGrabber = new DPDGDepositGrabber();
        this.service = new DepositBookingService();
    }

    public DPPSEODMaturityBatch(DPDGDepositGrabber dataGrabber, DepositBookingService service) {
        this.dataGrabber = dataGrabber;
        this.service = service;
    }

    /**
     * Primary batch execution method: DPPSEODMaturityBatchProcess
     */
    public synchronized int DPPSEODMaturityBatchProcess() {
        this.isExecutionRunning = true;
        int processedRecords = 0;
        try {
            AuditTrailService.logAuditEvent("BATCH_START", "DPPSEODMaturityBatch", "PS", "START");
            List<?> records = this.dataGrabber.retrieveAll();
            processedRecords = records != null ? records.size() : 0;
            this.service.executeBatchProcessingCycle("DPPSEODMaturityBatch", processedRecords);
            AuditTrailService.logAuditEvent("BATCH_COMPLETE", "DPPSEODMaturityBatch", "PS", "PROCESSED=" + processedRecords);
        } finally {
            this.isExecutionRunning = false;
        }
        return processedRecords;
    }

    public boolean isRunning() {
        return this.isExecutionRunning;
    }
}
