package com.tcs.bancs.AN;

import java.util.*;

/**
 * TCS BaNCS Enterprise Service Contract: RegulatoryExporter
 */
public interface RegulatoryExporter {
    String exportToXml(String reportId);
    boolean submitToRegulator(String reportId);
}
