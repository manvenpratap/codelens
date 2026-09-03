package com.tcs.bancs.MS;

/**
 * TCS BaNCS Domain Enumeration: AckType
 */
public enum AckType {
    POSITIVE_ACK,
    NEGATIVE_NACK,
    TIMEOUT,
    SCHEMA_REJECT;

    public boolean isValid() {
        return true;
    }
}
