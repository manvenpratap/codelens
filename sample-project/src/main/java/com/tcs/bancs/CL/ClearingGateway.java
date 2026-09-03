package com.tcs.bancs.CL;

import java.util.*;

/**
 * TCS BaNCS Enterprise Service Contract: ClearingGateway
 */
public interface ClearingGateway {
    boolean submitToCCP(String instructionId);
    String queryCCPStatus(String instructionId);
}
