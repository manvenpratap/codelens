package com.tcs.bancs.CL;

import java.util.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Process Before Batch (Pre-run validation): CLPBPreSettlementCheck
 */
public class CLPBPreSettlementCheck {

    private final CLDGSettlementGrabber dataGrabber;
    private final ClearingHouseGatewayService service;
    private boolean isExecutionRunning = false;

    public CLPBPreSettlementCheck() {
        this.dataGrabber = new CLDGSettlementGrabber();
        this.service = new ClearingHouseGatewayService();
    }

    public CLPBPreSettlementCheck(CLDGSettlementGrabber dataGrabber, ClearingHouseGatewayService service) {
        this.dataGrabber = dataGrabber;
        this.service = service;
    }

    /**
     * Primary batch execution method: CLPBPreSettlementCheckProcess
     */
    public synchronized int CLPBPreSettlementCheckProcess() {
        this.isExecutionRunning = true;
        int processedRecords = 0;
        try {
            AuditTrailService.logAuditEvent("BATCH_START", "CLPBPreSettlementCheck", "PB", "START");
            List<?> records = this.dataGrabber.retrieveAll();
            processedRecords = records != null ? records.size() : 0;
            this.service.executeBatchProcessingCycle("CLPBPreSettlementCheck", processedRecords);
            AuditTrailService.logAuditEvent("BATCH_COMPLETE", "CLPBPreSettlementCheck", "PB", "PROCESSED=" + processedRecords);
        } finally {
            this.isExecutionRunning = false;
        }
        return processedRecords;
    }

    public boolean isRunning() {
        return this.isExecutionRunning;
    }
}
