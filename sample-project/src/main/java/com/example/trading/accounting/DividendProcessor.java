package com.example.trading.accounting;

public class DividendProcessor {
    public double calculateDividendPayout(int shareCount, double dividendPerShare) {
        return shareCount * dividendPerShare;
    }
}
