package com.tcs.bancs.LN;

import java.util.*;

/**
 * TCS BaNCS Enterprise Service Contract: AmortizationEngine
 */
public interface AmortizationEngine {
    List<MO_AmortizationInstallment> generateSchedule(double principal, double rate, int months);
}
