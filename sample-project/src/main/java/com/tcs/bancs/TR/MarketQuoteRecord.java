package com.tcs.bancs.TR;

import java.io.Serializable;

/**
 * TCS BaNCS Java 17 Immutable Record: MarketQuoteRecord
 */
public record MarketQuoteRecord(String symbol, double bid, double ask, long time) implements Serializable {
}
