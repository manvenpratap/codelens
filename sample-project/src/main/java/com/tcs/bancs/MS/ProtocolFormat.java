package com.tcs.bancs.MS;

/**
 * TCS BaNCS Domain Enumeration: ProtocolFormat
 */
public enum ProtocolFormat {
    SWIFT_FIN,
    ISO_20022_XML,
    FIX_5_0,
    JSON_API,
    BAI2;

    public boolean isValid() {
        return true;
    }
}
