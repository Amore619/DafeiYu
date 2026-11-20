package com.dafeiyu.chat;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.method.LinkMovementMethod;
import android.text.style.BackgroundColorSpan;
import android.text.style.ClickableSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.LeadingMarginSpan;
import android.text.style.StrikethroughSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** A compact Markdown renderer tailored to chat answers (no WebView, no external deps). */
public class MarkdownView extends LinearLayout {
    public interface Listener {
        void onOpenUrl(String url);
        void onCopyCode(String code);
        void onNotice(String message);
    }

    private final Listener listener;
    private final int cText, cSecondary, cBrand, cCodeBg, cCodeHeader, cDivider, cSurfaceAlt, cSurface;
    private String lastText;
    private boolean lastStreaming;
    private boolean force;

    public MarkdownView(Context context, Listener listener) {
        super(context);
        this.listener = listener;
        setOrientation(VERTICAL);
        setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        cText = context.getColor(R.color.text);
        cSecondary = context.getColor(R.color.text_secondary);
        cBrand = context.getColor(R.color.brand);
        cCodeBg = context.getColor(R.color.code_bg);
        cCodeHeader = context.getColor(R.color.code_header);
        cDivider = context.getColor(R.color.divider);
        cSurfaceAlt = context.getColor(R.color.surface_alt);
        cSurface = context.getColor(R.color.surface);
    }

    public void render(String text, boolean streaming) {
        String safe = text == null ? "" : text.replace("\r\n", "\n").replace('\r', '\n');
        if (!force && safe.equals(lastText) && streaming == lastStreaming) return;
        lastText = safe;
        lastStreaming = streaming;
        force = false;
        removeAllViews();
        try {
            build(safe);
            if (streaming) addCursor();
        } catch (Throwable t) {
            // 畸形/超深嵌套 Markdown 可能抛 StackOverflowError 等非 Exception 错误，
            // 这里兜底降级为纯文本，避免整个 App 闪退。
            try {
                removeAllViews();
                TextView plain = new TextView(getContext());
                plain.setTextColor(cText);
                plain.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
                plain.setTextIsSelectable(true);
                plain.setText(safe);
                addView(plain);
            } catch (Throwable ignore) { }
        }
    }

    public void refresh() {
        force = true;
        render(lastText, lastStreaming);
    }

