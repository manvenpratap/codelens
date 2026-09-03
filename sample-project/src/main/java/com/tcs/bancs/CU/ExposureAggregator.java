package com.tcs.bancs.CU;

import java.util.*;

/**
 * TCS BaNCS Enterprise Service Contract: ExposureAggregator
 */
public interface ExposureAggregator {
    double rollupGroupExposure(String rootCustomerId);
}
