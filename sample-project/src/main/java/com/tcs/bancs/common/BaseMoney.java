package com.tcs.bancs.common;

import java.io.Serializable;
import java.math.BigDecimal;
import java.math.RoundingMode;

public class BaseMoney implements Serializable {
    private static final long serialVersionUID = 1L;
    private final BigDecimal amount;
    private final String currency;

    public BaseMoney(double amount, String currency) {
        this.amount = BigDecimal.valueOf(amount).setScale(4, RoundingMode.HALF_UP);
        this.currency = currency != null ? currency : "USD";
    }

    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public double doubleValue() { return amount.doubleValue(); }
}
