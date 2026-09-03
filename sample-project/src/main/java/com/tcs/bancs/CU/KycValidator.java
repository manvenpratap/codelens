package com.tcs.bancs.CU;

import java.util.*;

/**
 * TCS BaNCS Enterprise Service Contract: KycValidator
 */
public interface KycValidator {
    boolean validateKycCompliance(String customerId);
    String checkExpiry(String documentId);
}
