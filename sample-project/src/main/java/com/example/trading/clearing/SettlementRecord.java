package com.example.trading.clearing;

public record SettlementRecord(
    String settlementId,
    String tradeId,
    String buyerAccountId,
    String sellerAccountId,
    String symbol,
    int quantity,
    double settlementAmount,
    long timestamp,
    boolean settled
) {}
