package com.example.trading.strategy;

public record StrategyExecutionContext(
    String strategyId,
    String accountId,
    double allocatedCapital,
    long timestamp
) {}
