package com.tcs.bancs.AM;

import java.util.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Inbound Channel Controller: FundTransferController
 * Dispatches inbound requests from REST, Branch, ISO, and FIX channels.
 */
public class FundTransferController {

    private final AMBTFundTransfer businessTransaction;
    private final AMETQueryAccountDetails elementaryTransaction;

    public FundTransferController() {
        this.businessTransaction = new AMBTFundTransfer();
        this.elementaryTransaction = new AMETQueryAccountDetails();
    }

    public FundTransferController(AMBTFundTransfer bt, AMETQueryAccountDetails et) {
        this.businessTransaction = bt;
        this.elementaryTransaction = et;
    }

    /**
     * Inbound mutating command handler.
     */
    public MO_OUT_FundTransfer handleExecuteRequest(MO_INP_FundTransfer request) {
        AuditTrailService.logAuditEvent("CONTROLLER_INBOUND", "FundTransferController", request.getMessageCorrelationId(), "MUTATION");
        return this.businessTransaction.AMBTFundTransferExecute(request);
    }

    /**
     * Inbound read-only query handler.
     */
    public MO_OUT_FundTransfer handleInquiryRequest(String queryKey) {
        AuditTrailService.logAuditEvent("CONTROLLER_INBOUND", "FundTransferController", queryKey, "INQUIRY");
        return this.elementaryTransaction.AMETQueryAccountDetailsFetch(queryKey);
    }

    public boolean ping() {
        return true;
    }
}
