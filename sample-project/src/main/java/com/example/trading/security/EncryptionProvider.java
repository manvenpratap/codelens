package com.example.trading.security;

import java.util.Base64;

public class EncryptionProvider {
    public String encrypt(String raw) {
        return Base64.getEncoder().encodeToString(raw.getBytes());
    }

    public String decrypt(String encoded) {
        return new String(Base64.getDecoder().decode(encoded));
    }
}
