package com.tcs.bancs.CU;

import java.util.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Inbound Channel Controller: CustomerManagementController
 * Dispatches inbound requests from REST, Branch, ISO, and FIX channels.
 */
public class CustomerManagementController {

    private final CUBTOnboardCustomer businessTransaction;
    private final CUETGetCustomerProfile elementaryTransaction;

    public CustomerManagementController() {
        this.businessTransaction = new CUBTOnboardCustomer();
        this.elementaryTransaction = new CUETGetCustomerProfile();
    }

    public CustomerManagementController(CUBTOnboardCustomer bt, CUETGetCustomerProfile et) {
        this.businessTransaction = bt;
        this.elementaryTransaction = et;
    }

    /**
     * Inbound mutating command handler.
     */
    public MO_OUT_CustomerOnboarding handleExecuteRequest(MO_INP_CustomerOnboarding request) {
        AuditTrailService.logAuditEvent("CONTROLLER_INBOUND", "CustomerManagementController", request.getMessageCorrelationId(), "MUTATION");
        return this.businessTransaction.CUBTOnboardCustomerExecute(request);
    }

    /**
     * Inbound read-only query handler.
     */
    public MO_OUT_CustomerOnboarding handleInquiryRequest(String queryKey) {
        AuditTrailService.logAuditEvent("CONTROLLER_INBOUND", "CustomerManagementController", queryKey, "INQUIRY");
        return this.elementaryTransaction.CUETGetCustomerProfileFetch(queryKey);
    }

    public boolean ping() {
        return true;
    }
}
