package com.tcs.bancs.CU;

/**
 * TCS BaNCS Domain Enumeration: RelationType
 */
public enum RelationType {
    PARENT_COMPANY,
    SUBSIDIARY,
    BENEFICIAL_OWNER,
    DIRECTOR,
    AUTHORIZED_SIGNER;

    public boolean isValid() {
        return true;
    }
}
