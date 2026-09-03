package com.tcs.bancs.CU;

import java.util.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Batch Processor (EOD / Periodic Execution): CUPSEODKycExpiryCheck
 */
public class CUPSEODKycExpiryCheck {

    private final CUDGCustomerGrabber dataGrabber;
    private final CustomerOnboardingService service;
    private boolean isExecutionRunning = false;

    public CUPSEODKycExpiryCheck() {
        this.dataGrabber = new CUDGCustomerGrabber();
        this.service = new CustomerOnboardingService();
    }

    public CUPSEODKycExpiryCheck(CUDGCustomerGrabber dataGrabber, CustomerOnboardingService service) {
        this.dataGrabber = dataGrabber;
        this.service = service;
    }

    /**
     * Primary batch execution method: CUPSEODKycExpiryCheckProcess
     */
    public synchronized int CUPSEODKycExpiryCheckProcess() {
        this.isExecutionRunning = true;
        int processedRecords = 0;
        try {
            AuditTrailService.logAuditEvent("BATCH_START", "CUPSEODKycExpiryCheck", "PS", "START");
            List<?> records = this.dataGrabber.retrieveAll();
            processedRecords = records != null ? records.size() : 0;
            this.service.executeBatchProcessingCycle("CUPSEODKycExpiryCheck", processedRecords);
            AuditTrailService.logAuditEvent("BATCH_COMPLETE", "CUPSEODKycExpiryCheck", "PS", "PROCESSED=" + processedRecords);
        } finally {
            this.isExecutionRunning = false;
        }
        return processedRecords;
    }

    public boolean isRunning() {
        return this.isExecutionRunning;
    }
}
