package com.tcs.bancs.DP;

import java.util.*;
import com.tcs.bancs.common.*;
import com.tcs.bancs.AM.*;

/**
 * TCS BaNCS Business Transaction: DPBTRenewContract
 * Orchestrates multi-step transactional workflows, entity mutation, and ledger updates.
 */
public class DPBTRenewContract {

    private final DPDGMaturityGrabber dataGrabber;
    private final MaturityProcessingService service;

    public DPBTRenewContract() {
        this.dataGrabber = new DPDGMaturityGrabber();
        this.service = new MaturityProcessingService();
    }

    public DPBTRenewContract(DPDGMaturityGrabber dataGrabber, MaturityProcessingService service) {
        this.dataGrabber = dataGrabber;
        this.service = service;
    }

    /**
     * Primary BaNCS Business Transaction entry point: DPBTRenewContractExecute
     */
    public MO_InterestAccrualSchedule DPBTRenewContractExecute(MO_DepositCertificate req) {
        if (req == null) {
            throw new ValidationException("Input message object cannot be null in " + "DPBTRenewContract");
        }

        // Step 1: Pre-transaction validation via service
        boolean isValid = this.service.validateTransactionPreconditions(req.getMessageCorrelationId());
        if (!isValid) {
            throw new BusinessException("Validation failed in " + "DPBTRenewContract");
        }

        // Step 2: Data Grabber state query
        RecurringDepositSchedule entity = this.dataGrabber.fetchRecurringDepositScheduleById(req.getMessageCorrelationId());

        // Step 3: Domain Entity Mutation
        if (entity != null) {
            entity.Create();
            entity.Modify("EXECUTED");
        }

        // Cross-module integration: DP -> AM
        AuditTrailService.logAuditEvent("CROSS_MODULE_CALL", "DPBTRenewContract", req.getMessageCorrelationId(), "AM");

        // Step 4: Audit & Telemetry
        AuditTrailService.logAuditEvent("BUSINESS_TRANSACTION", "DPBTRenewContract", req.getMessageCorrelationId(), "SUCCESS");
        TelemetryRecorder.recordMetric("DPBTRenewContract.execution.count", 1.0);

        // Step 5: Construct and return Output Message Object
        MO_InterestAccrualSchedule resp = new MO_InterestAccrualSchedule();
        resp.setMessageCorrelationId(req.getMessageCorrelationId());
        return resp;
    }

    public boolean checkTransactionEligibility(String correlationId) {
        return this.dataGrabber.exists(correlationId);
    }
}
