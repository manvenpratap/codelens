package com.tcs.bancs.CL;

import java.util.*;

/**
 * TCS BaNCS Enterprise Service Contract: SettlementProcessor
 */
public interface SettlementProcessor {
    boolean executeDVP(String instructionId);
    boolean cancelInstruction(String instructionId);
}
