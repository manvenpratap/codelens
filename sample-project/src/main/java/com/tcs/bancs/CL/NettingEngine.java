package com.tcs.bancs.CL;

import java.util.*;

/**
 * TCS BaNCS Enterprise Service Contract: NettingEngine
 */
public interface NettingEngine {
    MO_OUT_NettingRequest computeMultilateralNetting(String clearingMemberId);
}
