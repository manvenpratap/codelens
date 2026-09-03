package com.tcs.bancs.GL;

/**
 * TCS BaNCS Domain Enumeration: PostingSide
 */
public enum PostingSide {
    DEBIT,
    CREDIT;

    public boolean isValid() {
        return true;
    }
}