    private void build(String text) {
        if (text.isEmpty()) return;
        String[] lines = text.split("\n", -1);
        int i = 0;
        while (i < lines.length) {
            String line = lines[i];
            String trim = line.trim();

            if (trim.startsWith("```")) {
                String lang = trim.substring(3).trim();
                StringBuilder code = new StringBuilder();
                i++;
                while (i < lines.length && !lines[i].trim().startsWith("```")) {
                    if (code.length() > 0) code.append('\n');
                    code.append(lines[i]);
                    i++;
                }
                if (i < lines.length) i++;
                addCode(lang, code.toString());
                continue;
            }

            if (trim.isEmpty()) {
                i++;
                continue;
            }

            if (trim.matches("^#{1,6}\\s+.*")) {
                int level = 0;
                while (level < trim.length() && trim.charAt(level) == '#') level++;
                String content = trim.substring(level).trim();
                addHeading(content, level);
                i++;
                continue;
            }

            if (trim.equals("---") || trim.equals("***") || trim.equals("___")) {
                View divider = new View(getContext());
                divider.setBackgroundColor(cDivider);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(getContext(), 1));
                lp.topMargin = Ui.dp(getContext(), 14);
                lp.bottomMargin = Ui.dp(getContext(), 14);
                addView(divider, lp);
                i++;
                continue;
            }

            if (trim.startsWith(">")) {
                StringBuilder q = new StringBuilder();
                while (i < lines.length && lines[i].trim().startsWith(">")) {
                    String s = lines[i].trim().substring(1).trim();
                    if (q.length() > 0) q.append('\n');
                    q.append(s);
                    i++;
                }
                addQuote(q.toString());
                continue;
            }

            if (isTableStart(lines, i)) {
                int consumed = addTable(lines, i);
                i += consumed;
                continue;
            }

            if (isListLine(line)) {
                i = addList(lines, i);
                continue;
            }

            // Paragraph: consume until a blank line or another block.
            StringBuilder para = new StringBuilder();
            while (i < lines.length) {
                String l = lines[i];
                String t = l.trim();
                if (t.isEmpty() || t.startsWith("```") || t.startsWith(">")
                        || t.matches("^#{1,6}\\s+.*") || isListLine(l) || isTableStart(lines, i)
                        || t.equals("---") || t.equals("***") || t.equals("___")) break;
                if (para.length() > 0) para.append('\n');
                para.append(l);
                i++;
            }
            addParagraph(para.toString());
        }
    }

    private boolean isListLine(String line) {
        String t = line.trim();
        return t.matches("^[-*+]\\s+.*") || t.matches("^\\d+[.)]\\s+.*");
    }

    private int addList(String[] lines, int start) {
        int i = start;
        while (i < lines.length) {
            String line = lines[i];
            if (!isListLine(line)) break;
            String t = line.trim();
            boolean ordered = t.matches("^\\d+[.)]\\s+.*");
            String marker;
            String content;
            if (ordered) {
                int dot = Math.max(t.indexOf('.'), t.indexOf(')'));
                marker = t.substring(0, dot + 1);
                content = t.substring(dot + 1).trim();
            } else {
                marker = "•";
                content = t.substring(1).trim();
            }
            int indent = 0;
            for (int k = 0; k < line.length() && Character.isWhitespace(line.charAt(k)); k++) {
                if (line.charAt(k) == ' ') indent++;
                else if (line.charAt(k) == '\t') indent += 4;
            }
            TextView tv = paragraphView();
            SpannableStringBuilder sb = new SpannableStringBuilder();
            sb.append(ordered ? marker + "  " : marker + "  ");
            int markEnd = sb.length();
            sb.setSpan(new ForegroundColorSpan(ordered ? cSecondary : cBrand), 0, markEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.append(inline(content));
            sb.setSpan(new LeadingMarginSpan.Standard(0, Ui.dp(getContext(), 15)), 0, sb.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            tv.setText(sb);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = Ui.dp(getContext(), 3);
            lp.leftMargin = Ui.dp(getContext(), 2 + Math.min(6, indent / 2) * 14);
            addView(tv, lp);
            i++;
        }
        return i;
    }

    private boolean isTableStart(String[] lines, int i) {
        if (i + 1 >= lines.length) return false;
        String a = lines[i].trim(), b = lines[i + 1].trim();
        if (!a.contains("|") || !b.contains("|")) return false;
        String sep = b.replace("|", "").replace(":", "").replace("-", "").trim();
        return sep.isEmpty() && b.contains("-");
    }

    private int addTable(String[] lines, int start) {
        List<String[]> rows = new ArrayList<>();
        int i = start;
        while (i < lines.length && lines[i].contains("|") && !lines[i].trim().isEmpty()) {
            String line = lines[i].trim();
            if (i == start + 1) { i++; continue; }
            String inner = line;
            if (inner.startsWith("|")) inner = inner.substring(1);
            if (inner.endsWith("|")) inner = inner.substring(0, inner.length() - 1);
            rows.add(inner.split("\\|", -1));
            i++;
        }
        if (rows.isEmpty()) return Math.max(1, i - start);
        int cols = 0;
        for (String[] r : rows) cols = Math.max(cols, r.length);

        HorizontalScrollView hs = new HorizontalScrollView(getContext());
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout table = new LinearLayout(getContext());
        table.setOrientation(VERTICAL);
        for (int r = 0; r < rows.size(); r++) {
            LinearLayout row = new LinearLayout(getContext());
            row.setOrientation(HORIZONTAL);
            String[] cells = rows.get(r);
            for (int c = 0; c < cols; c++) {
                String val = c < cells.length ? cells[c].trim() : "";
                TextView tv = new TextView(getContext());
                tv.setText(inline(val));
                tv.setTextSize(14);
                tv.setTextColor(cText);
                tv.setTypeface(r == 0 ? Ui.medium() : Ui.regular());
                tv.setPadding(Ui.dp(getContext(), 10), Ui.dp(getContext(), 8),
                        Ui.dp(getContext(), 10), Ui.dp(getContext(), 8));
                tv.setBackground(tableCell(r == 0));
                tv.setMinWidth(Ui.dp(getContext(), 76));
                tv.setMovementMethod(LinkMovementMethod.getInstance());
                tv.setHighlightColor(Color.TRANSPARENT);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                row.addView(tv, lp);
            }
            table.addView(row, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        hs.addView(table);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(getContext(), 8);
        lp.bottomMargin = Ui.dp(getContext(), 8);
        addView(hs, lp);
        return Math.max(1, i - start);
    }

    private android.graphics.drawable.GradientDrawable tableCell(boolean header) {
        android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable();
        d.setColor(header ? cSurfaceAlt : cSurface);
        d.setStroke(Math.max(1, Ui.dp(getContext(), 0.6f)), cDivider);
        d.setCornerRadius(0);
        return d;
    }

    private void addHeading(String text, int level) {
        TextView tv = new TextView(getContext());
        float[] sizes = {23, 20, 18, 17, 16, 16};
        tv.setTextSize(sizes[Math.min(5, Math.max(0, level - 1))]);
        tv.setTextColor(cText);
        tv.setTypeface(Ui.bold());
        tv.setText(inline(text));
        tv.setLineSpacing(Ui.dp(getContext(), 4), 1f);
        tv.setTextIsSelectable(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(getContext(), level <= 2 ? 16 : 12);
        lp.bottomMargin = Ui.dp(getContext(), 4);
        addView(tv, lp);
    }

    private void addQuote(String text) {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(HORIZONTAL);
        View bar = new View(getContext());
        bar.setBackground(Ui.rounded(getContext(), cBrand, 2));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(Ui.dp(getContext(), 3),
                ViewGroup.LayoutParams.MATCH_PARENT);
        blp.topMargin = Ui.dp(getContext(), 3);
        blp.bottomMargin = Ui.dp(getContext(), 3);
        row.addView(bar, blp);
        TextView tv = paragraphView();
        tv.setText(inline(text));
        tv.setTextColor(cSecondary);
        tv.setPadding(Ui.dp(getContext(), 12), Ui.dp(getContext(), 2), 0, Ui.dp(getContext(), 2));
        row.addView(tv, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(getContext(), 8);
        lp.bottomMargin = Ui.dp(getContext(), 8);
        addView(row, lp);
    }

    private void addParagraph(String text) {
        if (text.trim().isEmpty()) return;
        TextView tv = paragraphView();
        tv.setText(inline(text));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(getContext(), 3);
        lp.bottomMargin = Ui.dp(getContext(), 5);
        addView(tv, lp);
    }

    private TextView paragraphView() {
        TextView tv = new TextView(getContext());
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        tv.setTextColor(cText);
        tv.setTypeface(Ui.regular());
        tv.setLineSpacing(Ui.dp(getContext(), 5), 1.02f);
        tv.setTextIsSelectable(true);
        tv.setHighlightColor(Color.TRANSPARENT);
        tv.setMovementMethod(LinkMovementMethod.getInstance());
        tv.setLinkTextColor(cBrand);
        return tv;
    }

    private void addCode(String lang, String code) {
        LinearLayout box = new LinearLayout(getContext());
        box.setOrientation(VERTICAL);
        box.setBackground(Ui.rounded(getContext(), cCodeBg, 12));

        LinearLayout head = new LinearLayout(getContext());
        head.setOrientation(HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(Ui.dp(getContext(), 12), 0, Ui.dp(getContext(), 6), 0);
        head.setBackgroundColor(cCodeHeader);
        TextView label = Ui.text(getContext(), (lang == null || lang.isEmpty()) ? "代码" : lang,
                11.5f, cSecondary, false);
        label.setTypeface(Ui.mono());
        head.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        TextView copy = Ui.text(getContext(), "复制", 12, cBrand, false);
        copy.setPadding(Ui.dp(getContext(), 10), Ui.dp(getContext(), 7),
                Ui.dp(getContext(), 8), Ui.dp(getContext(), 7));
        copy.setOnClickListener(v -> {
            Ui.copy(getContext(), "代码", code);
            if (listener != null) listener.onNotice("代码已复制");
        });
        head.addView(copy);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(getContext(), 34));
        box.addView(head, hlp);

        HorizontalScrollView hs = new HorizontalScrollView(getContext());
        hs.setHorizontalScrollBarEnabled(false);
        TextView tv = new TextView(getContext());
        tv.setText(code);
        tv.setTextSize(13);
        tv.setTextColor(cText);
        tv.setTypeface(Ui.mono());
        tv.setLineSpacing(Ui.dp(getContext(), 3), 1f);
        tv.setPadding(Ui.dp(getContext(), 14), Ui.dp(getContext(), 11),
                Ui.dp(getContext(), 14), Ui.dp(getContext(), 12));
        tv.setTextIsSelectable(true);
        hs.addView(tv);
        box.addView(hs, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(getContext(), 8);
        lp.bottomMargin = Ui.dp(getContext(), 8);
        addView(box, lp);
    }

    private void addCursor() {
        TextView cursor = Ui.text(getContext(), "▍", 16, cBrand, true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(getContext(), 1);
        addView(cursor, lp);
    }

    private SpannableStringBuilder inline(String text) {
        SpannableStringBuilder sb = new SpannableStringBuilder();
        appendInline(sb, text == null ? "" : text, false, false, false);
        return sb;
    }

    private void appendInline(SpannableStringBuilder sb, String text, boolean bold, boolean italic, boolean strike) {
        int i = 0;
        while (i < text.length()) {
            char ch = text.charAt(i);
            if (ch == '\\' && i + 1 < text.length()) {
                sb.append(text.charAt(i + 1));
                i += 2;
                continue;
            }
            if (ch == '`') {
                int end = text.indexOf('`', i + 1);
                if (end > i) {
                    int start = sb.length();
                    sb.append(text, i + 1, end);
                    int stop = sb.length();
                    sb.setSpan(new TypefaceSpan("monospace"), start, stop, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    sb.setSpan(new BackgroundColorSpan(cSurfaceAlt), start, stop, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    sb.setSpan(new ForegroundColorSpan(Ui.blend(cBrand, cText, 0.35f)), start, stop,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    i = end + 1;
                    continue;
                }
            }
            if (text.startsWith("**", i) || text.startsWith("__", i)) {
                String mark = text.substring(i, i + 2);
                int end = text.indexOf(mark, i + 2);
                if (end > i + 2) {
                    int start = sb.length();
                    appendInline(sb, text.substring(i + 2, end), true, italic, strike);
                    int stop = sb.length();
                    if (bold) sb.setSpan(new StyleSpan(Typeface.BOLD), start, stop, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    sb.setSpan(new StyleSpan(Typeface.BOLD), start, stop, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    i = end + 2;
                    continue;
                }
            }
            if (text.startsWith("~~", i)) {
                int end = text.indexOf("~~", i + 2);
                if (end > i + 2) {
                    int start = sb.length();
                    appendInline(sb, text.substring(i + 2, end), bold, italic, true);
                    int stop = sb.length();
                    sb.setSpan(new StrikethroughSpan(), start, stop, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    i = end + 2;
                    continue;
                }
            }
            if (ch == '[') {
                int mid = text.indexOf("](", i + 1);
                int end = mid > 0 ? text.indexOf(')', mid + 2) : -1;
                if (mid > 0 && end > mid) {
                    final String label = text.substring(i + 1, mid);
                    final String url = text.substring(mid + 2, end).trim();
                    int start = sb.length();
                    sb.append(label);
                    int stop = sb.length();
                    if (!url.isEmpty()) {
                        sb.setSpan(new LinkSpan(url), start, stop, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                        sb.setSpan(new ForegroundColorSpan(cBrand), start, stop, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    }
                    i = end + 1;
                    continue;
                }
            }
            if (ch == '*' || ch == '_') {
                int end = text.indexOf(ch, i + 1);
                if (end > i + 1) {
                    int start = sb.length();
                    appendInline(sb, text.substring(i + 1, end), bold, true, strike);
                    int stop = sb.length();
                    sb.setSpan(new StyleSpan(Typeface.ITALIC), start, stop, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    i = end + 1;
                    continue;
                }
            }
            if (text.startsWith("http://", i) || text.startsWith("https://", i)) {
                int end = i;
                while (end < text.length() && !Character.isWhitespace(text.charAt(end))
                        && text.charAt(end) != ')' && text.charAt(end) != '，' && text.charAt(end) != '。') end++;
                String url = text.substring(i, end);
                int start = sb.length();
                sb.append(url);
                sb.setSpan(new LinkSpan(url), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                sb.setSpan(new ForegroundColorSpan(cBrand), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                i = end;
                continue;
            }
            int start = sb.length();
            sb.append(ch);
            if (bold) sb.setSpan(new StyleSpan(Typeface.BOLD), start, start + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            if (italic) sb.setSpan(new StyleSpan(Typeface.ITALIC), start, start + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            if (strike) sb.setSpan(new StrikethroughSpan(), start, start + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            i++;
        }
    }

    private class LinkSpan extends ClickableSpan {
        private final String url;
        LinkSpan(String url) { this.url = url; }

        @Override
        public void onClick(View widget) {
            if (listener != null) listener.onOpenUrl(url);
        }

        @Override
        public void updateDrawState(TextPaint ds) {
            ds.setColor(cBrand);
            ds.setUnderlineText(false);
        }
    }
}
