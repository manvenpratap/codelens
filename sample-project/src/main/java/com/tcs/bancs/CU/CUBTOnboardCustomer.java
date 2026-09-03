package com.tcs.bancs.CU;

import java.util.*;
import com.tcs.bancs.common.*;


/**
 * TCS BaNCS Business Transaction: CUBTOnboardCustomer
 * Orchestrates multi-step transactional workflows, entity mutation, and ledger updates.
 */
public class CUBTOnboardCustomer {

    private final CUDGCustomerGrabber dataGrabber;
    private final CustomerOnboardingService service;

    public CUBTOnboardCustomer() {
        this.dataGrabber = new CUDGCustomerGrabber();
        this.service = new CustomerOnboardingService();
    }

    public CUBTOnboardCustomer(CUDGCustomerGrabber dataGrabber, CustomerOnboardingService service) {
        this.dataGrabber = dataGrabber;
        this.service = service;
    }

    /**
     * Primary BaNCS Business Transaction entry point: CUBTOnboardCustomerExecute
     */
    public MO_OUT_CustomerOnboarding CUBTOnboardCustomerExecute(MO_INP_CustomerOnboarding req) {
        if (req == null) {
            throw new ValidationException("Input message object cannot be null in " + "CUBTOnboardCustomer");
        }

        // Step 1: Pre-transaction validation via service
        boolean isValid = this.service.validateTransactionPreconditions(req.getMessageCorrelationId());
        if (!isValid) {
            throw new BusinessException("Validation failed in " + "CUBTOnboardCustomer");
        }

        // Step 2: Data Grabber state query
        CustomerProfile entity = this.dataGrabber.fetchCustomerProfileById(req.getMessageCorrelationId());

        // Step 3: Domain Entity Mutation
        if (entity != null) {
            entity.Create();
            entity.Modify("EXECUTED");
        }



        // Step 4: Audit & Telemetry
        AuditTrailService.logAuditEvent("BUSINESS_TRANSACTION", "CUBTOnboardCustomer", req.getMessageCorrelationId(), "SUCCESS");
        TelemetryRecorder.recordMetric("CUBTOnboardCustomer.execution.count", 1.0);

        // Step 5: Construct and return Output Message Object
        MO_OUT_CustomerOnboarding resp = new MO_OUT_CustomerOnboarding();
        resp.setMessageCorrelationId(req.getMessageCorrelationId());
        return resp;
    }

    public boolean checkTransactionEligibility(String correlationId) {
        return this.dataGrabber.exists(correlationId);
    }
}
