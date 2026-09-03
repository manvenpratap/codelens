package com.tcs.bancs.DP;

import java.util.*;
import com.tcs.bancs.common.*;
import com.tcs.bancs.AM.*;

/**
 * TCS BaNCS Business Transaction: DPBTBookDeposit
 * Orchestrates multi-step transactional workflows, entity mutation, and ledger updates.
 */
public class DPBTBookDeposit {

    private final DPDGDepositGrabber dataGrabber;
    private final DepositBookingService service;

    public DPBTBookDeposit() {
        this.dataGrabber = new DPDGDepositGrabber();
        this.service = new DepositBookingService();
    }

    public DPBTBookDeposit(DPDGDepositGrabber dataGrabber, DepositBookingService service) {
        this.dataGrabber = dataGrabber;
        this.service = service;
    }

    /**
     * Primary BaNCS Business Transaction entry point: DPBTBookDepositExecute
     */
    public MO_OUT_DepositBooking DPBTBookDepositExecute(MO_INP_DepositBooking req) {
        if (req == null) {
            throw new ValidationException("Input message object cannot be null in " + "DPBTBookDeposit");
        }

        // Step 1: Pre-transaction validation via service
        boolean isValid = this.service.validateTransactionPreconditions(req.getMessageCorrelationId());
        if (!isValid) {
            throw new BusinessException("Validation failed in " + "DPBTBookDeposit");
        }

        // Step 2: Data Grabber state query
        DepositContract entity = this.dataGrabber.fetchDepositContractById(req.getMessageCorrelationId());

        // Step 3: Domain Entity Mutation
        if (entity != null) {
            entity.Create();
            entity.Modify("EXECUTED");
        }

        // Cross-module integration: DP -> AM
        AuditTrailService.logAuditEvent("CROSS_MODULE_CALL", "DPBTBookDeposit", req.getMessageCorrelationId(), "AM");

        // Step 4: Audit & Telemetry
        AuditTrailService.logAuditEvent("BUSINESS_TRANSACTION", "DPBTBookDeposit", req.getMessageCorrelationId(), "SUCCESS");
        TelemetryRecorder.recordMetric("DPBTBookDeposit.execution.count", 1.0);

        // Step 5: Construct and return Output Message Object
        MO_OUT_DepositBooking resp = new MO_OUT_DepositBooking();
        resp.setMessageCorrelationId(req.getMessageCorrelationId());
        return resp;
    }

    public boolean checkTransactionEligibility(String correlationId) {
        return this.dataGrabber.exists(correlationId);
    }
}
