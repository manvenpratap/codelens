package com.tcs.bancs.RK;

import java.util.*;

/**
 * TCS BaNCS Enterprise Service Contract: ComplianceScreeningEngine
 */
public interface ComplianceScreeningEngine {
    MO_OUT_AmlScreening screenTransaction(MO_INP_AmlScreening req);
}
