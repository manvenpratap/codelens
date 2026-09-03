package com.tcs.bancs.CU;

import java.util.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Process Before Batch (Pre-run validation): CUPBPreKycAudit
 */
public class CUPBPreKycAudit {

    private final CUDGCustomerGrabber dataGrabber;
    private final CustomerOnboardingService service;
    private boolean isExecutionRunning = false;

    public CUPBPreKycAudit() {
        this.dataGrabber = new CUDGCustomerGrabber();
        this.service = new CustomerOnboardingService();
    }

    public CUPBPreKycAudit(CUDGCustomerGrabber dataGrabber, CustomerOnboardingService service) {
        this.dataGrabber = dataGrabber;
        this.service = service;
    }

    /**
     * Primary batch execution method: CUPBPreKycAuditProcess
     */
    public synchronized int CUPBPreKycAuditProcess() {
        this.isExecutionRunning = true;
        int processedRecords = 0;
        try {
            AuditTrailService.logAuditEvent("BATCH_START", "CUPBPreKycAudit", "PB", "START");
            List<?> records = this.dataGrabber.retrieveAll();
            processedRecords = records != null ? records.size() : 0;
            this.service.executeBatchProcessingCycle("CUPBPreKycAudit", processedRecords);
            AuditTrailService.logAuditEvent("BATCH_COMPLETE", "CUPBPreKycAudit", "PB", "PROCESSED=" + processedRecords);
        } finally {
            this.isExecutionRunning = false;
        }
        return processedRecords;
    }

    public boolean isRunning() {
        return this.isExecutionRunning;
    }
}
