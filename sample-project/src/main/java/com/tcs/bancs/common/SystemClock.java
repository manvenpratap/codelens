package com.tcs.bancs.common;

import java.time.LocalDate;
import java.time.LocalDateTime;

public class SystemClock {
    public static LocalDate getBusinessDate() { return LocalDate.now(); }
    public static LocalDateTime getSystemDateTime() { return LocalDateTime.now(); }
    public static long getEpochMillis() { return System.currentTimeMillis(); }
}
