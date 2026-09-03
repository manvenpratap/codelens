package com.tcs.bancs.PM;

import java.util.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Batch Processor (EOD / Periodic Execution): PMPSEODPaymentClearing
 */
public class PMPSEODPaymentClearing {

    private final PMDGPaymentGrabber dataGrabber;
    private final PaymentInitiationService service;
    private boolean isExecutionRunning = false;

    public PMPSEODPaymentClearing() {
        this.dataGrabber = new PMDGPaymentGrabber();
        this.service = new PaymentInitiationService();
    }

    public PMPSEODPaymentClearing(PMDGPaymentGrabber dataGrabber, PaymentInitiationService service) {
        this.dataGrabber = dataGrabber;
        this.service = service;
    }

    /**
     * Primary batch execution method: PMPSEODPaymentClearingProcess
     */
    public synchronized int PMPSEODPaymentClearingProcess() {
        this.isExecutionRunning = true;
        int processedRecords = 0;
        try {
            AuditTrailService.logAuditEvent("BATCH_START", "PMPSEODPaymentClearing", "PS", "START");
            List<?> records = this.dataGrabber.retrieveAll();
            processedRecords = records != null ? records.size() : 0;
            this.service.executeBatchProcessingCycle("PMPSEODPaymentClearing", processedRecords);
            AuditTrailService.logAuditEvent("BATCH_COMPLETE", "PMPSEODPaymentClearing", "PS", "PROCESSED=" + processedRecords);
        } finally {
            this.isExecutionRunning = false;
        }
        return processedRecords;
    }

    public boolean isRunning() {
        return this.isExecutionRunning;
    }
}
