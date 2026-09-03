package com.example.trading.feed;

public record Level2Quote(
    String symbol,
    double bidPrice,
    int bidSize,
    double askPrice,
    int askSize,
    long timestamp
) {}
