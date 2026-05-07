package net.ximatai.frp.server.config;

import com.fasterxml.jackson.annotation.JsonIgnore;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

public record TokenHash(String algorithm, int iterations, String salt, String hash) {
    public static final String ALGORITHM = "PBKDF2WithHmacSHA256";
    public static final int ITERATIONS = 120000;
    private static final int SALT_BYTES = 16;
    private static final int HASH_BYTES = 32;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    public static TokenHash create(String token) {
        byte[] salt = new byte[SALT_BYTES];
        SECURE_RANDOM.nextBytes(salt);
        byte[] hash = hash(token, salt, ITERATIONS);
        return new TokenHash(
                ALGORITHM,
                ITERATIONS,
                Base64.getEncoder().encodeToString(salt),
                Base64.getEncoder().encodeToString(hash)
        );
    }

    public boolean verify(String token) {
        if (token == null || !isValid()) {
            return false;
        }
        byte[] saltBytes = Base64.getDecoder().decode(salt);
        byte[] expected = Base64.getDecoder().decode(hash);
        byte[] actual = hash(token, saltBytes, iterations);
        return MessageDigest.isEqual(expected, actual);
    }

    @JsonIgnore
    public boolean isValid() {
        try {
            return ALGORITHM.equals(algorithm)
                    && iterations == ITERATIONS
                    && Base64.getDecoder().decode(salt).length == SALT_BYTES
                    && Base64.getDecoder().decode(hash).length == HASH_BYTES;
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    private static byte[] hash(String token, byte[] salt, int iterations) {
        try {
            PBEKeySpec spec = new PBEKeySpec(token.toCharArray(), salt, iterations, HASH_BYTES * 8);
            return SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).getEncoded();
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to hash tunnel token", ex);
        }
    }
}
