package com.dafeiyu.chat;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.net.ssl.HttpsURLConnection;

/**
 * Direct DeepSeek API client. Uses only the platform HTTP stack so the APK stays small
 * and works without Google services.
 */
public class ApiClient {
    private static final String TAG = "ApiClient";
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService pool = Executors.newFixedThreadPool(3);
    private volatile HttpsURLConnection chatConn;
    private volatile boolean cancelled;

    public interface ChatCallback {
        default void onStarted() { }
        default void onReasoning(String delta) { }
        default void onContent(String delta) { }
        default void onToolEvent(JSONObject event) { }
        default void onTitle(String title) { }
        default void onUsage(int promptTokens, int completionTokens) { }
        default void onFinal(JSONArray serverToolCalls, JSONArray annotations, String finishReason) { }
        default void onComplete() { }
        default void onError(String message, int httpCode, boolean contextError) { }
        default void onCancelled() { }
    }

    public interface UploadCallback {
        default void onProgress(int percent) { }
        default void onDone(String fileId) { }
        default void onError(String message) { }
    }

    public interface SimpleCallback {
        void onResult(boolean ok, String message);
    }

    public void cancelChat() {
        cancelled = true;
        HttpsURLConnection c = chatConn;
        if (c != null) {
            try { c.disconnect(); } catch (Exception ignored) { }
        }
        chatConn = null;
    }

    public void sendChat(Conv conv, Prefs p, ChatCallback cb) {
        cancelled = false;
        final String base = p.baseUrl();
        final String key = p.apiKey();
        if (key == null || key.trim().isEmpty()) {
            post(() -> cb.onError("还没有设置 API Key。请先在设置中填入 DeepSeek API Key。", 401, false));
            return;
        }
        if (!base.startsWith("https://") && !base.startsWith("http://")) {
            post(() -> cb.onError("API 地址必须以 http:// 或 https:// 开头。", 400, false));
            return;
        }
        final JSONObject body;
        try {
            body = buildBody(conv, p);
        } catch (Exception e) {
            post(() -> cb.onError("请求构造失败：" + e.getMessage(), 0, false));
            return;
        }
        pool.execute(() -> stream(base, key, body, cb));
    }

    public void testConnection(Prefs p, SimpleCallback cb) {
        pool.execute(() -> {
            HttpsURLConnection c = null;
            try {
                URL url = new URL(p.baseUrl() + "/models");
                c = (HttpsURLConnection) url.openConnection();
                c.setConnectTimeout(15000);
                c.setReadTimeout(20000);
                c.setRequestProperty("Authorization", "Bearer " + p.apiKey());
                c.setRequestProperty("Accept", "application/json");
                int code = c.getResponseCode();
                String msg = "";
                if (code == 200) {
                    msg = "连接成功，API Key 有效";
                } else {
                    InputStream es = c.getErrorStream();
                    String text = es == null ? "" : readAll(es);
                    msg = extractError(text, code);
                }
                final String fm = msg;
                final boolean ok = code == 200;
                post(() -> cb.onResult(ok, fm));
            } catch (Exception e) {
                post(() -> cb.onResult(false, "连接失败：" + friendlyNetwork(e)));
            } finally {
                if (c != null) c.disconnect();
            }
        });
    }

    public void fetchBalance(Prefs p, SimpleCallback cb) {
        pool.execute(() -> {
            HttpsURLConnection c = null;
            try {
                URL url = new URL(p.baseUrl() + "/user/balance");
                c = (HttpsURLConnection) url.openConnection();
                c.setConnectTimeout(15000);
                c.setReadTimeout(20000);
                c.setRequestProperty("Authorization", "Bearer " + p.apiKey());
                int code = c.getResponseCode();
                String text;
                try (InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream()) {
                    text = in == null ? "" : readAll(in);
                }
                if (code != 200) {
                    final String msg = extractError(text, code);
                    post(() -> cb.onResult(false, msg));
                    return;
                }
                JSONObject o = new JSONObject(text);
                JSONArray arr = o.optJSONArray("balance_infos");
                String msg = "余额获取成功";
                if (arr != null && arr.length() > 0) {
                    JSONObject b = arr.optJSONObject(0);
                    if (b != null) msg = b.optString("currency", "CNY") + " " + b.optString("total_balance", "0.00");
                }
                final String fm = msg;
                post(() -> cb.onResult(true, fm));
            } catch (Exception e) {
                post(() -> cb.onResult(false, friendlyNetwork(e)));
            } finally {
                if (c != null) c.disconnect();
            }
        });
    }

