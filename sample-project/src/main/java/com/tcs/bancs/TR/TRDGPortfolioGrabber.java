package com.tcs.bancs.TR;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Data Grabber: TRDGPortfolioGrabber
 * Specialized query and data retrieval component for TR domain entities.
 */
public class TRDGPortfolioGrabber {

    private final Map<String, PortfolioHolding> entityCache = new ConcurrentHashMap<>();

    public TRDGPortfolioGrabber() {
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
        AuditTrailService.logAuditEvent("DATA_GRABBER_QUERY", "TRDGPortfolioGrabber", id, "grabSummary");
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
