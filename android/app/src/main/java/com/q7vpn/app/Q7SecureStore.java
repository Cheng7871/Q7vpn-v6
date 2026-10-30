package com.q7vpn.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.ByteBuffer;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Encrypts saved node URIs with an AES-GCM key held by Android Keystore. */
public final class Q7SecureStore {
    private static final String PREFS = "q7vpn";
    private static final String ALIAS = "q7vpn_node_store_aes_gcm_v1";
    private static final String ENCRYPTED_SUFFIX = "_encrypted_v1";

    private Q7SecureStore() { }

    public static synchronized String read(Context context, String logicalKey) throws Exception {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String encrypted = prefs.getString(logicalKey + ENCRYPTED_SUFFIX, null);
        if (encrypted != null && !encrypted.isEmpty()) return decrypt(encrypted);

        // One-time migration from the old plain SharedPreferences entries.
        String legacy = prefs.getString(logicalKey, null);
        if (legacy == null) return "";
        write(context, logicalKey, legacy);
        return legacy;
    }

    public static synchronized void write(Context context, String logicalKey, String value) throws Exception {
        String encrypted = encrypt(value == null ? "" : value);
        SharedPreferences.Editor editor = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit();
        editor.putString(logicalKey + ENCRYPTED_SUFFIX, encrypted);
        editor.remove(logicalKey);
        if (!editor.commit()) throw new IllegalStateException("Could not commit encrypted preferences");
    }

    private static SecretKey getOrCreateKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        java.security.Key existing = keyStore.getKey(ALIAS, null);
        if (existing instanceof SecretKey) return (SecretKey) existing;

        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        KeyGenParameterSpec spec = new KeyGenParameterSpec.Builder(ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build();
        generator.init(spec);
        return generator.generateKey();
    }

    private static String encrypt(String plaintext) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey());
        byte[] iv = cipher.getIV();
        byte[] ciphertext = cipher.doFinal(plaintext.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        ByteBuffer packed = ByteBuffer.allocate(1 + iv.length + ciphertext.length);
        packed.put((byte) iv.length);
        packed.put(iv);
        packed.put(ciphertext);
        return Base64.encodeToString(packed.array(), Base64.NO_WRAP);
    }

    private static String decrypt(String encoded) throws Exception {
        byte[] packed = Base64.decode(encoded, Base64.NO_WRAP);
        if (packed.length < 1 + 12 + 16) throw new IllegalArgumentException("Encrypted node data is truncated");
        ByteBuffer buffer = ByteBuffer.wrap(packed);
        int ivLength = buffer.get() & 0xff;
        if (ivLength < 12 || ivLength > 16 || buffer.remaining() < ivLength + 16) {
            throw new IllegalArgumentException("Encrypted node data is invalid");
        }
        byte[] iv = new byte[ivLength];
        buffer.get(iv);
        byte[] ciphertext = new byte[buffer.remaining()];
        buffer.get(ciphertext);

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), new GCMParameterSpec(128, iv));
        byte[] plaintext = cipher.doFinal(ciphertext);
        return new String(plaintext, java.nio.charset.StandardCharsets.UTF_8);
    }
}
