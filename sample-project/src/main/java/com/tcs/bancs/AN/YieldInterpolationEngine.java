package com.tcs.bancs.AN;

import java.util.*;

/**
 * TCS BaNCS Enterprise Service Contract: YieldInterpolationEngine
 */
public interface YieldInterpolationEngine {
    double interpolate(int days, String currency);
}
