package com.tcs.bancs.SC;

import java.util.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Batch Processor (EOD / Periodic Execution): SCPSEODLtvMonitoring
 */
public class SCPSEODLtvMonitoring {

    private final SCDGCollateralGrabber dataGrabber;
    private final CollateralRegistrationService service;
    private boolean isExecutionRunning = false;

    public SCPSEODLtvMonitoring() {
        this.dataGrabber = new SCDGCollateralGrabber();
        this.service = new CollateralRegistrationService();
    }

    public SCPSEODLtvMonitoring(SCDGCollateralGrabber dataGrabber, CollateralRegistrationService service) {
        this.dataGrabber = dataGrabber;
        this.service = service;
    }

    /**
     * Primary batch execution method: SCPSEODLtvMonitoringProcess
     */
    public synchronized int SCPSEODLtvMonitoringProcess() {
        this.isExecutionRunning = true;
        int processedRecords = 0;
        try {
            AuditTrailService.logAuditEvent("BATCH_START", "SCPSEODLtvMonitoring", "PS", "START");
            List<?> records = this.dataGrabber.retrieveAll();
            processedRecords = records != null ? records.size() : 0;
            this.service.executeBatchProcessingCycle("SCPSEODLtvMonitoring", processedRecords);
            AuditTrailService.logAuditEvent("BATCH_COMPLETE", "SCPSEODLtvMonitoring", "PS", "PROCESSED=" + processedRecords);
        } finally {
            this.isExecutionRunning = false;
        }
        return processedRecords;
    }

    public boolean isRunning() {
        return this.isExecutionRunning;
    }
}
