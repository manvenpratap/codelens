package com.tcs.bancs.MS;

/**
 * TCS BaNCS Domain Enumeration: MessagePriority
 */
public enum MessagePriority {
    NORMAL,
    URGENT,
    SYSTEM_OVERRIDE,
    BATCH_BULK;

    public boolean isValid() {
        return true;
    }
}
