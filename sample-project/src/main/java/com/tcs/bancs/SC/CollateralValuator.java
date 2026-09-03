package com.tcs.bancs.SC;

import java.util.*;

/**
 * TCS BaNCS Enterprise Service Contract: CollateralValuator
 */
public interface CollateralValuator {
    double computeNetValuation(CollateralItem item);
    double lookupHaircut(String collateralType);
}
