package com.tcs.bancs.AN;

import java.util.*;

/**
 * TCS BaNCS Enterprise Service Contract: QuantitativeModel
 */
public interface QuantitativeModel {
    double evaluate(Map<String, Double> inputs);
    String getModelVersion();
}
