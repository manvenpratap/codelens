package com.tcs.bancs.TR;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Data Getter: TRDGMarketQuoteGetter
 * Archetype: Paging Cursor Data Getter
 * Specialized query and data retrieval component for TR domain entities.
 */
public class TRDGMarketQuoteGetter {

    private final Map<String, TradingStrategyConfig> entityCache = new ConcurrentHashMap<>();

    public TRDGMarketQuoteGetter() {
    }

    /**
     * Type 4: Paging Cursor Data Getter - Cursor lifecycle and paging (Open, Fetch, Close, GetMFwd, GetMBwd).
     */
    private Iterator<TradingStrategyConfig> cursorIterator;
    private boolean cursorOpen = false;

    public void Open(String criteria) {
        this.cursorIterator = retrieveAll().iterator();
        this.cursorOpen = true;
        AuditTrailService.logAuditEvent("DATA_GETTER_CURSOR_OPEN", "TRDGMarketQuoteGetter", criteria, "OPEN");
    }

    public TradingStrategyConfig Fetch() {
        if (!cursorOpen || cursorIterator == null || !cursorIterator.hasNext()) {
            return null;
        }
        return cursorIterator.next();
    }

    public void Close() {
        this.cursorOpen = false;
        this.cursorIterator = null;
        AuditTrailService.logAuditEvent("DATA_GETTER_CURSOR_CLOSE", "TRDGMarketQuoteGetter", "STATUS", "CLOSED");
    }

    public List<TradingStrategyConfig> GetMFwd(String startKey, int pageSize) {
        List<TradingStrategyConfig> results = retrieveAll();
        int limit = Math.max(1, Math.min(pageSize, results.size()));
        return new ArrayList<>(results.subList(0, limit));
    }

    public List<TradingStrategyConfig> GetMBwd(String startKey, int pageSize) {
        List<TradingStrategyConfig> results = retrieveAll();
        Collections.reverse(results);
        int limit = Math.max(1, Math.min(pageSize, results.size()));
        return new ArrayList<>(results.subList(0, limit));
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
        AuditTrailService.logAuditEvent("DATA_GETTER_QUERY", "TRDGMarketQuoteGetter", id, "grabSummary");
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
