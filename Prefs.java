package com.dafeiyu.chat;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;

/** Central app settings. The API key is stored separately through {@link SecureStore}. */
public final class Prefs {
    private static final String FILE = "settings";
    private static Prefs instance;

    private final SharedPreferences sp;
    private final SecureStore secure;

    private Prefs(Context context) {
        sp = context.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
        secure = new SecureStore(context.getApplicationContext());
    }

    public static synchronized Prefs get(Context context) {
        if (instance == null) instance = new Prefs(context);
        return instance;
    }

    // ------------------------------------------------------- 多 Key / 多模型配置

    private static final String P_KEY_META = "key_profiles";
    private static final String P_KEY_INDEX = "key_index";

    /** key profile 元数据：name / base / model（不含密钥，密钥按索引加密存储）。 */
    private JSONArray profiles() {
        String raw = sp.getString(P_KEY_META, "");
        if (raw != null && !raw.isEmpty()) {
            try {
                JSONArray a = new JSONArray(raw);
                return a;
            } catch (Exception ignore) { }
        }
        return new JSONArray();
    }

    private void saveProfiles(JSONArray a) {
        sp.edit().putString(P_KEY_META, a.toString()).apply();
    }

    public int keyCount() { return profiles().length(); }

    public int currentKeyIndex() {
        int i = sp.getInt(P_KEY_INDEX, 0);
        int n = keyCount();
        if (n == 0) return -1;
        if (i < 0 || i >= n) i = 0;
        return i;
    }

    public void setCurrentKeyIndex(int i) { sp.edit().putInt(P_KEY_INDEX, i).apply(); }

    public String keyName(int i) {
        JSONArray a = profiles();
        if (i < 0 || i >= a.length()) return "API Key";
        JSONObject o = a.optJSONObject(i);
        String n = o == null ? "" : o.optString("name", "");
        return n == null || n.isEmpty() ? "API Key " + (i + 1) : n;
    }

    public String keyBaseAt(int i) {
        JSONArray a = profiles();
        if (i < 0 || i >= a.length()) return "https://api.deepseek.com";
        JSONObject o = a.optJSONObject(i);
        String b = o == null ? "" : o.optString("base", "");
        return b == null || b.isEmpty() ? "https://api.deepseek.com" : normalizeBase(b);
    }

    public String keyModelAt(int i) {
        JSONArray a = profiles();
        if (i < 0 || i >= a.length()) return "deepseek-flash";
        JSONObject o = a.optJSONObject(i);
        String m = o == null ? "" : o.optString("model", "");
        return m == null || m.isEmpty() ? "deepseek-flash" : m;
    }

    /** 读取指定槽位的密钥明文（用于编辑框回显）。 */
    public String apiKeyIfIndex(int i) {
        if (i < 0) return apiKey();
        return secure.getKeyAt(i);
    }

    private static String normalizeBase(String v) {
        if (v == null) return "https://api.deepseek.com";
        v = v.trim();
        if (v.isEmpty()) v = "https://api.deepseek.com";
        while (v.endsWith("/")) v = v.substring(0, v.length() - 1);
        return v;
    }

    /** 新增一个 key 配置，返回新索引。 */
    public int addKey(String name, String key, String base, String model) {
        int idx = keyCount();
        JSONArray a = profiles();
        JSONObject o = new JSONObject();
        try {
            o.put("name", name == null || name.trim().isEmpty() ? "API Key " + (idx + 1) : name.trim());
            o.put("base", normalizeBase(base));
            o.put("model", model == null || model.trim().isEmpty() ? "deepseek-flash" : model.trim());
        } catch (Exception ignore) { }
        a.put(o);
        saveProfiles(a);
        secure.putKeyAt(idx, key == null ? "" : key.trim());
        setCurrentKeyIndex(idx);
        return idx;
    }

    /** 更新某个 key 配置（索引已存在时）。 */
    public void updateKey(int i, String name, String key, String base, String model) {
        JSONArray a = profiles();
        if (i < 0 || i >= a.length()) return;
        JSONObject o = a.optJSONObject(i);
        if (o == null) o = new JSONObject();
        try {
            o.put("name", name == null || name.trim().isEmpty() ? "API Key " + (i + 1) : name.trim());
            o.put("base", normalizeBase(base));
            o.put("model", model == null || model.trim().isEmpty() ? "deepseek-flash" : model.trim());
        } catch (Exception ignore) { }
        try { a.put(i, o); } catch (Exception ignore) { }
        saveProfiles(a);
        secure.putKeyAt(i, key == null ? "" : key.trim());
    }

    /** 删除某个 key 配置，后续槽位前移。 */
    public void removeKey(int i) {
        JSONArray a = profiles();
        if (i < 0 || i >= a.length()) return;
        int oldCount = a.length();
        // 先读出每个槽位的明文密钥，重排后写回，保持元数据与密钥槽位一致。
        String[] keys = new String[oldCount];
        for (int k = 0; k < oldCount; k++) keys[k] = secure.getKeyAt(k);
        JSONArray next = new JSONArray();
        ArrayList<String> keptKeys = new ArrayList<>();
        for (int k = 0; k < oldCount; k++) {
            if (k == i) continue;
            next.put(a.opt(k));
            keptKeys.add(keys[k]);
        }
        // 清掉尾部多余槽位
        for (int k = next.length(); k < oldCount; k++) secure.removeKeyAt(k);
        saveProfiles(next);
        for (int k = 0; k < next.length(); k++) secure.putKeyAt(k, keptKeys.get(k));
        int n = next.length();
        int cur = currentKeyIndex();
        if (n == 0) setCurrentKeyIndex(-1);
        else {
            int newCur;
            if (cur == i) newCur = Math.min(i, n - 1);
            else if (cur > i) newCur = cur - 1;
            else newCur = cur;
            setCurrentKeyIndex(Math.max(0, Math.min(newCur, n - 1)));
        }
    }

