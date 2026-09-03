package com.tcs.bancs.AN;

import java.util.*;
import com.tcs.bancs.common.*;
import com.tcs.bancs.AM.*;

/**
 * TCS BaNCS Business Transaction: ANBTGenerateRegulatoryFiling
 * Orchestrates multi-step transactional workflows, entity mutation, and ledger updates.
 */
public class ANBTGenerateRegulatoryFiling {

    private final ANDGLiquidityGrabber dataGrabber;
    private final BaselComplianceService service;

    public ANBTGenerateRegulatoryFiling() {
        this.dataGrabber = new ANDGLiquidityGrabber();
        this.service = new BaselComplianceService();
    }

    public ANBTGenerateRegulatoryFiling(ANDGLiquidityGrabber dataGrabber, BaselComplianceService service) {
        this.dataGrabber = dataGrabber;
        this.service = service;
    }

    /**
     * Primary BaNCS Business Transaction entry point: ANBTGenerateRegulatoryFilingExecute
     */
    public MO_OUT_BaselReportGenerate ANBTGenerateRegulatoryFilingExecute(MO_INP_BaselReportGenerate req) {
        if (req == null) {
            throw new ValidationException("Input message object cannot be null in " + "ANBTGenerateRegulatoryFiling");
        }

        // Step 1: Pre-transaction validation via service
        boolean isValid = this.service.validateTransactionPreconditions(req.getMessageCorrelationId());
        if (!isValid) {
            throw new BusinessException("Validation failed in " + "ANBTGenerateRegulatoryFiling");
        }

        // Step 2: Data Grabber state query
        LiquidityMetrics entity = this.dataGrabber.fetchLiquidityMetricsById(req.getMessageCorrelationId());

        // Step 3: Domain Entity Mutation
        if (entity != null) {
            entity.Create();
            entity.Modify("EXECUTED");
        }

        // Cross-module integration: AN -> AM
        AuditTrailService.logAuditEvent("CROSS_MODULE_CALL", "ANBTGenerateRegulatoryFiling", req.getMessageCorrelationId(), "AM");

        // Step 4: Audit & Telemetry
        AuditTrailService.logAuditEvent("BUSINESS_TRANSACTION", "ANBTGenerateRegulatoryFiling", req.getMessageCorrelationId(), "SUCCESS");
        TelemetryRecorder.recordMetric("ANBTGenerateRegulatoryFiling.execution.count", 1.0);

        // Step 5: Construct and return Output Message Object
        MO_OUT_BaselReportGenerate resp = new MO_OUT_BaselReportGenerate();
        resp.setMessageCorrelationId(req.getMessageCorrelationId());
        return resp;
    }

    public boolean checkTransactionEligibility(String correlationId) {
        return this.dataGrabber.exists(correlationId);
    }
}