    public void uploadFile(File file, String displayName, String mime, String purpose,
                           Prefs prefs, UploadCallback cb) {
        final String base = prefs.baseUrl();
        final String key = prefs.apiKey();
        pool.execute(() -> {
            HttpsURLConnection c = null;
            try {
                if (key == null || key.trim().isEmpty()) {
                    post(() -> cb.onError("还没有设置 API Key"));
                    return;
                }
                if (!base.startsWith("https://") && !base.startsWith("http://")) {
                    post(() -> cb.onError("API 地址必须以 http:// 或 https:// 开头"));
                    return;
                }
                String boundary = "----DafeiYu" + UUID.randomUUID().toString().replace("-", "");
                String ext = FileUtils.ext(displayName);
                String safeName = "upload" + (ext.isEmpty() ? "" : "." + ext);
                byte[] head = ("--" + boundary + "\r\n"
                        + "Content-Disposition: form-data; name=\"purpose\"\r\n\r\n"
                        + purpose + "\r\n"
                        + "--" + boundary + "\r\n"
                        + "Content-Disposition: form-data; name=\"file\"; filename=\"" + safeName + "\"\r\n"
                        + "Content-Type: " + (mime == null ? "application/octet-stream" : mime) + "\r\n\r\n")
                        .getBytes(StandardCharsets.UTF_8);
                byte[] tail = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
                long total = head.length + file.length() + tail.length;

                URL url = new URL(base + "/files");
                c = (HttpsURLConnection) url.openConnection();
                c.setRequestMethod("POST");
                c.setConnectTimeout(30000);
                c.setReadTimeout(360000);
                c.setDoOutput(true);
                c.setFixedLengthStreamingMode(total);
                c.setRequestProperty("Authorization", "Bearer " + key);
                c.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
                c.setRequestProperty("Accept", "application/json");
                c.setRequestProperty("User-Agent", "DafeiYu/1.0 (Android)");

                try (OutputStream out = c.getOutputStream(); FileInputStream fis = new FileInputStream(file)) {
                    out.write(head);
                    byte[] buf = new byte[64 * 1024];
                    long sent = 0;
                    int n;
                    int lastPct = -1;
                    while ((n = fis.read(buf)) > 0) {
                        out.write(buf, 0, n);
                        sent += n;
                        int pct = (int) (sent * 100 / Math.max(1, file.length()));
                        if (pct != lastPct) {
                            lastPct = pct;
                            final int fp = pct;
                            post(() -> cb.onProgress(fp));
                        }
                    }
                    out.write(tail);
                }

                int code = c.getResponseCode();
                String text;
                try (InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream()) {
                    text = in == null ? "" : readAll(in);
                }
                if (code != 200) {
                    final String msg = extractError(text, code);
                    post(() -> cb.onError(msg));
                    return;
                }
                JSONObject o = new JSONObject(text);
                String id = o.optString("id", "");
                if (id.isEmpty()) {
                    post(() -> cb.onError("文件上传失败：服务端没有返回文件 ID"));
                    return;
                }
                post(() -> cb.onDone(id));
            } catch (Exception e) {
                final String msg = friendlyNetwork(e);
                post(() -> cb.onError("上传失败：" + msg));
            } finally {
                if (c != null) c.disconnect();
            }
        });
    }

    private JSONObject buildBody(Conv c, Prefs p) throws Exception {
        JSONObject body = new JSONObject();
        body.put("model", p.model());
        body.put("stream", true);
        body.put("max_tokens", p.maxTokens());

        // DeepSeek 官方专有参数只在官方端点发送；其他 OpenAI 兼容端点收到未知字段会报错。
        boolean deepseek = isDeepSeek(p.baseUrl());
        boolean tools = false;
        if (deepseek) {
            body.put("thinking", new JSONObject().put("type", p.thinking() ? "enabled" : "disabled"));
            if (p.thinking()) body.put("reasoning_effort", p.effort());
            tools = p.search();
            if (tools) {
                JSONArray arr = new JSONArray();
                arr.put(new JSONObject().put("type", "deepseek_web_search"));
                arr.put(new JSONObject().put("type", "deepseek_read_url"));
                body.put("tools", arr);
            }
            if (p.autoTitle() && (c.title == null || c.title.trim().isEmpty()) && c.turns() == 0) {
                body.put("title", new JSONObject().put("enabled", true));
            }
        }
        body.put("messages", buildMessages(c, p, tools));
        return body;
    }

