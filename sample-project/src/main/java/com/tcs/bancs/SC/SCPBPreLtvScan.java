package com.tcs.bancs.SC;

import java.util.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Process Before Batch (Pre-run validation): SCPBPreLtvScan
 */
public class SCPBPreLtvScan {

    private final SCDGCollateralGrabber dataGrabber;
    private final CollateralRegistrationService service;
    private boolean isExecutionRunning = false;

    public SCPBPreLtvScan() {
        this.dataGrabber = new SCDGCollateralGrabber();
        this.service = new CollateralRegistrationService();
    }

    public SCPBPreLtvScan(SCDGCollateralGrabber dataGrabber, CollateralRegistrationService service) {
        this.dataGrabber = dataGrabber;
        this.service = service;
    }

    /**
     * Primary batch execution method: SCPBPreLtvScanProcess
     */
    public synchronized int SCPBPreLtvScanProcess() {
        this.isExecutionRunning = true;
        int processedRecords = 0;
        try {
            AuditTrailService.logAuditEvent("BATCH_START", "SCPBPreLtvScan", "PB", "START");
            List<?> records = this.dataGrabber.retrieveAll();
            processedRecords = records != null ? records.size() : 0;
            this.service.executeBatchProcessingCycle("SCPBPreLtvScan", processedRecords);
            AuditTrailService.logAuditEvent("BATCH_COMPLETE", "SCPBPreLtvScan", "PB", "PROCESSED=" + processedRecords);
        } finally {
            this.isExecutionRunning = false;
        }
        return processedRecords;
    }

    public boolean isRunning() {
        return this.isExecutionRunning;
    }
}
