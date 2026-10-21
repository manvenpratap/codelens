package com.tcs.bancs.TR;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Data Getter: TRDGPortfolioGetter
 * Archetype: Cursor Data Getter
 * Specialized query and data retrieval component for TR domain entities.
 */
public class TRDGPortfolioGetter {

    private final Map<String, PortfolioHolding> entityCache = new ConcurrentHashMap<>();

    public TRDGPortfolioGetter() {
    }

    /**
     * Type 3: Cursor Data Getter - Streaming cursor lifecycle (Open, Fetch, Close).
     */
    private Iterator<PortfolioHolding> cursorIterator;
    private boolean cursorOpen = false;

    public void Open(String criteria) {
        this.cursorIterator = retrieveAll().iterator();
        this.cursorOpen = true;
        AuditTrailService.logAuditEvent("DATA_GETTER_CURSOR_OPEN", "TRDGPortfolioGetter", criteria, "OPEN");
    }

    public PortfolioHolding Fetch() {
        if (!cursorOpen || cursorIterator == null || !cursorIterator.hasNext()) {
            return null;
        }
        return cursorIterator.next();
    }

    public void Close() {
        this.cursorOpen = false;
        this.cursorIterator = null;
        AuditTrailService.logAuditEvent("DATA_GETTER_CURSOR_CLOSE", "TRDGPortfolioGetter", "STATUS", "CLOSED");
    }

    public PortfolioHolding fetchPortfolioHoldingById(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        return entityCache.computeIfAbsent(id, k -> {
            PortfolioHolding entity = new PortfolioHolding();
            entity.Get(k);
            return entity;
        });
    }

    public List<PortfolioHolding> retrieveAll() {
        List<PortfolioHolding> results = new ArrayList<>(entityCache.values());
        if (results.isEmpty()) {
            PortfolioHolding sample = new PortfolioHolding();
            sample.Get("DEFAULT_" + System.currentTimeMillis());
            results.add(sample);
        }
        return results;
    }

    public List<PortfolioHolding> retrieveActivePortfolioHoldings() {
        return retrieveAll();
    }

    public MO_MarketDepthSnapshot grabPortfolioHoldingSummary(String id) {
        PortfolioHolding entity = fetchPortfolioHoldingById(id);
        MO_MarketDepthSnapshot summary = new MO_MarketDepthSnapshot();
        summary.setMessageCorrelationId("DG_SUMMARY_" + id);
        AuditTrailService.logAuditEvent("DATA_GETTER_QUERY", "TRDGPortfolioGetter", id, "grabSummary");
        return summary;
    }

    public boolean exists(String id) {
        return id != null && (entityCache.containsKey(id) || id.length() > 3);
    }

    public void invalidateCache(String id) {
        if (id != null) {
            entityCache.remove(id);
        }
    }
}
