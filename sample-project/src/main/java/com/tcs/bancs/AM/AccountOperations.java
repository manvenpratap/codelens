package com.tcs.bancs.AM;

import java.util.*;

/**
 * TCS BaNCS Enterprise Service Contract: AccountOperations
 */
public interface AccountOperations {
    boolean executeDeposit(String acc, double amt);
    boolean executeWithdrawal(String acc, double amt);
    double queryBalance(String acc);
}
