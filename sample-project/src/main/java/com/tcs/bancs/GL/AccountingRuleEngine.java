package com.tcs.bancs.GL;

import java.util.*;

/**
 * TCS BaNCS Enterprise Service Contract: AccountingRuleEngine
 */
public interface AccountingRuleEngine {
    boolean validateGlCompatibility(String drGl, String crGl);
    String resolveDefaultGl(String eventType);
}
