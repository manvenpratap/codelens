package com.tcs.bancs.TR;

import java.util.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Elementary Transaction: TRETFetchMarketQuote
 * Read-only inquiry transaction for high-throughput non-mutating lookups.
 */
public class TRETFetchMarketQuote {

    private final TRDGMarketQuoteGrabber dataGrabber;

    public TRETFetchMarketQuote() {
        this.dataGrabber = new TRDGMarketQuoteGrabber();
    }

    public TRETFetchMarketQuote(TRDGMarketQuoteGrabber dataGrabber) {
        this.dataGrabber = dataGrabber;
    }

    /**
     * Primary Elementary Transaction fetch method: TRETFetchMarketQuoteFetch
     */
    public MO_OUT_QuoteRequest TRETFetchMarketQuoteFetch(String lookupKey) {
        if (lookupKey == null || lookupKey.isBlank()) {
            lookupKey = "INQUIRY_DEFAULT";
        }

        TradingStrategyConfig entity = this.dataGrabber.fetchTradingStrategyConfigById(lookupKey);
        AuditTrailService.logAuditEvent("ELEMENTARY_TRANSACTION", "TRETFetchMarketQuote", lookupKey, "FETCH");

        MO_OUT_QuoteRequest resp = new MO_OUT_QuoteRequest();
        resp.setMessageCorrelationId("ET_" + lookupKey);
        return resp;
    }

    public boolean isHealthy() {
        return this.dataGrabber != null;
    }
}
