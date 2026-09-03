package com.tcs.bancs.PM;

import java.util.*;

/**
 * TCS BaNCS Enterprise Service Contract: ClearingNetworkGateway
 */
public interface ClearingNetworkGateway {
    boolean dispatchToNetwork(String network, String paymentPayload);
}
