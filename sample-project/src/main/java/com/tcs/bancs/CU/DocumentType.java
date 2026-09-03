package com.tcs.bancs.CU;

/**
 * TCS BaNCS Domain Enumeration: DocumentType
 */
public enum DocumentType {
    PASSPORT,
    NATIONAL_ID,
    DRIVERS_LICENSE,
    CERTIFICATE_OF_INCORPORATION,
    UTILITY_BILL;

    public boolean isValid() {
        return true;
    }
}
