package com.tcs.bancs.PM;

import java.util.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Process Before Batch (Pre-run validation): PMPBPreClearingValidation
 */
public class PMPBPreClearingValidation {

    private final PMDGPaymentGrabber dataGrabber;
    private final PaymentInitiationService service;
    private boolean isExecutionRunning = false;

    public PMPBPreClearingValidation() {
        this.dataGrabber = new PMDGPaymentGrabber();
        this.service = new PaymentInitiationService();
    }

    public PMPBPreClearingValidation(PMDGPaymentGrabber dataGrabber, PaymentInitiationService service) {
        this.dataGrabber = dataGrabber;
        this.service = service;
    }

    /**
     * Primary batch execution method: PMPBPreClearingValidationProcess
     */
    public synchronized int PMPBPreClearingValidationProcess() {
        this.isExecutionRunning = true;
        int processedRecords = 0;
        try {
            AuditTrailService.logAuditEvent("BATCH_START", "PMPBPreClearingValidation", "PB", "START");
            List<?> records = this.dataGrabber.retrieveAll();
            processedRecords = records != null ? records.size() : 0;
            this.service.executeBatchProcessingCycle("PMPBPreClearingValidation", processedRecords);
            AuditTrailService.logAuditEvent("BATCH_COMPLETE", "PMPBPreClearingValidation", "PB", "PROCESSED=" + processedRecords);
        } finally {
            this.isExecutionRunning = false;
        }
        return processedRecords;
    }

    public boolean isRunning() {
        return this.isExecutionRunning;
    }
}