    /** 判断是否为 DeepSeek 官方 API 端点。 */
    public static boolean isDeepSeek(String base) {
        if (base == null) return false;
        String b = base.toLowerCase(Locale.ROOT);
        return b.contains("deepseek.com") || b.contains("deepseek.ai");
    }

    private JSONArray buildMessages(Conv c, Prefs p, boolean tools) throws Exception {
        JSONArray arr = new JSONArray();
        JSONObject sys = new JSONObject();
        sys.put("role", "system");
        String date = new SimpleDateFormat("yyyy年M月d日", Locale.CHINA).format(new Date());
        sys.put("content", "你是「大肥鱼」，一个基于 DeepSeek 的中文 AI 助手。你回答准确、清晰、友好，"
                + "善于使用 Markdown。除非用户要求其他语言，否则用简体中文回答。当前日期：" + date + "。");
        arr.put(sys);

        for (Msg m : c.messages) {
            if (m.isAssistant() && m.status == Msg.STATUS_STREAMING) continue;
            if (m.isAssistant() && !m.hasVisibleContent() && m.status == Msg.STATUS_ERROR) continue;
            if (m.isUser()) {
                JSONObject o = new JSONObject();
                o.put("role", "user");
                if (m.attachments == null || m.attachments.isEmpty()) {
                    o.put("content", m.content == null ? "" : m.content);
                } else {
                    JSONArray content = new JSONArray();
                    String text = m.content == null ? "" : m.content.trim();
                    if (text.isEmpty()) text = "请阅读并分析我上传的内容。";
                    content.put(new JSONObject().put("type", "text").put("text", text));
                    for (Att a : m.attachments) {
                        if (a.fileId != null && !a.fileId.isEmpty()) {
                            content.put(new JSONObject().put("type", "file").put("file_id", a.fileId));
                        } else {
                            content.put(new JSONObject().put("type", "text")
                                    .put("text", "[附件：" + a.name + "（未能上传，仅保留文件名）]"));
                        }
                    }
                    o.put("content", content);
                }
                arr.put(o);
            } else if (m.isAssistant()) {
                JSONObject o = new JSONObject();
                o.put("role", "assistant");
                o.put("content", m.content == null ? "" : m.content);
                if (tools || m.search) {
                    o.put("reasoning_content", m.reasoning == null ? "" : m.reasoning);
                }
                if (m.serverToolCalls != null && m.serverToolCalls.length() > 2) {
                    try {
                        JSONArray calls = new JSONArray(m.serverToolCalls);
                        if (calls.length() > 0) o.put("server_tool_calls", calls);
                    } catch (Exception ignored) { }
                }
                arr.put(o);
            }
        }
        return arr;
    }

