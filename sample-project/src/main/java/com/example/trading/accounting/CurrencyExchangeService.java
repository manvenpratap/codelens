package com.example.trading.accounting;

import java.util.Map;
import java.util.HashMap;

public class CurrencyExchangeService {
    private final Map<String, Double> fxRates = new HashMap<>();

    public CurrencyExchangeService() {
        fxRates.put("EUR/USD", 1.08);
        fxRates.put("GBP/USD", 1.28);
        fxRates.put("USD/JPY", 155.0);
    }

    public double convert(double amount, String currencyPair) {
        double rate = fxRates.getOrDefault(currencyPair, 1.0);
        return amount * rate;
    }
}
