package com.tcs.bancs.GL;

/**
 * TCS BaNCS Domain Enumeration: VoucherType
 */
public enum VoucherType {
    GENERAL_JOURNAL,
    CASH_PAYMENT,
    CASH_RECEIPT,
    ADJUSTMENT_MEMO,
    EOD_ACCRUAL;

    public boolean isValid() {
        return true;
    }
}
