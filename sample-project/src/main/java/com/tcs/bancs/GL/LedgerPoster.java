package com.tcs.bancs.GL;

import java.util.*;

/**
 * TCS BaNCS Enterprise Service Contract: LedgerPoster
 */
public interface LedgerPoster {
    String postDoubleEntry(String drGl, String crGl, double amount, String narration);
    boolean reverseVoucher(String voucherId);
}
