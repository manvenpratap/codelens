package com.tcs.bancs.RK;

import java.util.*;

/**
 * TCS BaNCS Enterprise Service Contract: LimitRegistry
 */
public interface LimitRegistry {
    void registerLimit(String partyId, double amount);
    void releaseLimit(String partyId, double amount);
}
