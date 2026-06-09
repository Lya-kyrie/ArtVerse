package com.artverse.config;

import org.mindrot.jbcrypt.BCrypt;

/**
 * BCrypt 密码编码器（不依赖 Spring Security）。
 * <p>
 * 强度 10（与 Spring Security BCryptPasswordEncoder 默认一致）。
 */
public class BCryptPasswordEncoder {

    private final int strength;

    public BCryptPasswordEncoder() {
        this(10);
    }

    public BCryptPasswordEncoder(int strength) {
        this.strength = strength;
    }

    public String encode(String rawPassword) {
        return BCrypt.hashpw(rawPassword, BCrypt.gensalt(strength));
    }

    public boolean matches(String rawPassword, String hashedPassword) {
        if (rawPassword == null || hashedPassword == null) {
            return false;
        }
        try {
            return BCrypt.checkpw(rawPassword, hashedPassword);
        } catch (Exception e) {
            return false;
        }
    }
}
