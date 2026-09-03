package com.tcs.bancs.RK;

import java.util.*;
import com.tcs.bancs.common.*;
import com.tcs.bancs.AM.*;

/**
 * TCS BaNCS Business Transaction: RKBTExecuteStressTest
 * Orchestrates multi-step transactional workflows, entity mutation, and ledger updates.
 */
public class RKBTExecuteStressTest {

    private final RKDGRiskGrabber dataGrabber;
    private final StressTestingService service;

    public RKBTExecuteStressTest() {
        this.dataGrabber = new RKDGRiskGrabber();
        this.service = new StressTestingService();
    }

    public RKBTExecuteStressTest(RKDGRiskGrabber dataGrabber, StressTestingService service) {
        this.dataGrabber = dataGrabber;
        this.service = service;
    }

    /**
     * Primary BaNCS Business Transaction entry point: RKBTExecuteStressTestExecute
     */
    public MO_ComplianceViolation RKBTExecuteStressTestExecute(MO_RiskMetricSummary req) {
        if (req == null) {
            throw new ValidationException("Input message object cannot be null in " + "RKBTExecuteStressTest");
        }

        // Step 1: Pre-transaction validation via service
        boolean isValid = this.service.validateTransactionPreconditions(req.getMessageCorrelationId());
        if (!isValid) {
            throw new BusinessException("Validation failed in " + "RKBTExecuteStressTest");
        }

        // Step 2: Data Grabber state query
        RiskExposure entity = this.dataGrabber.fetchRiskExposureById(req.getMessageCorrelationId());

        // Step 3: Domain Entity Mutation
        if (entity != null) {
            entity.Create();
            entity.Modify("EXECUTED");
        }

        // Cross-module integration: RK -> AM
        AuditTrailService.logAuditEvent("CROSS_MODULE_CALL", "RKBTExecuteStressTest", req.getMessageCorrelationId(), "AM");

        // Step 4: Audit & Telemetry
        AuditTrailService.logAuditEvent("BUSINESS_TRANSACTION", "RKBTExecuteStressTest", req.getMessageCorrelationId(), "SUCCESS");
        TelemetryRecorder.recordMetric("RKBTExecuteStressTest.execution.count", 1.0);

        // Step 5: Construct and return Output Message Object
        MO_ComplianceViolation resp = new MO_ComplianceViolation();
        resp.setMessageCorrelationId(req.getMessageCorrelationId());
        return resp;
    }

    public boolean checkTransactionEligibility(String correlationId) {
        return this.dataGrabber.exists(correlationId);
    }
}
