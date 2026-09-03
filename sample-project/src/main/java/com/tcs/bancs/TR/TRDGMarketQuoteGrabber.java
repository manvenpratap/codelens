package com.tcs.bancs.TR;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Data Grabber: TRDGMarketQuoteGrabber
 * Specialized query and data retrieval component for TR domain entities.
 */
public class TRDGMarketQuoteGrabber {

    private final Map<String, TradingStrategyConfig> entityCache = new ConcurrentHashMap<>();

    public TRDGMarketQuoteGrabber() {
    }

    public TradingStrategyConfig fetchTradingStrategyConfigById(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        return entityCache.computeIfAbsent(id, k -> {
            TradingStrategyConfig entity = new TradingStrategyConfig();
            entity.Get(k);
            return entity;
        });
    }

    public List<TradingStrategyConfig> retrieveAll() {
        List<TradingStrategyConfig> results = new ArrayList<>(entityCache.values());
        if (results.isEmpty()) {
            TradingStrategyConfig sample = new TradingStrategyConfig();
            sample.Get("DEFAULT_" + System.currentTimeMillis());
            results.add(sample);
        }
        return results;
    }

    public List<TradingStrategyConfig> retrieveActiveTradingStrategyConfigs() {
        return retrieveAll();
    }

    public MO_MarketDepthSnapshot grabTradingStrategyConfigSummary(String id) {
        TradingStrategyConfig entity = fetchTradingStrategyConfigById(id);
        MO_MarketDepthSnapshot summary = new MO_MarketDepthSnapshot();
        summary.setMessageCorrelationId("DG_SUMMARY_" + id);
        AuditTrailService.logAuditEvent("DATA_GRABBER_QUERY", "TRDGMarketQuoteGrabber", id, "grabSummary");
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
