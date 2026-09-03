package com.example.trading.clearing;

import java.util.UUID;

public class SettlementEngine {
    private final ClearingHouseGateway gateway;

    public SettlementEngine(ClearingHouseGateway gateway) {
        this.gateway = gateway;
    }

    public SettlementRecord settleTrade(String tradeId, String buyer, String seller, String symbol, int qty, double price) {
        String sId = "SETTLE-" + UUID.randomUUID().toString().substring(0, 8);
        SettlementRecord record = new SettlementRecord(
            sId, tradeId, buyer, seller, symbol, qty, qty * price, System.currentTimeMillis(), true
        );
        gateway.submitForClearing(record);
        return record;
    }
}
