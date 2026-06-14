package com.icusu.sivan.infra.shared.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * API 密钥加解密组件。
 * <p>PBKDF2-SHA256 密钥派生 + AES-256-GCM 认证加密。
 * GCM 提供机密性 + 完整性校验，PBKDF2 慢派生抗暴力破解。
 * Repository Adapter 层调用，Service/Domain 层零感知。</p>
 */
@Component
public class ApiKeyEncryptor {

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final String KEY_DERIVATION = "PBKDF2WithHmacSHA256";
    private static final int KEY_SIZE = 256;
    private static final int ITERATIONS = 65536;
    private static final int GCM_IV_LEN = 12;   // GCM 推荐 96-bit IV
    private static final int GCM_TAG_LEN = 128;  // 16 字节认证标签

    private final byte[] salt;

    private final SecretKey secretKey;

    public ApiKeyEncryptor(@Value("${sivan.encryption.master-key}") String masterKey) {
        if (masterKey == null || masterKey.isBlank()) {
            throw new IllegalArgumentException("sivan.encryption.master-key 未配置");
        }
        this.salt = "sivan-api-key-v1".getBytes(StandardCharsets.UTF_8);
        this.secretKey = deriveKey(masterKey);
    }

    /**
     * PBKDF2-SHA256 派生 AES-256 密钥。
     */
    private SecretKey deriveKey(String password) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, ITERATIONS, KEY_SIZE);
            SecretKeyFactory factory = SecretKeyFactory.getInstance(KEY_DERIVATION);
            byte[] keyBytes = factory.generateSecret(spec).getEncoded();
            return new SecretKeySpec(keyBytes, "AES");
        } catch (Exception e) {
            throw new RuntimeException("密钥派生失败", e);
        }
    }

    /**
     * 加密：输出格式 = Base64(12-byte IV + 密文 + 16-byte GCM tag)
     */
    public String encrypt(String plain) {
        if (plain == null || plain.isBlank()) return plain;
        try {
            byte[] iv = new byte[GCM_IV_LEN];
            SecureRandom.getInstanceStrong().nextBytes(iv);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_LEN, iv));

            byte[] ciphertext = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));

            // IV + 密文(含tag) → Base64
            ByteBuffer buf = ByteBuffer.allocate(iv.length + ciphertext.length);
            buf.put(iv);
            buf.put(ciphertext);
            return Base64.getEncoder().encodeToString(buf.array());
        } catch (Exception e) {
            throw new RuntimeException("API key 加密失败", e);
        }
    }

    /**
     * 解密：输入格式 = Base64(12-byte IV + 密文 + 16-byte GCM tag)
     */
    public String decrypt(String stored) {
        if (stored == null || stored.isBlank()) return stored;
        try {
            byte[] decoded = Base64.getDecoder().decode(stored);

            ByteBuffer buf = ByteBuffer.wrap(decoded);
            byte[] iv = new byte[GCM_IV_LEN];
            buf.get(iv);
            byte[] ciphertext = new byte[buf.remaining()];
            buf.get(ciphertext);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_LEN, iv));

            byte[] plain = cipher.doFinal(ciphertext);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("API key 解密失败（master-key 不匹配或数据已损坏）", e);
        }
    }
}
