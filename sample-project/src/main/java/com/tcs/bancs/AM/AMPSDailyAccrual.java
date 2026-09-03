package com.tcs.bancs.AM;

import java.util.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Batch Processor (EOD / Periodic Execution): AMPSDailyAccrual
 */
public class AMPSDailyAccrual {

    private final AMDGAccountGrabber dataGrabber;
    private final AccountService service;
    private boolean isExecutionRunning = false;

    public AMPSDailyAccrual() {
        this.dataGrabber = new AMDGAccountGrabber();
        this.service = new AccountService();
    }

    public AMPSDailyAccrual(AMDGAccountGrabber dataGrabber, AccountService service) {
        this.dataGrabber = dataGrabber;
        this.service = service;
    }

    /**
     * Primary batch execution method: AMPSDailyAccrualProcess
     */
    public synchronized int AMPSDailyAccrualProcess() {
        this.isExecutionRunning = true;
        int processedRecords = 0;
        try {
            AuditTrailService.logAuditEvent("BATCH_START", "AMPSDailyAccrual", "PS", "START");
            List<?> records = this.dataGrabber.retrieveAll();
            processedRecords = records != null ? records.size() : 0;
            this.service.executeBatchProcessingCycle("AMPSDailyAccrual", processedRecords);
            AuditTrailService.logAuditEvent("BATCH_COMPLETE", "AMPSDailyAccrual", "PS", "PROCESSED=" + processedRecords);
        } finally {
            this.isExecutionRunning = false;
        }
        return processedRecords;
    }

    public boolean isRunning() {
        return this.isExecutionRunning;
    }
}
