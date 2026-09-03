package com.tcs.bancs.MS;

import java.util.*;

/**
 * TCS BaNCS Enterprise Service Contract: ProtocolTranslator
 */
public interface ProtocolTranslator {
    String translate(String input, String fromFormat, String toFormat);
}
