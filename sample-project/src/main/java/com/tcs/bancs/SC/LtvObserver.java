package com.tcs.bancs.SC;

import java.util.*;

/**
 * TCS BaNCS Enterprise Service Contract: LtvObserver
 */
public interface LtvObserver {
    void onLtvBreached(String facilityId, double currentLtv, double maxLtv);
}
