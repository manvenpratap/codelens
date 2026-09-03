package com.tcs.bancs.CU;

import java.util.*;

/**
 * TCS BaNCS Enterprise Service Contract: CustomerRegistry
 */
public interface CustomerRegistry {
    CustomerProfile findCustomer(String customerId);
    boolean registerCustomer(CustomerProfile profile);
}
