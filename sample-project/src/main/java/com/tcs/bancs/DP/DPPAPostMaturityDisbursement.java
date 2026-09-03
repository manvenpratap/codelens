package com.tcs.bancs.DP;

import java.util.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Process After Batch (Post-run reconciliation): DPPAPostMaturityDisbursement
 */
public class DPPAPostMaturityDisbursement {

    private final DPDGDepositGrabber dataGrabber;
    private final DepositBookingService service;
    private boolean isExecutionRunning = false;

    public DPPAPostMaturityDisbursement() {
        this.dataGrabber = new DPDGDepositGrabber();
        this.service = new DepositBookingService();
    }

    public DPPAPostMaturityDisbursement(DPDGDepositGrabber dataGrabber, DepositBookingService service) {
        this.dataGrabber = dataGrabber;
        this.service = service;
    }

    /**
     * Primary batch execution method: DPPAPostMaturityDisbursementProcess
     */
    public synchronized int DPPAPostMaturityDisbursementProcess() {
        this.isExecutionRunning = true;
        int processedRecords = 0;
        try {
            AuditTrailService.logAuditEvent("BATCH_START", "DPPAPostMaturityDisbursement", "PA", "START");
            List<?> records = this.dataGrabber.retrieveAll();
            processedRecords = records != null ? records.size() : 0;
            this.service.executeBatchProcessingCycle("DPPAPostMaturityDisbursement", processedRecords);
            AuditTrailService.logAuditEvent("BATCH_COMPLETE", "DPPAPostMaturityDisbursement", "PA", "PROCESSED=" + processedRecords);
        } finally {
            this.isExecutionRunning = false;
        }
        return processedRecords;
    }

    public boolean isRunning() {
        return this.isExecutionRunning;
    }
}
