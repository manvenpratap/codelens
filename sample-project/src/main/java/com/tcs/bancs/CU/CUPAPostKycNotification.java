package com.tcs.bancs.CU;

import java.util.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Process After Batch (Post-run reconciliation): CUPAPostKycNotification
 */
public class CUPAPostKycNotification {

    private final CUDGCustomerGrabber dataGrabber;
    private final CustomerOnboardingService service;
    private boolean isExecutionRunning = false;

    public CUPAPostKycNotification() {
        this.dataGrabber = new CUDGCustomerGrabber();
        this.service = new CustomerOnboardingService();
    }

    public CUPAPostKycNotification(CUDGCustomerGrabber dataGrabber, CustomerOnboardingService service) {
        this.dataGrabber = dataGrabber;
        this.service = service;
    }

    /**
     * Primary batch execution method: CUPAPostKycNotificationProcess
     */
    public synchronized int CUPAPostKycNotificationProcess() {
        this.isExecutionRunning = true;
        int processedRecords = 0;
        try {
            AuditTrailService.logAuditEvent("BATCH_START", "CUPAPostKycNotification", "PA", "START");
            List<?> records = this.dataGrabber.retrieveAll();
            processedRecords = records != null ? records.size() : 0;
            this.service.executeBatchProcessingCycle("CUPAPostKycNotification", processedRecords);
            AuditTrailService.logAuditEvent("BATCH_COMPLETE", "CUPAPostKycNotification", "PA", "PROCESSED=" + processedRecords);
        } finally {
            this.isExecutionRunning = false;
        }
        return processedRecords;
    }

    public boolean isRunning() {
        return this.isExecutionRunning;
    }
}
