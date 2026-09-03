package com.tcs.bancs.RK;

import java.util.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Process After Batch (Post-run reconciliation): RKPAPostRiskReporting
 */
public class RKPAPostRiskReporting {

    private final RKDGRiskGrabber dataGrabber;
    private final MarketRiskService service;
    private boolean isExecutionRunning = false;

    public RKPAPostRiskReporting() {
        this.dataGrabber = new RKDGRiskGrabber();
        this.service = new MarketRiskService();
    }

    public RKPAPostRiskReporting(RKDGRiskGrabber dataGrabber, MarketRiskService service) {
        this.dataGrabber = dataGrabber;
        this.service = service;
    }

    /**
     * Primary batch execution method: RKPAPostRiskReportingProcess
     */
    public synchronized int RKPAPostRiskReportingProcess() {
        this.isExecutionRunning = true;
        int processedRecords = 0;
        try {
            AuditTrailService.logAuditEvent("BATCH_START", "RKPAPostRiskReporting", "PA", "START");
            List<?> records = this.dataGrabber.retrieveAll();
            processedRecords = records != null ? records.size() : 0;
            this.service.executeBatchProcessingCycle("RKPAPostRiskReporting", processedRecords);
            AuditTrailService.logAuditEvent("BATCH_COMPLETE", "RKPAPostRiskReporting", "PA", "PROCESSED=" + processedRecords);
        } finally {
            this.isExecutionRunning = false;
        }
        return processedRecords;
    }

    public boolean isRunning() {
        return this.isExecutionRunning;
    }
}
