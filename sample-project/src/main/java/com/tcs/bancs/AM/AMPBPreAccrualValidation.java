package com.tcs.bancs.AM;

import java.util.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Process Before Batch (Pre-run validation): AMPBPreAccrualValidation
 */
public class AMPBPreAccrualValidation {

    private final AMDGAccountGrabber dataGrabber;
    private final AccountService service;
    private boolean isExecutionRunning = false;

    public AMPBPreAccrualValidation() {
        this.dataGrabber = new AMDGAccountGrabber();
        this.service = new AccountService();
    }

    public AMPBPreAccrualValidation(AMDGAccountGrabber dataGrabber, AccountService service) {
        this.dataGrabber = dataGrabber;
        this.service = service;
    }

    /**
     * Primary batch execution method: AMPBPreAccrualValidationProcess
     */
    public synchronized int AMPBPreAccrualValidationProcess() {
        this.isExecutionRunning = true;
        int processedRecords = 0;
        try {
            AuditTrailService.logAuditEvent("BATCH_START", "AMPBPreAccrualValidation", "PB", "START");
            List<?> records = this.dataGrabber.retrieveAll();
            processedRecords = records != null ? records.size() : 0;
            this.service.executeBatchProcessingCycle("AMPBPreAccrualValidation", processedRecords);
            AuditTrailService.logAuditEvent("BATCH_COMPLETE", "AMPBPreAccrualValidation", "PB", "PROCESSED=" + processedRecords);
        } finally {
            this.isExecutionRunning = false;
        }
        return processedRecords;
    }

    public boolean isRunning() {
        return this.isExecutionRunning;
    }
}
