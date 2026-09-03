package com.tcs.bancs.LN;

/**
 * TCS BaNCS Domain Enumeration: NPACategory
 */
public enum NPACategory {
    STANDARD,
    SPECIAL_MENTION,
    SUB_STANDARD,
    DOUBTFUL,
    LOSS_ASSET;

    public boolean isValid() {
        return true;
    }
}
