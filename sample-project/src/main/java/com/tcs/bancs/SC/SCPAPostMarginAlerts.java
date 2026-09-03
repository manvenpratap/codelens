package com.tcs.bancs.SC;

import java.util.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Process After Batch (Post-run reconciliation): SCPAPostMarginAlerts
 */
public class SCPAPostMarginAlerts {

    private final SCDGCollateralGrabber dataGrabber;
    private final CollateralRegistrationService service;
    private boolean isExecutionRunning = false;

    public SCPAPostMarginAlerts() {
        this.dataGrabber = new SCDGCollateralGrabber();
        this.service = new CollateralRegistrationService();
    }

    public SCPAPostMarginAlerts(SCDGCollateralGrabber dataGrabber, CollateralRegistrationService service) {
        this.dataGrabber = dataGrabber;
        this.service = service;
    }

    /**
     * Primary batch execution method: SCPAPostMarginAlertsProcess
     */
    public synchronized int SCPAPostMarginAlertsProcess() {
        this.isExecutionRunning = true;
        int processedRecords = 0;
        try {
            AuditTrailService.logAuditEvent("BATCH_START", "SCPAPostMarginAlerts", "PA", "START");
            List<?> records = this.dataGrabber.retrieveAll();
            processedRecords = records != null ? records.size() : 0;
            this.service.executeBatchProcessingCycle("SCPAPostMarginAlerts", processedRecords);
            AuditTrailService.logAuditEvent("BATCH_COMPLETE", "SCPAPostMarginAlerts", "PA", "PROCESSED=" + processedRecords);
        } finally {
            this.isExecutionRunning = false;
        }
        return processedRecords;
    }

    public boolean isRunning() {
        return this.isExecutionRunning;
    }
}
