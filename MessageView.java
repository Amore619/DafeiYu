package com.dafeiyu.chat;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.Locale;

/** One message row. Assistant rows update in place while a response streams. */
public class MessageView extends LinearLayout {
    public interface Callbacks {
        void onCopy(Msg m);
        void onRegenerate(Msg m);
        void onRetry(Msg m);
        void onOpenImage(Att a);
        void onOpenUrl(String url);
        void onLongPress(Msg m);
        void onNotice(String text);
    }

    private final Msg msg;
    private final Callbacks cb;
    private final int cText, cSecondary, cTertiary, cBrand, cSurfaceAlt, cSurface, cDivider, cDanger, cDangerSoft;

    private boolean assistantStructure;
    private LinearLayout thinkBox, thinkBodyWrap, searchBox, searchList, actionsRow, errorBox;
    private TextView thinkTitle, searchTitle, errorText, thinkBody;
    private ImageView thinkChevron, searchChevron;
    private MarkdownView markdown;
    private TypingDots dots;
    private boolean thinkExpanded, thinkTouched;
    private boolean searchExpanded, searchTouched;
    private int lastStatus = -1;
    private String searchSignature = "";

    public MessageView(Context context, Msg m, Callbacks cb) {
        super(context);
        this.msg = m;
        this.cb = cb;
        setOrientation(VERTICAL);
        setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        int pad = Ui.dp(context, 16);
        setPadding(pad, Ui.dp(context, 9), pad, Ui.dp(context, 9));
        cText = context.getColor(R.color.text);
        cSecondary = context.getColor(R.color.text_secondary);
        cTertiary = context.getColor(R.color.text_tertiary);
        cBrand = context.getColor(R.color.brand);
        cSurfaceAlt = context.getColor(R.color.surface_alt);
        cSurface = context.getColor(R.color.surface);
        cDivider = context.getColor(R.color.divider);
        cDanger = context.getColor(R.color.danger);
        cDangerSoft = context.getColor(R.color.danger_soft);
        if (msg.isUser()) buildUser();
        else buildAssistant();
        refresh();
    }

    public Msg message() { return msg; }

    // ---------------------------------------------------------------- user

