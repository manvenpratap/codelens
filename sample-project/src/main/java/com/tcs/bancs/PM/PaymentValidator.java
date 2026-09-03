package com.tcs.bancs.PM;

import java.util.*;

/**
 * TCS BaNCS Enterprise Service Contract: PaymentValidator
 */
public interface PaymentValidator {
    boolean validatePaymentRequest(MO_INP_PaymentInitiation req);
}
