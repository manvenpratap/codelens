package com.tcs.bancs.TR;

import java.util.*;

/**
 * TCS BaNCS Enterprise Service Contract: PriceFeedSubscriber
 */
public interface PriceFeedSubscriber {
    void onPriceUpdate(String symbol, double price);
}
