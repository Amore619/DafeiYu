package com.dafeiyu.chat;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Encrypts the API key with a hardware-backed key from Android Keystore. */
public final class SecureStore {
    private static final String ANDROID_KEYSTORE = "AndroidKeyStore";
    private static final String ALIAS = "dafeiyu_api_key_v1";
    private static final String PREF = "secure";
    private static final String KEY_VALUE = "api_key";
    // 多 Key 管理：密钥条目以 api_key_N 存放（独立加密），索引与当前选中项明文存放在普通 prefs。
    private static final String KEY_PREFIX = "api_key_";

    private final SharedPreferences sp;

    public SecureStore(Context context) {
        sp = context.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    public void put(String plain) {
        if (plain == null || plain.isEmpty()) {
            sp.edit().remove(KEY_VALUE).apply();
            return;
        }
        try {
            SecretKey key = key();
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key);
            byte[] iv = cipher.getIV();
            byte[] enc = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] all = new byte[iv.length + enc.length];
            System.arraycopy(iv, 0, all, 0, iv.length);
            System.arraycopy(enc, 0, all, iv.length, enc.length);
            sp.edit().putString(KEY_VALUE, Base64.encodeToString(all, Base64.NO_WRAP)).apply();
        } catch (Exception e) {
            // Do not silently persist a plaintext key. A failed write simply asks again next time.
            sp.edit().remove(KEY_VALUE).apply();
        }
    }

    public String get() {
        String packed = sp.getString(KEY_VALUE, "");
        if (packed == null || packed.isEmpty()) return "";
        try {
            byte[] all = Base64.decode(packed, Base64.NO_WRAP);
            if (all.length < 13) return "";
            byte[] iv = new byte[12];
            byte[] enc = new byte[all.length - 12];
            System.arraycopy(all, 0, iv, 0, 12);
            System.arraycopy(all, 12, enc, 0, enc.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, iv));
            return new String(cipher.doFinal(enc), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    public void clear() {
        sp.edit().remove(KEY_VALUE).apply();
    }

    // ------------------------------------------------------------ 多 Key 管理

    private String slotKey(int index) { return KEY_PREFIX + index; }

    /** 读取某个槽位的密钥（解密）。 */
    public String getKeyAt(int index) {
        String packed = sp.getString(slotKey(index), "");
        if (packed == null || packed.isEmpty()) return "";
        try {
            byte[] all = Base64.decode(packed, Base64.NO_WRAP);
            if (all.length < 13) return "";
            byte[] iv = new byte[12];
            byte[] enc = new byte[all.length - 12];
            System.arraycopy(all, 0, iv, 0, 12);
            System.arraycopy(all, 12, enc, 0, enc.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, iv));
            return new String(cipher.doFinal(enc), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    /** 写入某个槽位的密钥（加密）；plain 为空则删除该槽位。 */
    public void putKeyAt(int index, String plain) {
        if (plain == null || plain.isEmpty()) {
            sp.edit().remove(slotKey(index)).apply();
            return;
        }
        try {
            SecretKey key = key();
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key);
            byte[] iv = cipher.getIV();
            byte[] enc = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] all = new byte[iv.length + enc.length];
            System.arraycopy(iv, 0, all, 0, iv.length);
            System.arraycopy(enc, 0, all, iv.length, enc.length);
            sp.edit().putString(slotKey(index), Base64.encodeToString(all, Base64.NO_WRAP)).apply();
        } catch (Exception e) {
            sp.edit().remove(slotKey(index)).apply();
        }
    }

    public void removeKeyAt(int index) { sp.edit().remove(slotKey(index)).apply(); }

    private SecretKey key() throws Exception {
        KeyStore ks = KeyStore.getInstance(ANDROID_KEYSTORE);
        ks.load(null);
        if (ks.containsAlias(ALIAS)) {
            return (SecretKey) ks.getKey(ALIAS, null);
        }
        try {
            return generate(256);
        } catch (Exception e) {
            try { ks.deleteEntry(ALIAS); } catch (Exception ignored) { }
            return generate(128);
        }
    }

    private SecretKey generate(int bits) throws Exception {
        KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE);
        KeyGenParameterSpec spec = new KeyGenParameterSpec.Builder(
                ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(bits)
                .setRandomizedEncryptionRequired(true)
                .build();
        kg.init(spec);
        return kg.generateKey();
    }
}
