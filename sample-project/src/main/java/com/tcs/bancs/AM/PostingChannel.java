package com.tcs.bancs.AM;

/**
 * TCS BaNCS Domain Enumeration: PostingChannel
 */
public enum PostingChannel {
    BRANCH,
    INTERNET_BANKING,
    MOBILE_APP,
    ATM,
    OPEN_API;

    public boolean isValid() {
        return true;
    }
}
