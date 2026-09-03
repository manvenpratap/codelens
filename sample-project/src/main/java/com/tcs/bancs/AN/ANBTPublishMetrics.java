package com.tcs.bancs.AN;

import java.util.*;
import com.tcs.bancs.common.*;
import com.tcs.bancs.AM.*;

/**
 * TCS BaNCS Business Transaction: ANBTPublishMetrics
 * Orchestrates multi-step transactional workflows, entity mutation, and ledger updates.
 */
public class ANBTPublishMetrics {

    private final ANDGAnalyticsGrabber dataGrabber;
    private final ExecutiveDashboardService service;

    public ANBTPublishMetrics() {
        this.dataGrabber = new ANDGAnalyticsGrabber();
        this.service = new ExecutiveDashboardService();
    }

    public ANBTPublishMetrics(ANDGAnalyticsGrabber dataGrabber, ExecutiveDashboardService service) {
        this.dataGrabber = dataGrabber;
        this.service = service;
    }

    /**
     * Primary BaNCS Business Transaction entry point: ANBTPublishMetricsExecute
     */
    public MO_RegulatoryFiling ANBTPublishMetricsExecute(MO_PnLDecomposition req) {
        if (req == null) {
            throw new ValidationException("Input message object cannot be null in " + "ANBTPublishMetrics");
        }

        // Step 1: Pre-transaction validation via service
        boolean isValid = this.service.validateTransactionPreconditions(req.getMessageCorrelationId());
        if (!isValid) {
            throw new BusinessException("Validation failed in " + "ANBTPublishMetrics");
        }

        // Step 2: Data Grabber state query
        PnLSummaryRecord entity = this.dataGrabber.fetchPnLSummaryRecordById(req.getMessageCorrelationId());

        // Step 3: Domain Entity Mutation
        if (entity != null) {
            entity.Create();
            entity.Modify("EXECUTED");
        }

        // Cross-module integration: AN -> AM
        AuditTrailService.logAuditEvent("CROSS_MODULE_CALL", "ANBTPublishMetrics", req.getMessageCorrelationId(), "AM");

        // Step 4: Audit & Telemetry
        AuditTrailService.logAuditEvent("BUSINESS_TRANSACTION", "ANBTPublishMetrics", req.getMessageCorrelationId(), "SUCCESS");
        TelemetryRecorder.recordMetric("ANBTPublishMetrics.execution.count", 1.0);

        // Step 5: Construct and return Output Message Object
        MO_RegulatoryFiling resp = new MO_RegulatoryFiling();
        resp.setMessageCorrelationId(req.getMessageCorrelationId());
        return resp;
    }

    public boolean checkTransactionEligibility(String correlationId) {
        return this.dataGrabber.exists(correlationId);
    }
}
