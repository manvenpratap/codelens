package com.tcs.bancs.LN;

import java.util.*;

/**
 * TCS BaNCS Enterprise Service Contract: LoanLifecycleManager
 */
public interface LoanLifecycleManager {
    boolean approveLoan(String loanId);
    boolean disburseTranche(String loanId, double amt);
    boolean closeLoan(String loanId);
}
