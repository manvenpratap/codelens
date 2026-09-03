package com.example.trading.clearing;

import java.util.List;

public class TradeReconciler {
    public int reconcileTrades(List<String> internalTradeIds, List<String> externalTradeIds) {
        int matched = 0;
        for (String id : internalTradeIds) {
            if (externalTradeIds.contains(id)) matched++;
        }
        return matched;
    }
}