    private void buildUser() {
        LinearLayout outer = new LinearLayout(getContext());
        outer.setOrientation(HORIZONTAL);
        outer.setGravity(Gravity.END);
        outer.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        MaxWidthLinearLayout bubble = new MaxWidthLinearLayout(getContext());
        bubble.setOrientation(VERTICAL);
        bubble.maxWidth = (int) (getResources().getDisplayMetrics().widthPixels * 0.80f);
        bubble.setBackground(Ui.rounded(getContext(), getContext().getColor(R.color.bubble_user),
                new float[]{20, 20, 7, 20}));
        bubble.setPadding(Ui.dp(getContext(), 13), Ui.dp(getContext(), 10),
                Ui.dp(getContext(), 13), Ui.dp(getContext(), 10));

        ArrayList<Att> images = new ArrayList<>();
        ArrayList<Att> files = new ArrayList<>();
        if (msg.attachments != null) for (Att a : msg.attachments) {
            if (a.isImage()) images.add(a); else files.add(a);
        }
        if (!images.isEmpty()) {
            LinearLayout grid = new LinearLayout(getContext());
            grid.setOrientation(VERTICAL);
            int perRow = images.size() == 1 ? 1 : (images.size() == 2 ? 2 : 3);
            for (int i = 0; i < images.size(); i += perRow) {
                LinearLayout row = new LinearLayout(getContext());
                row.setOrientation(HORIZONTAL);
                for (int j = i; j < Math.min(images.size(), i + perRow); j++) {
                    Att a = images.get(j);
                    ImageView iv = new ImageView(getContext());
                    iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                    int size = images.size() == 1 ? Ui.dp(getContext(), 190) : Ui.dp(getContext(), 92);
                    LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(size, size);
                    ilp.rightMargin = Ui.dp(getContext(), 6);
                    ilp.bottomMargin = Ui.dp(getContext(), 6);
                    iv.setLayoutParams(ilp);
                    iv.setBackground(Ui.rounded(getContext(), 0x33FFFFFF, 12));
                    iv.setClipToOutline(true);
                    iv.setOutlineProvider(ViewOutlineProvider.BACKGROUND);
                    File f = StoreHolder.file(a.localName);
                    if (f != null) ImageLoader.load(f, iv, size, 0x33FFFFFF);
                    iv.setOnClickListener(v -> cb.onOpenImage(a));
                    row.addView(iv);
                }
                grid.addView(row);
            }
            grid.setPadding(0, 0, 0, Ui.dp(getContext(), 4));
            bubble.addView(grid);
        }

        for (Att a : files) {
            LinearLayout row = new LinearLayout(getContext());
            row.setOrientation(HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setBackground(Ui.rounded(getContext(), 0x26FFFFFF, 10));
            row.setPadding(Ui.dp(getContext(), 9), Ui.dp(getContext(), 8),
                    Ui.dp(getContext(), 10), Ui.dp(getContext(), 8));
            ImageView icon = Ui.icon(getContext(), R.drawable.ic_file, Color.WHITE);
            row.addView(icon, new LinearLayout.LayoutParams(Ui.dp(getContext(), 20), Ui.dp(getContext(), 20)));
            LinearLayout col = Ui.column(getContext());
            TextView name = Ui.text(getContext(), a.name, 13, Color.WHITE, false);
            name.setMaxLines(1);
            name.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
            TextView meta = Ui.text(getContext(), FileUtils.typeLabel(a) + " · " + FileUtils.humanSize(a.size),
                    10.5f, 0xCCFFFFFF, false);
            col.addView(name);
            col.addView(meta);
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            clp.leftMargin = Ui.dp(getContext(), 8);
            row.addView(col, clp);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = Ui.dp(getContext(), 4);
            bubble.addView(row, lp);
        }

        if (msg.content != null && !msg.content.trim().isEmpty()) {
            TextView tv = new TextView(getContext());
            tv.setText(msg.content);
            tv.setTextSize(16);
            tv.setTextColor(Color.WHITE);
            tv.setTypeface(Ui.regular());
            tv.setLineSpacing(Ui.dp(getContext(), 4), 1.03f);
            tv.setTextIsSelectable(true);
            tv.setHighlightColor(0x33FFFFFF);
            if (!images.isEmpty() || !files.isEmpty()) tv.setPadding(0, Ui.dp(getContext(), 6), 0, 0);
            bubble.addView(tv);
        }

        bubble.setOnLongClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            cb.onLongPress(msg);
            return true;
        });
        outer.addView(bubble);
        addView(outer);
    }

    // ----------------------------------------------------------- assistant

    private void buildAssistant() {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(HORIZONTAL);
        row.setGravity(Gravity.TOP);
        row.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LogoView avatar = new LogoView(getContext());
        avatar.setBadge(true);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(Ui.dp(getContext(), 28), Ui.dp(getContext(), 28));
        alp.rightMargin = Ui.dp(getContext(), 10);
        alp.topMargin = Ui.dp(getContext(), 1);
        row.addView(avatar, alp);

        LinearLayout col = Ui.column(getContext());
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        row.addView(col, clp);

        LinearLayout head = Ui.row(getContext());
        TextView name = Ui.text(getContext(), "大肥鱼", 12.5f, cSecondary, true);
        head.addView(name);
        if (msg.model != null && !msg.model.isEmpty()) {
            TextView tag = Ui.text(getContext(), "  " + modelLabel(msg.model), 10.5f, cTertiary, false);
            head.addView(tag);
        }
        head.setPadding(0, 0, 0, Ui.dp(getContext(), 4));
        col.addView(head);

        thinkBox = Ui.column(getContext());
        thinkBox.setBackground(Ui.rounded(getContext(), cSurfaceAlt, 12));
        thinkBox.setPadding(Ui.dp(getContext(), 11), Ui.dp(getContext(), 9),
                Ui.dp(getContext(), 11), Ui.dp(getContext(), 9));
        LinearLayout thinkHead = Ui.row(getContext());
        ImageView brain = Ui.icon(getContext(), R.drawable.ic_sparkle, cBrand);
        thinkHead.addView(brain, new LinearLayout.LayoutParams(Ui.dp(getContext(), 15), Ui.dp(getContext(), 15)));
        thinkTitle = Ui.text(getContext(), "正在深度思考…", 12.5f, cSecondary, false);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        tlp.leftMargin = Ui.dp(getContext(), 6);
        thinkHead.addView(thinkTitle, tlp);
        thinkChevron = Ui.icon(getContext(), R.drawable.ic_chevron_down, cTertiary);
        thinkHead.addView(thinkChevron, new LinearLayout.LayoutParams(Ui.dp(getContext(), 16), Ui.dp(getContext(), 16)));
        thinkBox.addView(thinkHead);
        thinkBody = new TextView(getContext());
        thinkBody.setTextSize(12.5f);
        thinkBody.setTextColor(cSecondary);
        thinkBody.setTypeface(Ui.regular());
        thinkBody.setLineSpacing(Ui.dp(getContext(), 3), 1.01f);
        thinkBody.setTextIsSelectable(true);
        thinkBody.setPadding(0, Ui.dp(getContext(), 7), 0, 0);
        thinkBody.setMaxHeight(Ui.dp(getContext(), 260));
        thinkBodyWrap = Ui.column(getContext());
        thinkBodyWrap.addView(thinkBody);
        thinkBox.addView(thinkBodyWrap);
        thinkHead.setOnClickListener(v -> {
            thinkExpanded = !thinkExpanded;
            thinkTouched = true;
            updateThinkVisibility();
        });
        LinearLayout.LayoutParams thlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        thlp.bottomMargin = Ui.dp(getContext(), 7);
        col.addView(thinkBox, thlp);

        searchBox = Ui.column(getContext());
        searchBox.setBackground(Ui.rounded(getContext(), cSurfaceAlt, 12));
        searchBox.setPadding(Ui.dp(getContext(), 11), Ui.dp(getContext(), 8),
                Ui.dp(getContext(), 11), Ui.dp(getContext(), 8));
        LinearLayout searchHead = Ui.row(getContext());
        ImageView globe = Ui.icon(getContext(), R.drawable.ic_globe, cBrand);
        searchHead.addView(globe, new LinearLayout.LayoutParams(Ui.dp(getContext(), 15), Ui.dp(getContext(), 15)));
        searchTitle = Ui.text(getContext(), "正在联网搜索…", 12.5f, cSecondary, false);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        slp.leftMargin = Ui.dp(getContext(), 6);
        searchHead.addView(searchTitle, slp);
        searchChevron = Ui.icon(getContext(), R.drawable.ic_chevron_down, cTertiary);
        searchHead.addView(searchChevron, new LinearLayout.LayoutParams(Ui.dp(getContext(), 16), Ui.dp(getContext(), 16)));
        searchBox.addView(searchHead);
        searchList = Ui.column(getContext());
        searchBox.addView(searchList);
        searchHead.setOnClickListener(v -> {
            searchExpanded = !searchExpanded;
            searchTouched = true;
            updateSearchVisibility();
        });
        LinearLayout.LayoutParams shlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        shlp.bottomMargin = Ui.dp(getContext(), 7);
        col.addView(searchBox, shlp);

        dots = new TypingDots(getContext());
        dots.setDotColor(cTertiary);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                Ui.dp(getContext(), 54), Ui.dp(getContext(), 24));
        dlp.topMargin = Ui.dp(getContext(), 2);
        col.addView(dots, dlp);

        markdown = new MarkdownView(getContext(), new MarkdownView.Listener() {
            @Override public void onOpenUrl(String url) { cb.onOpenUrl(url); }
            @Override public void onCopyCode(String code) { Ui.copy(getContext(), "代码", code); }
            @Override public void onNotice(String message) { cb.onNotice(message); }
        });
        col.addView(markdown);

        errorBox = Ui.column(getContext());
        errorBox.setBackground(Ui.rounded(getContext(), cDangerSoft, 10));
        errorBox.setPadding(Ui.dp(getContext(), 11), Ui.dp(getContext(), 9),
                Ui.dp(getContext(), 11), Ui.dp(getContext(), 9));
        LinearLayout errRow = Ui.row(getContext());
        ImageView warn = Ui.icon(getContext(), R.drawable.ic_warning, cDanger);
        errRow.addView(warn, new LinearLayout.LayoutParams(Ui.dp(getContext(), 15), Ui.dp(getContext(), 15)));
        errorText = Ui.text(getContext(), "", 12.5f, cDanger, false);
        LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        elp.leftMargin = Ui.dp(getContext(), 6);
        errRow.addView(errorText, elp);
        TextView retry = Ui.text(getContext(), "重试", 12.5f, cBrand, true);
        retry.setPadding(Ui.dp(getContext(), 8), Ui.dp(getContext(), 2), 0, Ui.dp(getContext(), 2));
        retry.setOnClickListener(v -> cb.onRetry(msg));
        errRow.addView(retry);
        errorBox.addView(errRow);
        LinearLayout.LayoutParams eblp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        eblp.topMargin = Ui.dp(getContext(), 5);
        col.addView(errorBox, eblp);

        actionsRow = Ui.row(getContext());
        actionsRow.setPadding(0, Ui.dp(getContext(), 5), 0, 0);
        View copy = action("复制", R.drawable.ic_copy);
        copy.setOnClickListener(v -> cb.onCopy(msg));
        actionsRow.addView(copy);
        View regen = action("重新生成", R.drawable.ic_refresh);
        regen.setOnClickListener(v -> cb.onRegenerate(msg));
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.leftMargin = Ui.dp(getContext(), 14);
        actionsRow.addView(regen, rlp);
        col.addView(actionsRow);

        addView(row);
    }

    private View action(String label, int iconRes) {
        LinearLayout wrap = Ui.row(getContext());
        ImageView icon = Ui.icon(getContext(), iconRes, cTertiary);
        wrap.addView(icon, new LinearLayout.LayoutParams(Ui.dp(getContext(), 13), Ui.dp(getContext(), 13)));
        TextView tv = Ui.text(getContext(), label, 11.5f, cTertiary, false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = Ui.dp(getContext(), 4);
        wrap.addView(tv, lp);
        wrap.setPadding(0, Ui.dp(getContext(), 3), 0, Ui.dp(getContext(), 3));
        return wrap;
    }

    private static String modelLabel(String model) {
        if (model == null) return "";
        if (model.contains("pro")) return "V4 Pro";
        return "V4.1 Flash";
    }

    // --------------------------------------------------------------- update

    public void refresh() {
        if (msg.isUser()) return;
        boolean streaming = msg.status == Msg.STATUS_STREAMING;

        boolean hasThink = msg.thinking && (!msg.reasoning.isEmpty() || streaming);
        thinkBox.setVisibility(hasThink ? VISIBLE : GONE);
        if (hasThink) {
            if (streaming) {
                thinkTitle.setText(msg.reasoning.isEmpty() ? "正在深度思考…" : "深度思考中…");
                if (!thinkTouched) thinkExpanded = true;
            } else {
                long sec = Math.max(0, msg.thinkingMs / 1000);
                if (sec > 0) thinkTitle.setText("已深度思考（用时 " + sec + " 秒）");
                else thinkTitle.setText("已深度思考");
                if (!thinkTouched) thinkExpanded = false;
            }
            thinkBody.setText(msg.reasoning);
            updateThinkVisibility();
        }

        boolean hasSearch = !msg.tools.isEmpty() || (msg.search && streaming);
        searchBox.setVisibility(hasSearch ? VISIBLE : GONE);
        if (hasSearch) {
            if (streaming && msg.tools.isEmpty()) {
                searchTitle.setText("正在联网搜索…");
            } else {
                String summary = ToolCall.summary(msg.tools);
                searchTitle.setText(summary.isEmpty() ? "正在联网搜索…" : summary);
            }
            rebuildSearchIfNeeded();
            if (!searchTouched) searchExpanded = streaming;
            updateSearchVisibility();
        }

        boolean hasContent = msg.content != null && !msg.content.trim().isEmpty();
        markdown.setVisibility(hasContent ? VISIBLE : GONE);
        if (hasContent) markdown.render(msg.content, streaming);
        boolean showDots = streaming && !hasContent && !msg.thinking && msg.reasoning.isEmpty();
        dots.setVisibility(showDots ? VISIBLE : GONE);
        if (showDots) dots.start(); else dots.stop();

        boolean showError = msg.status == Msg.STATUS_ERROR && !msg.error.isEmpty();
        errorBox.setVisibility(showError ? VISIBLE : GONE);
        if (showError) errorText.setText(msg.error);

        boolean showActions = !streaming && hasContent && msg.status != Msg.STATUS_ERROR;
        actionsRow.setVisibility(showActions ? VISIBLE : GONE);

        if (lastStatus != msg.status) {
            lastStatus = msg.status;
            if (msg.status == Msg.STATUS_COMPLETE) {
                thinkTouched = false;
                searchTouched = false;
                if (hasThink) { thinkExpanded = false; updateThinkVisibility(); }
                if (hasSearch) { searchExpanded = false; updateSearchVisibility(); }
            }
        }
    }

    private void updateThinkVisibility() {
        thinkBodyWrap.setVisibility(thinkExpanded && !msg.reasoning.isEmpty() ? VISIBLE : GONE);
        thinkChevron.animate().rotation(thinkExpanded ? 180 : 0).setDuration(160).start();
        thinkTitle.setTextColor(thinkExpanded ? cText : cSecondary);
    }

    private void updateSearchVisibility() {
        searchList.setVisibility(searchExpanded ? VISIBLE : GONE);
        searchChevron.animate().rotation(searchExpanded ? 180 : 0).setDuration(160).start();
        searchTitle.setTextColor(searchExpanded ? cText : cSecondary);
    }

    private void rebuildSearchIfNeeded() {
        StringBuilder sig = new StringBuilder();
        for (ToolCall t : msg.tools) {
            sig.append(t.id).append(t.kind).append(t.status).append(t.sourceCount).append(';');
            for (ToolCall.Source s : t.sources) sig.append(s.url).append(',');
        }
        if (sig.toString().equals(searchSignature)) return;
        searchSignature = sig.toString();
        searchList.removeAllViews();
        int shown = 0;
        for (ToolCall t : msg.tools) {
            if (t.isSearch()) {
                for (ToolCall.Source s : t.sources) {
                    if (shown >= 8) break;
                    searchList.addView(sourceRow(shown + 1, s.title, s.url, s.site));
                    shown++;
                }
            } else if (!t.url.isEmpty()) {
                if (shown >= 8) break;
                searchList.addView(sourceRow(shown + 1,
                        t.pageTitle.isEmpty() ? t.url : t.pageTitle, t.url, host(t.url)));
                shown++;
            }
        }
        if (shown == 0 && msg.status == Msg.STATUS_STREAMING) {
            TextView t = Ui.text(getContext(), "正在检索网页…", 12, cSecondary, false);
            t.setPadding(0, Ui.dp(getContext(), 7), 0, 0);
            searchList.addView(t);
        }
    }

    private View sourceRow(int index, String title, String url, String site) {
        LinearLayout row = Ui.row(getContext());
        row.setPadding(0, Ui.dp(getContext(), 7), 0, Ui.dp(getContext(), 1));
        TextView num = Ui.text(getContext(), String.valueOf(index), 10.5f, cBrand, true);
        num.setGravity(Gravity.CENTER);
        num.setBackground(Ui.rounded(getContext(), getContext().getColor(R.color.brand_soft), 8));
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(Ui.dp(getContext(), 16), Ui.dp(getContext(), 16));
        nlp.rightMargin = Ui.dp(getContext(), 7);
        row.addView(num, nlp);
        LinearLayout col = Ui.column(getContext());
        TextView t = Ui.text(getContext(), title == null || title.isEmpty() ? url : title, 12, cText, false);
        t.setMaxLines(2);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        TextView s = Ui.text(getContext(), site == null || site.isEmpty() ? host(url) : site, 10.5f, cTertiary, false);
        col.addView(t);
        col.addView(s);
        row.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        row.setOnClickListener(v -> cb.onOpenUrl(url));
        return row;
    }

    private static String host(String url) {
        try {
            return android.net.Uri.parse(url).getHost();
        } catch (Exception e) {
            return "";
        }
    }

    private static class MaxWidthLinearLayout extends LinearLayout {
        int maxWidth;

        MaxWidthLinearLayout(Context context) { super(context); }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int mode = MeasureSpec.getMode(widthMeasureSpec);
            int size = MeasureSpec.getSize(widthMeasureSpec);
            int width = mode == MeasureSpec.UNSPECIFIED ? maxWidth : Math.min(size, maxWidth);
            super.onMeasure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.AT_MOST), heightMeasureSpec);
        }
    }

    /** Set by MainActivity so attachment thumbnails can resolve local files. */
    public static class StoreHolder {
        private static Store store;
        public static void set(Store s) { store = s; }
        public static File file(String localName) {
            return store == null ? null : store.attachmentFile(localName);
        }
    }
}
