package com.tcs.bancs.AN;

import java.util.*;
import com.tcs.bancs.common.*;
import com.tcs.bancs.AM.*;

/**
 * TCS BaNCS Business Transaction: ANBTExecuteLiquidityTest
 * Orchestrates multi-step transactional workflows, entity mutation, and ledger updates.
 */
public class ANBTExecuteLiquidityTest {

    private final ANDGReportGrabber dataGrabber;
    private final LiquidityRiskModelingService service;

    public ANBTExecuteLiquidityTest() {
        this.dataGrabber = new ANDGReportGrabber();
        this.service = new LiquidityRiskModelingService();
    }

    public ANBTExecuteLiquidityTest(ANDGReportGrabber dataGrabber, LiquidityRiskModelingService service) {
        this.dataGrabber = dataGrabber;
        this.service = service;
    }

    /**
     * Primary BaNCS Business Transaction entry point: ANBTExecuteLiquidityTestExecute
     */
    public MO_OUT_LiquidityStressCheck ANBTExecuteLiquidityTestExecute(MO_INP_LiquidityStressCheck req) {
        if (req == null) {
            throw new ValidationException("Input message object cannot be null in " + "ANBTExecuteLiquidityTest");
        }

        // Step 1: Pre-transaction validation via service
        boolean isValid = this.service.validateTransactionPreconditions(req.getMessageCorrelationId());
        if (!isValid) {
            throw new BusinessException("Validation failed in " + "ANBTExecuteLiquidityTest");
        }

        // Step 2: Data Grabber state query
        RegulatoryReportSnapshot entity = this.dataGrabber.fetchRegulatoryReportSnapshotById(req.getMessageCorrelationId());

        // Step 3: Domain Entity Mutation
        if (entity != null) {
            entity.Create();
            entity.Modify("EXECUTED");
        }

        // Cross-module integration: AN -> AM
        AuditTrailService.logAuditEvent("CROSS_MODULE_CALL", "ANBTExecuteLiquidityTest", req.getMessageCorrelationId(), "AM");

        // Step 4: Audit & Telemetry
        AuditTrailService.logAuditEvent("BUSINESS_TRANSACTION", "ANBTExecuteLiquidityTest", req.getMessageCorrelationId(), "SUCCESS");
        TelemetryRecorder.recordMetric("ANBTExecuteLiquidityTest.execution.count", 1.0);

        // Step 5: Construct and return Output Message Object
        MO_OUT_LiquidityStressCheck resp = new MO_OUT_LiquidityStressCheck();
        resp.setMessageCorrelationId(req.getMessageCorrelationId());
        return resp;
    }

    public boolean checkTransactionEligibility(String correlationId) {
        return this.dataGrabber.exists(correlationId);
    }
}
