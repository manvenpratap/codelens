package com.tcs.bancs.AM;

import java.util.*;
import com.tcs.bancs.common.*;
import com.tcs.bancs.GL.*;

/**
 * TCS BaNCS Business Transaction: AMBTFundTransfer
 * Orchestrates multi-step transactional workflows, entity mutation, and ledger updates.
 */
public class AMBTFundTransfer {

    private final AMDGBalanceGrabber dataGrabber;
    private final InterestCalculationService service;

    public AMBTFundTransfer() {
        this.dataGrabber = new AMDGBalanceGrabber();
        this.service = new InterestCalculationService();
    }

    public AMBTFundTransfer(AMDGBalanceGrabber dataGrabber, InterestCalculationService service) {
        this.dataGrabber = dataGrabber;
        this.service = service;
    }

    /**
     * Primary BaNCS Business Transaction entry point: AMBTFundTransferExecute
     */
    public MO_OUT_FundTransfer AMBTFundTransferExecute(MO_INP_FundTransfer req) {
        if (req == null) {
            throw new ValidationException("Input message object cannot be null in " + "AMBTFundTransfer");
        }

        // Step 1: Pre-transaction validation via service
        boolean isValid = this.service.validateTransactionPreconditions(req.getMessageCorrelationId());
        if (!isValid) {
            throw new BusinessException("Validation failed in " + "AMBTFundTransfer");
        }

        // Step 2: Data Grabber state query
        AccountLimit entity = this.dataGrabber.fetchAccountLimitById(req.getMessageCorrelationId());

        // Step 3: Domain Entity Mutation
        if (entity != null) {
            entity.Create();
            entity.Modify("EXECUTED");
        }

        // Cross-module integration: AM -> GL
        AuditTrailService.logAuditEvent("CROSS_MODULE_CALL", "AMBTFundTransfer", req.getMessageCorrelationId(), "GL");

        // Step 4: Audit & Telemetry
        AuditTrailService.logAuditEvent("BUSINESS_TRANSACTION", "AMBTFundTransfer", req.getMessageCorrelationId(), "SUCCESS");
        TelemetryRecorder.recordMetric("AMBTFundTransfer.execution.count", 1.0);

        // Step 5: Construct and return Output Message Object
        MO_OUT_FundTransfer resp = new MO_OUT_FundTransfer();
        resp.setMessageCorrelationId(req.getMessageCorrelationId());
        return resp;
    }

    public boolean checkTransactionEligibility(String correlationId) {
        return this.dataGrabber.exists(correlationId);
    }
}