    private void stream(String base, String key, JSONObject body, ChatCallback cb) {
        HttpsURLConnection c = null;
        try {
            URL url = new URL(base + "/chat/completions");
            c = (HttpsURLConnection) url.openConnection();
            chatConn = c;
            c.setRequestMethod("POST");
            c.setConnectTimeout(30000);
            c.setReadTimeout(600000);
            c.setDoOutput(true);
            c.setRequestProperty("Authorization", "Bearer " + key);
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            c.setRequestProperty("Accept", "text/event-stream");
            c.setRequestProperty("Accept-Encoding", "identity");
            c.setRequestProperty("User-Agent", "DafeiYu/1.0 (Android)");
            byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
            c.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream out = c.getOutputStream()) {
                out.write(bytes);
            }
            if (cancelled) {
                post(cb::onCancelled);
                return;
            }
            int code = c.getResponseCode();
            if (code != 200) {
                String text;
                try (InputStream in = c.getErrorStream()) {
                    text = in == null ? "" : readAll(in);
                }
                String msg = extractError(text, code);
                boolean context = isContextError(msg, code);
                post(() -> cb.onError(msg, code, context));
                return;
            }
            post(cb::onStarted);
            String contentType = c.getContentType();
            if (contentType != null && contentType.contains("application/json")) {
                String text;
                try (InputStream in = c.getInputStream()) {
                    text = readAll(in);
                }
                if (!parseWhole(text, cb)) return;
                post(cb::onComplete);
                return;
            }

            boolean[] finished = {false};
            int[] usage = {0, 0};
            JSONArray[] calls = {null};
            JSONArray[] annotations = {null};
            String[] finish = {""};
            boolean[] gotError = {false};
            String[] streamError = {""};

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8), 16 * 1024)) {
                StringBuilder data = new StringBuilder();
                String line;
                while (!cancelled && (line = reader.readLine()) != null) {
                    if (line.startsWith("data:")) {
                        String part = line.substring(5).trim();
                        if (data.length() > 0) data.append('\n');
                        data.append(part);
                    } else if (line.isEmpty()) {
                        if (data.length() > 0) {
                            boolean done = handleEvent(data.toString(), cb, usage, calls, annotations, finish, streamError);
                            data.setLength(0);
                            if (done) break;
                        }
                    }
                }
                if (!cancelled && data.length() > 0) {
                    handleEvent(data.toString(), cb, usage, calls, annotations, finish, streamError);
                }
            }

            if (cancelled) {
                post(cb::onCancelled);
                return;
            }
            if (streamError[0] != null && !streamError[0].isEmpty()) {
                final String msg = streamError[0];
                final boolean context = isContextError(msg, 400);
                post(() -> cb.onError(msg, 400, context));
                return;
            }
            final int fp = usage[0], fc = usage[1];
            final JSONArray fCalls = calls[0], fAnn = annotations[0];
            final String fFinish = finish[0];
            post(() -> {
                if (fp > 0 || fc > 0) cb.onUsage(fp, fc);
                cb.onFinal(fCalls == null ? new JSONArray() : fCalls,
                        fAnn == null ? new JSONArray() : fAnn, fFinish);
                cb.onComplete();
            });
        } catch (Exception e) {
            if (cancelled) {
                post(cb::onCancelled);
            } else {
                final String msg = friendlyNetwork(e);
                post(() -> cb.onError(msg, 0, false));
            }
        } finally {
            if (c != null) c.disconnect();
            chatConn = null;
        }
    }

    private boolean handleEvent(String raw, ChatCallback cb, int[] usage, JSONArray[] calls,
                                JSONArray[] annotations, String[] finish, String[] streamError) {
        if ("[DONE]".equals(raw.trim())) return true;
        JSONObject root;
        try {
            root = new JSONObject(raw);
        } catch (Exception e) {
            return false;
        }
        JSONObject err = root.optJSONObject("error");
        if (err != null) {
            streamError[0] = err.optString("message", "生成失败，请重试");
            return true;
        }
        JSONObject u = root.optJSONObject("usage");
        if (u != null) readUsage(u, usage);
        JSONArray choices = root.optJSONArray("choices");
        if (choices == null || choices.length() == 0) return false;
        JSONObject choice = choices.optJSONObject(0);
        if (choice == null) return false;
        JSONObject u2 = choice.optJSONObject("usage");
        if (u2 != null) readUsage(u2, usage);
        JSONObject delta = choice.optJSONObject("delta");
        if (delta != null) {
            String r = delta.optString("reasoning_content", "");
            if (!r.isEmpty()) cb.onReasoning(r);
            String content = delta.optString("content", "");
            if (!content.isEmpty()) cb.onContent(content);
            JSONArray stc = delta.optJSONArray("server_tool_calls");
            if (stc != null) calls[0] = stc;
            JSONArray ann = delta.optJSONArray("annotations");
            if (ann != null) annotations[0] = ann;
            String title = delta.optString("title", "");
            if (!title.isEmpty()) cb.onTitle(title);
            JSONObject dev = delta.optJSONObject("server_tool_event");
            if (dev != null) cb.onToolEvent(dev);
        }
        JSONObject ev = choice.optJSONObject("server_tool_event");
        if (ev != null) cb.onToolEvent(ev);
        String fr = choice.optString("finish_reason", "");
        if (!fr.isEmpty() && !"null".equals(fr)) finish[0] = fr;
        return false;
    }

    private boolean parseWhole(String text, ChatCallback cb) {
        try {
            JSONObject o = new JSONObject(text);
            JSONObject err = o.optJSONObject("error");
            if (err != null) {
                String msg = err.optString("message", "请求失败");
                cb.onError(msg, 400, isContextError(msg, 400));
                return false;
            }
            JSONArray choices = o.optJSONArray("choices");
            if (choices != null && choices.length() > 0) {
                JSONObject msg = choices.optJSONObject(0).optJSONObject("message");
                if (msg != null) {
                    String r = msg.optString("reasoning_content", "");
                    if (!r.isEmpty()) cb.onReasoning(r);
                    String content = msg.optString("content", "");
                    if (!content.isEmpty()) cb.onContent(content);
                    JSONArray calls = msg.optJSONArray("server_tool_calls");
                    JSONArray ann = msg.optJSONArray("annotations");
                    cb.onFinal(calls == null ? new JSONArray() : calls,
                            ann == null ? new JSONArray() : ann,
                            choices.optJSONObject(0).optString("finish_reason", ""));
                }
            }
            JSONObject u = o.optJSONObject("usage");
            if (u != null) {
                int[] usage = {0, 0};
                readUsage(u, usage);
                cb.onUsage(usage[0], usage[1]);
            }
            return true;
        } catch (Exception e) {
            cb.onError("响应解析失败，请重试", 0, false);
            return false;
        }
    }

    private void readUsage(JSONObject u, int[] out) {
        int p = u.optInt("prompt_tokens", 0);
        if (p == 0) p = u.optInt("input_tokens", 0);
        int c = u.optInt("completion_tokens", 0);
        if (c == 0) c = u.optInt("output_tokens", 0);
        if (p > 0) out[0] = p;
        if (c > 0) out[1] = c;
    }

    private static boolean isContextError(String msg, int code) {
        if (msg == null) return false;
        String m = msg.toLowerCase(Locale.ROOT);
        return code == 400 && (m.contains("context") || m.contains("too long") || m.contains("maximum")
                || m.contains("max_tokens") || m.contains("token limit") || m.contains("exceed"));
    }

    private static String extractError(String body, int code) {
        String message = "";
        try {
            JSONObject o = new JSONObject(body);
            JSONObject e = o.optJSONObject("error");
            if (e != null) message = e.optString("message", "");
            if (message.isEmpty()) message = o.optString("message", "");
        } catch (Exception ignored) {
            message = body == null ? "" : body.replaceAll("<[^>]+>", " ").trim();
        }
        if (message.length() > 400) message = message.substring(0, 400);
        switch (code) {
            case 401: return "API Key 无效或已过期，请在设置中检查。";
            case 402: return "账户余额不足，请前往 DeepSeek 开放平台充值。";
            case 403: return "当前 API Key 没有访问权限。";
            case 413: return "上传内容太大，请换一个小一点的文件。";
            case 429: return "请求太频繁了，请稍等片刻再试。";
            case 503: case 502: case 500:
                return "DeepSeek 服务暂时不可用，请稍后重试。";
            default:
                if (message.isEmpty()) return "请求失败（HTTP " + code + "）";
                return message;
        }
    }

    private static String friendlyNetwork(Exception e) {
        String m = e.getMessage();
        if (m == null) m = e.getClass().getSimpleName();
        String low = m.toLowerCase(Locale.ROOT);
        if (low.contains("timeout") || low.contains("timed out")) return "连接超时，请检查网络后重试。";
        if (low.contains("unknownhost") || low.contains("unable to resolve")) return "无法连接服务器，请检查网络。";
        if (low.contains("ssl") || low.contains("certificate")) return "安全连接失败，请检查系统时间或网络代理。";
        if (low.contains("econnreset") || low.contains("connection reset") || low.contains("broken pipe"))
            return "连接被中断，请重试。";
        return "网络错误：" + m;
    }

    private static String readAll(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n, total = 0;
        while ((n = in.read(buf)) > 0) {
            total += n;
            if (total > 1024 * 1024) break;
            out.write(buf, 0, n);
        }
        return out.toString("UTF-8");
    }

    private void post(Runnable r) {
        main.post(r);
    }
}
