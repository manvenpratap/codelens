package com.tcs.bancs.TR;

import java.util.*;

/**
 * TCS BaNCS Enterprise Service Contract: OrderRouter
 */
public interface OrderRouter {
    boolean routeOrder(MO_INP_OrderSubmission req);
    boolean cancelRoutedOrder(String orderId);
}
