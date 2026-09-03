package com.tcs.bancs.SC;

import java.util.*;

/**
 * TCS BaNCS Enterprise Service Contract: MarginCallEngine
 */
public interface MarginCallEngine {
    MO_OUT_MarginCallIssue triggerMarginCall(String facilityId, double deficit);
}