    // ------------------------------------------------------- 当前生效配置

    public String apiKey() {
        int i = currentKeyIndex();
        if (i < 0) {
            // 兼容旧版：没有 profile 时读旧槽位
            return secure.get();
        }
        return secure.getKeyAt(i);
    }

    public void setApiKey(String v) {
        int i = currentKeyIndex();
        if (i < 0) {
            // 兼容旧版：首次设置时创建默认 profile
            if (v == null || v.trim().isEmpty()) { secure.put(""); return; }
            addKey("DeepSeek", v, "https://api.deepseek.com", "deepseek-flash");
        } else {
            secure.putKeyAt(i, v == null ? "" : v.trim());
        }
    }

    public boolean hasApiKey() { return !apiKey().isEmpty(); }

    public String baseUrl() {
        int i = currentKeyIndex();
        if (i < 0) return normalizeBase(sp.getString("base_url", "https://api.deepseek.com"));
        return keyBaseAt(i);
    }

    public void setBaseUrl(String v) {
        int i = currentKeyIndex();
        if (i < 0) { sp.edit().putString("base_url", v == null ? "" : v.trim()).apply(); return; }
        JSONArray a = profiles();
        if (i >= a.length()) return;
        JSONObject o = a.optJSONObject(i);
        if (o == null) o = new JSONObject();
        try { o.put("base", normalizeBase(v)); } catch (Exception ignore) { }
        try { a.put(i, o); } catch (Exception ignore) { }
        saveProfiles(a);
    }

    public String model() {
        int i = currentKeyIndex();
        if (i < 0) return sp.getString("model", "deepseek-flash");
        return keyModelAt(i);
    }

    public void setModel(String v) {
        int i = currentKeyIndex();
        if (i < 0) { sp.edit().putString("model", v).apply(); return; }
        JSONArray a = profiles();
        if (i >= a.length()) return;
        JSONObject o = a.optJSONObject(i);
        if (o == null) o = new JSONObject();
        try { o.put("model", v); } catch (Exception ignore) { }
        try { a.put(i, o); } catch (Exception ignore) { }
        saveProfiles(a);
    }

    public boolean thinking() { return sp.getBoolean("thinking", true); }
    public void setThinking(boolean v) { sp.edit().putBoolean("thinking", v).apply(); }

    public boolean search() { return sp.getBoolean("search", false); }
    public void setSearch(boolean v) { sp.edit().putBoolean("search", v).apply(); }

    public String effort() { return sp.getString("effort", "high"); }
    public void setEffort(String v) { sp.edit().putString("effort", v).apply(); }

    public int maxTokens() { return sp.getInt("max_tokens", 8192); }
    public void setMaxTokens(int v) { sp.edit().putInt("max_tokens", v).apply(); }

    public int contextLimit() { return sp.getInt("context_limit", 1000000); }
    public void setContextLimit(int v) { sp.edit().putInt("context_limit", v).apply(); }

    public int warnThreshold() { return sp.getInt("warn_threshold", 10); }
    public void setWarnThreshold(int v) { sp.edit().putInt("warn_threshold", v).apply(); }

    public boolean autoTitle() { return sp.getBoolean("auto_title", true); }
    public void setAutoTitle(boolean v) { sp.edit().putBoolean("auto_title", v).apply(); }

    public boolean haptics() { return sp.getBoolean("haptics", true); }
    public void setHaptics(boolean v) { sp.edit().putBoolean("haptics", v).apply(); }

    public String lastConvId() { return sp.getString("last_conv", ""); }
    public void setLastConvId(String v) { sp.edit().putString("last_conv", v == null ? "" : v).apply(); }

    public boolean onboardingDone() { return sp.getBoolean("onboarding_done", false); }
    public void setOnboardingDone(boolean v) { sp.edit().putBoolean("onboarding_done", v).apply(); }

    // ------------------------------------------------------- 自动备份（本地，卸载不删）

    /** 自动备份到手机公共 Download 目录，默认开启。 */
    public boolean autoBackup() { return sp.getBoolean("auto_backup", true); }
    public void setAutoBackup(boolean v) { sp.edit().putBoolean("auto_backup", v).apply(); }

    /** 上次自动备份的时间戳，用于节流。 */
    public long lastAutoBackupAt() { return sp.getLong("last_auto_backup_at", 0L); }
    public void setLastAutoBackupAt(long v) { sp.edit().putLong("last_auto_backup_at", v).apply(); }

    /** 已提示过「检测到备份可恢复」的标记，避免每次启动重复弹。 */
    public boolean restorePromptShown() { return sp.getBoolean("restore_prompt_shown", false); }
    public void setRestorePromptShown(boolean v) { sp.edit().putBoolean("restore_prompt_shown", v).apply(); }

    public boolean warningDialogShown(String convId) {
        return sp.getBoolean("warn_dialog_" + convId, false);
    }
    public void setWarningDialogShown(String convId, boolean v) {
        sp.edit().putBoolean("warn_dialog_" + convId, v).apply();
    }
}
