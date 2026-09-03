package com.example.trading.clearing;

public class DefaultFundManager {
    private double reservePool = 10_000_000.0;

    public synchronized void contributeToPool(double amount) {
        this.reservePool += amount;
    }

    public synchronized double getAvailableReserve() {
        return reservePool;
    }
}
