package com.dafeiyu.chat;

import java.util.Locale;

/** Lightweight, deliberately conservative token estimator plus context-budget math. */
public final class TokenMeter {
    private TokenMeter() { }

    public static int estimate(String s) {
        if (s == null || s.isEmpty()) return 0;
        int cjk = 0, ascii = 0, other = 0;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            if (isCjk(cp)) cjk++;
            else if (cp < 128) ascii++;
            else other++;
        }
        return (int) Math.ceil(cjk * 0.72 + ascii / 4.0 + other * 0.65) + 2;
    }

    private static boolean isCjk(int cp) {
        return (cp >= 0x4E00 && cp <= 0x9FFF) || (cp >= 0x3400 && cp <= 0x4DBF)
                || (cp >= 0xF900 && cp <= 0xFAFF) || (cp >= 0x3000 && cp <= 0x303F)
                || (cp >= 0xFF00 && cp <= 0xFFEF) || (cp >= 0x3040 && cp <= 0x30FF)
                || (cp >= 0xAC00 && cp <= 0xD7AF);
    }

    public static int estimateMessage(Msg m) {
        if (m == null) return 0;
        int n = 8 + estimate(m.content);
        if (m.thinking || m.search) n += estimate(m.reasoning) / 2;
        if (m.attachments != null) {
            for (Att a : m.attachments) {
                if (a.isImage()) n += 1024;
                else n += (int) Math.min(6000, 800 + a.size / 300);
            }
        }
        return n;
    }

    public static int estimateConversation(Conv c) {
        int n = 1400; // system prompt + request envelope
        if (c == null) return n;
        for (Msg m : c.messages) n += estimateMessage(m);
        return n;
    }

    /** Tokens currently occupied after the most recent completed request. */
    public static int used(Conv c) {
        if (c == null) return 0;
        if (c.lastPromptTokens > 0) {
            return c.lastPromptTokens + Math.max(0, c.lastCompletionTokens);
        }
        return estimateConversation(c);
    }

    public static int averageRound(Conv c) {
        int used = used(c);
        int turns = Math.max(1, c == null ? 1 : c.turns());
        int avg = (used - 1200) / turns;
        if (avg < 260) avg = 260;
        return avg;
    }

    public static int roundsLeft(Conv c, int maxTokens) {
        if (c == null) return 999;
        int limit = c.contextLimit > 0 ? c.contextLimit : 1000000;
        int reserve = Math.max(2048, maxTokens) + 4096;
        int used = used(c);
        int left = limit - reserve - used;
        if (left <= 0) return 0;
        int avg = averageRound(c);
        int rounds = left / Math.max(200, avg);
        return Math.max(0, Math.min(9999, rounds));
    }

    public static int percent(Conv c) {
        if (c == null) return 0;
        int limit = c.contextLimit > 0 ? c.contextLimit : 1000000;
        return Math.max(0, Math.min(100, Math.round(used(c) * 100f / limit)));
    }

    public static String formatTokens(int n) {
        if (n < 1000) return n + "";
        if (n < 1000000) return String.format(Locale.CHINA, "%.1fK", n / 1000f);
        return String.format(Locale.CHINA, "%.1fM", n / 1000000f);
    }

    public static String limitLabel(int n) {
        if (n >= 1000000) return String.format(Locale.CHINA, "%.0fM", n / 1000000f);
        return (n / 1000) + "K";
    }
}
