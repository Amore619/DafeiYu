package com.dafeiyu.chat;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.UUID;

public class MainActivity extends Activity {
    private static final int REQ_IMAGES = 101;
    private static final int REQ_FILES = 102;
    private static final int REQ_EXPORT = 103;
    private static final int REQ_IMPORT = 104;
    private static final int REQ_RESTORE = 105;

    private Prefs prefs;
    private Store store;
    private ApiClient api;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private FrameLayout root;
    private LinearLayout page;
    private LinearLayout topBar;
    private TextView titleView, subtitleView, contextPill;
    private View exportButton;
    private FrameLayout chatHost;
    private ScrollView chatScroll;
    private LinearLayout messagesContainer;
    private View welcomeView;
    private View jumpButton;
    private LinearLayout composerWrap, attachmentStrip, chipsRow;
    private View warningBanner;
    private TextView warningText;
    private EditText input;
    private FrameLayout sendButton;
    private ImageView sendIcon;
    private LinearLayout thinkChip, searchChip, modelChip;
    private TextView thinkChipText, searchChipText, modelChipText;
    private ImageView thinkChipIcon, searchChipIcon;

    private View drawerScrim, drawerPanel;
    private LinearLayout drawerList;
    private boolean drawerOpen;
    private View settingsOverlay;
    private View sheetOverlay;
    private View dialogOverlay;
    private View onboardingOverlay;
    private View previewOverlay;
    private View busyOverlay;
    private TextView busyLabel;
    private View snackView;
    private int snackSeq;
    private Runnable snackTimeout;

    private interface Choice { void on(String value); }
    private interface Toggle { void on(boolean value); }

    private final MessageView.Callbacks messageCallbacks = new MessageView.Callbacks() {
        @Override public void onCopy(Msg m) {
            Ui.copy(MainActivity.this, "消息", m.content);
            snack("已复制", null, null);
        }
        @Override public void onRegenerate(Msg m) { regenerate(m); }
        @Override public void onRetry(Msg m) { retryMessage(m); }
        @Override public void onOpenImage(Att a) { showImagePreview(a); }
        @Override public void onOpenUrl(String url) { Ui.openUrl(MainActivity.this, url); }
        @Override public void onLongPress(Msg m) { showMessageSheet(m); }
        @Override public void onNotice(String text) { snack(text, null, null); }
    };

    private Conv conv;
    private final ArrayList<Att> pending = new ArrayList<>();
    private final HashMap<String, MessageView> messageViews = new HashMap<>();
    private boolean streaming;
    private Msg streamingMsg;
    private MessageView streamingView;
    private boolean userNearBottom = true;
    private long thinkingStart;
    private String lastDayRendered = "";
    private View aiNote;
    private int bottomInset;
    private int navInset = -1;      // 导航栏高度（缓存，保底用）
    private int imeInset = 0;       // 当前输入法高度
    private boolean windowResizesForIme = false; // 窗口是否随输入法物理缩小
    private boolean night;
    private Runnable streamRefresh;
    private boolean importInProgress;
    private boolean backupInProgress;

    private final Runnable refreshStreamRunnable = new Runnable() {
        @Override public void run() {
            if (streamingView != null) streamingView.refresh();
            if (userNearBottom) scrollBottom(false);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        night = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        setupWindow();
        prefs = Prefs.get(this);
        store = new Store(this);
        MessageView.StoreHolder.set(store);
        api = new ApiClient();

        buildRoot();
        setContentView(root);
        applySystemBarsAppearance();
        setupInsets();
        watchComposerSpace();
        buildDrawer();
        // 先把主界面显示出来（避免启动白屏），历史消息的解析与渲染放到下一帧执行。
        updateEverything();
        root.requestApplyInsets();
        root.post(() -> {
            try {
                openLastConversation();
                updateEverything();
            } catch (Throwable t) {
                // 历史数据异常不应导致启动即闪退
                conv = null;
                try { renderConversation(); } catch (Throwable ignore) { }
            }
            if (!prefs.onboardingDone() && !prefs.hasApiKey()) {
                openOnboarding();
            }
            // 有历史对话但从未备份过时，首次启动先做一次静默备份（换机/卸载前的兜底）。
            if (prefs.autoBackup() && prefs.lastAutoBackupAt() == 0L
                    && !store.list().isEmpty()) {
                autoBackupIfNeeded(false);
            }
        });
    }

    private void setupWindow() {
        Window w = getWindow();
        try {
            w.setStatusBarColor(Color.TRANSPARENT);
            w.setNavigationBarColor(Color.TRANSPARENT);
            if (Build.VERSION.SDK_INT >= 29) w.setNavigationBarContrastEnforced(false);
            if (Build.VERSION.SDK_INT >= 30) {
                try { w.setDecorFitsSystemWindows(false); } catch (Throwable ignore) { }
            } else {
                int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
                if (!night) {
                    flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
                    if (Build.VERSION.SDK_INT >= 26) flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
                }
                try { w.getDecorView().setSystemUiVisibility(flags); } catch (Throwable ignore) { }
            }
            w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        } catch (Throwable ignore) {
            // 部分 ROM（小米 HyperOS）在 setContentView 之前窗口对象未就绪，做最佳努力即可。
        }
    }

    /**
     * 设置状态栏/导航栏图标明暗。必须在 setContentView 之后调用：
     * 在小米 HyperOS 上，setContentView 之前 DecorView 尚未创建，
     * w.getInsetsController() 内部会访问 null 的 DecorView 导致 NPE 闪退。
     */
    private void applySystemBarsAppearance() {
        if (Build.VERSION.SDK_INT < 30) return;
        try {
            Window w = getWindow();
            WindowInsetsController c = w.getInsetsController();
            if (c != null) {
                int mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                        | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                c.setSystemBarsAppearance(night ? 0 : mask, mask);
            }
        } catch (Throwable ignore) {
            // 状态栏配色失败不影响功能，绝不让它导致闪退。
        }
    }

    // ------------------------------------------------------------------ root

    private void buildRoot() {
        root = new FrameLayout(this);
        root.setBackgroundColor(getColor(R.color.bg));
        page = Ui.column(this);
        page.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(page);

        buildTopBar();
        buildChatHost();
        buildComposer();
    }

    private void buildTopBar() {
        topBar = Ui.row(this);
        topBar.setBackgroundColor(getColor(R.color.bg));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 56));
        page.addView(topBar, lp);
        topBar.setPadding(Ui.dp(this, 4), 0, Ui.dp(this, 6), 0);

        View menu = Ui.iconButton(this, R.drawable.ic_menu, getColor(R.color.text),
                0, getColor(R.color.surface_press));
        menu.setContentDescription("历史对话");
        menu.setOnClickListener(v -> {
            haptic();
            openDrawer();
        });
        topBar.addView(menu);

        LinearLayout titleCol = Ui.column(this);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        tlp.leftMargin = Ui.dp(this, 4);
        topBar.addView(titleCol, tlp);
        titleView = Ui.text(this, "大肥鱼", 17, getColor(R.color.text), true);
        titleView.setMaxLines(1);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        subtitleView = Ui.text(this, "DeepSeek API 直连", 10.5f, getColor(R.color.text_tertiary), false);
        subtitleView.setMaxLines(1);
        titleCol.addView(titleView);
        titleCol.addView(subtitleView);

        exportButton = Ui.iconButton(this, R.drawable.ic_download, getColor(R.color.text_secondary),
                0, getColor(R.color.surface_press));
        exportButton.setContentDescription("导出对话");
        exportButton.setOnClickListener(v -> quickExport());
        exportButton.setVisibility(View.GONE);
        topBar.addView(exportButton);

        contextPill = new TextView(this);
        contextPill.setTextSize(11.5f);
        contextPill.setTypeface(Ui.medium());
        contextPill.setTextColor(getColor(R.color.brand));
        contextPill.setBackground(Ui.ripple(Ui.rounded(this, getColor(R.color.brand_soft), 14),
                getColor(R.color.brand_soft_2)));
        contextPill.setPadding(Ui.dp(this, 9), Ui.dp(this, 5), Ui.dp(this, 9), Ui.dp(this, 5));
        contextPill.setGravity(Gravity.CENTER);
        contextPill.setText("1M 上下文");
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.rightMargin = Ui.dp(this, 2);
        topBar.addView(contextPill, clp);
        contextPill.setOnClickListener(v -> {
            haptic();
            showContextSheet();
        });

        View add = Ui.iconButton(this, R.drawable.ic_plus, getColor(R.color.text),
                0, getColor(R.color.surface_press));
        add.setContentDescription("新对话");
        add.setOnClickListener(v -> {
            haptic();
            requestNewChat();
        });
        topBar.addView(add);
    }

    private void buildChatHost() {
        chatHost = new FrameLayout(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1);
        page.addView(chatHost, lp);

        chatScroll = new ScrollView(this);
        chatScroll.setFillViewport(true);
        chatScroll.setVerticalScrollBarEnabled(false);
        chatScroll.setClipToPadding(false);
        chatScroll.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 8));
        chatHost.addView(chatScroll, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        messagesContainer = Ui.column(this);
        messagesContainer.setLayoutParams(new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        chatScroll.addView(messagesContainer);

        chatScroll.setOnScrollChangeListener((v, scrollX, scrollY, oldX, oldY) -> {
            int diff = messagesContainer.getHeight() - (scrollY + chatScroll.getHeight());
            userNearBottom = diff < Ui.dp(this, 140);
            if (jumpButton != null) {
                if (diff > Ui.dp(this, 280)) {
                    if (jumpButton.getVisibility() != View.VISIBLE) {
                        jumpButton.setVisibility(View.VISIBLE);
                        jumpButton.setAlpha(0f);
                        jumpButton.animate().alpha(1f).translationY(0).setDuration(160).start();
                    }
                } else if (jumpButton.getVisibility() == View.VISIBLE) {
                    jumpButton.animate().alpha(0f).translationY(Ui.dp(this, 10))
                            .setDuration(140).withEndAction(() -> jumpButton.setVisibility(View.GONE)).start();
                }
            }
        });

        buildWelcome();

        jumpButton = Ui.iconButton(this, R.drawable.ic_arrow_down, getColor(R.color.text),
                getColor(R.color.surface), getColor(R.color.surface_press));
        FrameLayout.LayoutParams jlp = new FrameLayout.LayoutParams(Ui.dp(this, 42), Ui.dp(this, 42));
        jlp.gravity = Gravity.BOTTOM | Gravity.END;
        jlp.setMargins(0, 0, Ui.dp(this, 16), Ui.dp(this, 10));
        jumpButton.setLayoutParams(jlp);
        jumpButton.setElevation(Ui.dp(this, 4));
        jumpButton.setVisibility(View.GONE);
        jumpButton.setOnClickListener(v -> scrollBottom(true));
        chatHost.addView(jumpButton, jlp);
    }

    private void buildWelcome() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        welcomeView = scroll;
        chatHost.addView(scroll, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout box = Ui.column(this);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(Ui.dp(this, 24), Ui.dp(this, 30), Ui.dp(this, 24), Ui.dp(this, 24));
        scroll.addView(box, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LogoView logo = new LogoView(this);
        logo.setBadge(true);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(Ui.dp(this, 82), Ui.dp(this, 82));
        llp.bottomMargin = Ui.dp(this, 16);
        box.addView(logo, llp);
        logo.setTranslationY(0);
        logo.animate().translationY(-Ui.dpf(this, 5)).setDuration(2200)
                .setInterpolator(new DecelerateInterpolator())
                .withEndAction(() -> logo.animate().translationY(0).setDuration(2200)
                        .withEndAction(() -> { }).start()).start();

        TextView title = Ui.text(this, getString(R.string.welcome_title), 25, getColor(R.color.text), true);
        title.setGravity(Gravity.CENTER);
        box.addView(title);
        TextView sub = Ui.text(this, getString(R.string.welcome_subtitle), 13,
                getColor(R.color.text_secondary), false);
        sub.setGravity(Gravity.CENTER);
        sub.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 22));
        box.addView(sub);

        String[] suggestions = {
                getString(R.string.welcome_suggestion_1),
                getString(R.string.welcome_suggestion_2),
                getString(R.string.welcome_suggestion_3),
                getString(R.string.welcome_suggestion_4)
        };
        int[] icons = {R.drawable.ic_sparkle, R.drawable.ic_info,
                R.drawable.ic_file, R.drawable.ic_image};
        LinearLayout rows = Ui.column(this);
        for (int r = 0; r < 2; r++) {
            LinearLayout row = Ui.row(this);
            for (int c = 0; c < 2; c++) {
                int index = r * 2 + c;
                LinearLayout card = Ui.row(this);
                card.setBackground(Ui.ripple(Ui.stroke(this, getColor(R.color.surface),
                        getColor(R.color.divider), 0.6f, 15), getColor(R.color.surface_press)));
                card.setPadding(Ui.dp(this, 12), Ui.dp(this, 12), Ui.dp(this, 12), Ui.dp(this, 12));
                ImageView ic = Ui.icon(this, icons[index], getColor(R.color.brand));
                card.addView(ic, new LinearLayout.LayoutParams(Ui.dp(this, 16), Ui.dp(this, 16)));
                TextView tv = Ui.text(this, suggestions[index], 12.5f, getColor(R.color.text), false);
                tv.setMaxLines(2);
                tv.setEllipsize(TextUtils.TruncateAt.END);
                LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0,
                        ViewGroup.LayoutParams.WRAP_CONTENT, 1);
                tlp.leftMargin = Ui.dp(this, 7);
                card.addView(tv, tlp);
                LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, Ui.dp(this, 62), 1);
                clp.rightMargin = c == 0 ? Ui.dp(this, 5) : 0;
                clp.leftMargin = c == 1 ? Ui.dp(this, 5) : 0;
                clp.bottomMargin = Ui.dp(this, 10);
                row.addView(card, clp);
                card.setOnClickListener(v -> {
                    haptic();
                    input.setText(suggestions[index]);
                    sendMessage();
                });
            }
            rows.addView(row);
        }
        box.addView(rows);

        TextView importLink = Ui.text(this, "导入历史对话，继续上次的聊天  ›", 12,
                getColor(R.color.brand), false);
        importLink.setGravity(Gravity.CENTER);
        importLink.setPadding(0, Ui.dp(this, 14), 0, Ui.dp(this, 10));
        importLink.setOnClickListener(v -> importChat());
        box.addView(importLink);

        TextView restoreLink = Ui.text(this, "从完整备份恢复所有对话  ›", 12,
                getColor(R.color.brand), false);
        restoreLink.setGravity(Gravity.CENTER);
        restoreLink.setPadding(0, 0, 0, Ui.dp(this, 4));
        restoreLink.setOnClickListener(v -> restoreAll());
        box.addView(restoreLink);

        TextView hint = Ui.text(this, "直连 DeepSeek 官方 API · 你的 Key 只保存在本机", 10.5f,
                getColor(R.color.text_tertiary), false);
        hint.setGravity(Gravity.CENTER);
        box.addView(hint);
    }

    private void buildComposer() {
        composerWrap = Ui.column(this);
        composerWrap.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 12), Ui.dp(this, 6));
        page.addView(composerWrap, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        warningBanner = buildWarningBanner();
        warningBanner.setVisibility(View.GONE);
        LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        wlp.bottomMargin = Ui.dp(this, 7);
        composerWrap.addView(warningBanner, wlp);

        HorizontalScrollView stripScroll = new HorizontalScrollView(this);
        stripScroll.setHorizontalScrollBarEnabled(false);
        stripScroll.setVisibility(View.GONE);
        attachmentStrip = Ui.row(this);
        stripScroll.addView(attachmentStrip, new HorizontalScrollView.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.bottomMargin = Ui.dp(this, 7);
        composerWrap.addView(stripScroll, slp);
        attachmentStrip.setTag(stripScroll);

        LinearLayout card = Ui.column(this);
        card.setBackground(Ui.stroke(this, getColor(R.color.surface), getColor(R.color.divider),
                0.7f, 22));
        card.setElevation(Ui.dpf(this, 2));
        card.setPadding(Ui.dp(this, 8), Ui.dp(this, 5), Ui.dp(this, 8), Ui.dp(this, 6));
        composerWrap.addView(card, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        input = new EditText(this);
        input.setHint(getString(R.string.input_hint));
        input.setHintTextColor(getColor(R.color.text_tertiary));
        input.setTextColor(getColor(R.color.text));
        input.setTextSize(16);
        input.setTypeface(Ui.regular());
        input.setBackground(null);
        input.setPadding(Ui.dp(this, 7), Ui.dp(this, 7), Ui.dp(this, 7), Ui.dp(this, 4));
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setImeOptions(android.view.inputmethod.EditorInfo.IME_FLAG_NO_ENTER_ACTION
                | android.view.inputmethod.EditorInfo.IME_ACTION_NONE);
        input.setMinLines(1);
        // 限制输入区最大高度，避免长文本时把发送键顶到屏幕/导航键之外（小米三键导航尤其明显）。
        input.setMaxLines(4);
        input.setSingleLine(false);
        input.setGravity(Gravity.TOP | Gravity.START);
        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) { }
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) { updateSendButton(); }
            @Override public void afterTextChanged(Editable s) { }
        });
        input.setOnFocusChangeListener((v, has) -> {
            if (has) scrollBottom(true);
        });
        card.addView(input, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout actions = Ui.row(this);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        View attach = Ui.iconButton(this, R.drawable.ic_attach, getColor(R.color.text_secondary),
                getColor(R.color.surface_alt), getColor(R.color.surface_press));
        attach.setContentDescription("添加照片或文件");
        attach.setOnClickListener(v -> {
            haptic();
            showAttachSheet();
        });
        actions.addView(attach);

        TextView tip = Ui.text(this, "图片、PDF、Word 都能读", 10f, getColor(R.color.text_tertiary), false);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        tlp.leftMargin = Ui.dp(this, 8);
        actions.addView(tip, tlp);

        sendButton = new FrameLayout(this);
        LinearLayout.LayoutParams sblp = new LinearLayout.LayoutParams(Ui.dp(this, 38), Ui.dp(this, 38));
        sendButton.setLayoutParams(sblp);
        sendButton.setBackground(Ui.ripple(Ui.rounded(this, getColor(R.color.brand), 19),
                getColor(R.color.brand_dark)));
        sendButton.setContentDescription("发送");
        sendIcon = new ImageView(this);
        sendIcon.setImageResource(R.drawable.ic_send);
        sendIcon.setColorFilter(Color.WHITE);
        FrameLayout.LayoutParams silp = new FrameLayout.LayoutParams(
                Ui.dp(this, 20), Ui.dp(this, 20));
        silp.gravity = Gravity.CENTER;
        sendButton.addView(sendIcon, silp);
        sendButton.setOnClickListener(v -> {
            haptic();
            if (streaming) stopStreaming(); else sendMessage();
        });
        actions.addView(sendButton);
        card.addView(actions);

        HorizontalScrollView chipScroll = new HorizontalScrollView(this);
        chipScroll.setHorizontalScrollBarEnabled(false);
        chipsRow = Ui.row(this);
        chipsRow.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 1));
        chipScroll.addView(chipsRow);
        composerWrap.addView(chipScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        thinkChip = makeChip(R.drawable.ic_sparkle, "深度思考", () -> toggleThinking());
        thinkChipText = (TextView) thinkChip.getChildAt(1);
        thinkChipIcon = (ImageView) thinkChip.getChildAt(0);
        chipsRow.addView(thinkChip);

        searchChip = makeChip(R.drawable.ic_globe, "联网搜索", () -> toggleSearch());
        searchChipText = (TextView) searchChip.getChildAt(1);
        searchChipIcon = (ImageView) searchChip.getChildAt(0);
        LinearLayout.LayoutParams shlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        shlp.leftMargin = Ui.dp(this, 7);
        chipsRow.addView(searchChip, shlp);

        modelChip = makeChip(R.drawable.ic_sparkle, "V4.1 Flash", this::showModelSheet);
        modelChipText = (TextView) modelChip.getChildAt(1);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mlp.leftMargin = Ui.dp(this, 7);
        chipsRow.addView(modelChip, mlp);
        updateChips();
    }

    private View buildWarningBanner() {
        LinearLayout banner = Ui.row(this);
        banner.setBackground(Ui.rounded(this, getColor(R.color.warning_bg), 14));
        banner.setPadding(Ui.dp(this, 11), Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 8));
        ImageView icon = Ui.icon(this, R.drawable.ic_warning, getColor(R.color.warning_text));
        banner.addView(icon, new LinearLayout.LayoutParams(Ui.dp(this, 16), Ui.dp(this, 16)));
        warningText = Ui.text(this, "", 12, getColor(R.color.warning_text), false);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        tlp.leftMargin = Ui.dp(this, 7);
        banner.addView(warningText, tlp);
        TextView export = Ui.text(this, "导出", 12, getColor(R.color.brand), true);
        export.setPadding(Ui.dp(this, 8), Ui.dp(this, 3), Ui.dp(this, 8), Ui.dp(this, 3));
        export.setOnClickListener(v -> quickExport());
        banner.addView(export);
        ImageView close = Ui.icon(this, R.drawable.ic_close, getColor(R.color.warning_text));
        close.setPadding(Ui.dp(this, 5), Ui.dp(this, 5), Ui.dp(this, 2), Ui.dp(this, 5));
        close.setOnClickListener(v -> {
            warningBanner.setVisibility(View.GONE);
            if (conv != null) {
                conv.warningDismissed = true;
                saveCurrent();
            }
        });
        banner.addView(close, new LinearLayout.LayoutParams(Ui.dp(this, 24), Ui.dp(this, 24)));
        return banner;
    }

    private LinearLayout makeChip(int iconRes, String label, Runnable onClick) {
        LinearLayout chip = Ui.row(this);
        chip.setPadding(Ui.dp(this, 10), Ui.dp(this, 7), Ui.dp(this, 11), Ui.dp(this, 7));
        chip.setBackground(Ui.ripple(Ui.rounded(this, getColor(R.color.surface), 16),
                getColor(R.color.surface_press)));
        ImageView icon = Ui.icon(this, iconRes, getColor(R.color.text_secondary));
        chip.addView(icon, new LinearLayout.LayoutParams(Ui.dp(this, 14), Ui.dp(this, 14)));
        TextView tv = Ui.text(this, label, 12, getColor(R.color.text_secondary), false);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.leftMargin = Ui.dp(this, 5);
        chip.addView(tv, tlp);
        chip.setOnClickListener(v -> onClick.run());
        return chip;
    }

    // --------------------------------------------------------------- insets

    /** 经典三键导航键的保底高度（小米等 ROM 上 systemBars.bottom 可能为 0）。 */
    private int navBarFallback() {
        int id = getResources().getIdentifier("navigation_bar_height", "dimen", "android");
        if (id > 0) {
            try { return getResources().getDimensionPixelSize(id); } catch (Throwable ignore) { }
        }
        return Ui.dp(this, 48);
    }

    private void setupInsets() {
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int top;
            if (Build.VERSION.SDK_INT >= 30) {
                Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                Insets ime = insets.getInsets(WindowInsets.Type.ime());
                Insets nav = insets.getInsets(WindowInsets.Type.navigationBars());
                top = bars.top;
                int navBottom = Math.max(nav.bottom, bars.bottom);
                if (navBottom <= 0) navBottom = navBarFallback();
                navInset = navBottom;
                imeInset = ime.bottom;
                // 关键：判断窗口是否已经为输入法物理缩小。
                // 若 root 的可见高度已随键盘出现而变矮（rootH < 屏幕高 - 导航栏），
                // 说明 adjustResize 生效，绝不能再叠加 ime.bottom，否则长文本时
                // composer 多出一个键盘高度、发送键被顶出屏幕/压到导航键下方。
                evaluateWindowResize();
            } else {
                top = insets.getSystemWindowInsetTop();
                int b = insets.getSystemWindowInsetBottom();
                if (b <= 0) b = navBarFallback();
                navInset = b;
                imeInset = 0;
                windowResizesForIme = true; // minSdk 30，此分支为兼容兜底
            }
            bottomInset = navInset;
            topBar.setPadding(Ui.dp(this, 4), top, Ui.dp(this, 6), 0);
            ViewGroup.LayoutParams lp = topBar.getLayoutParams();
            lp.height = Ui.dp(this, 56) + top;
            topBar.setLayoutParams(lp);
            applyComposerBottomPadding();
            if (drawerPanel != null) drawerPanel.setPadding(0, top, 0, navInset + Ui.dp(this, 12));
            if (previewOverlay != null) previewOverlay.setPadding(0, 0, 0, 0);
            // 输入区高度随可用空间动态收缩，确保发送键永远留在可见区域内。
            v.post(this::clampComposerHeight);
            return insets;
        });
    }

    /**
     * 设置输入区底部内边距：
     * - 窗口已随键盘缩小（adjustResize 生效）→ 只留导航栏高度；
     * - 窗口未缩小（edge-to-edge 覆盖模式）→ 叠加输入法高度把输入栏顶到键盘上方。
     * 无论哪种情况都不会重复计算键盘高度，从根上避免长文本时发送键被挤出。
     */
    private void applyComposerBottomPadding() {
        if (composerWrap == null) return;
        int nav = navInset > 0 ? navInset : navBarFallback();
        int bottom = windowResizesForIme ? nav : Math.max(nav, imeInset);
        int pad = bottom + Ui.dp(this, 6);
        if (composerWrap.getPaddingBottom() != pad) {
            composerWrap.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 12), pad);
        }
    }

    /**
     * 判断窗口是否为输入法物理缩小。root 高度在键盘刚弹出时尚未更新的机型上，
     * 首帧可能误判；watchComposerSpace 会在布局稳定后重新评估并修正。
     */
    private void evaluateWindowResize() {
        int screenH = getResources().getDisplayMetrics().heightPixels;
        int rootH = root.getHeight() > 0 ? root.getHeight() : 0;
        windowResizesForIme = imeInset > 0 && rootH > 0
                && rootH < screenH - Math.max(navInset, 0) / 2;
    }

    /**
     * 限制输入框高度，防止长文本把发送键挤出可见区域。
     * 超出上限的部分由 EditText 自身滚动，发送键始终锚定在输入卡片底部。
     */
    private void clampComposerHeight() {
        if (input == null || page == null || topBar == null || composerWrap == null) return;
        int pageH = page.getHeight();
        if (pageH <= 0) return;
        int padBottom = composerWrap.getPaddingBottom();
        // 输入卡片内除输入框外的固定占用：发送行 38dp + card 内边距 11dp
        // + chips 行 46dp + composer 下边距 6dp。
        int chrome = Ui.dp(this, 38 + 11 + 46 + 6);
        if (warningBanner != null && warningBanner.getVisibility() != View.GONE) {
            chrome += warningBanner.getHeight() + Ui.dp(this, 7);
        }
        Object tag = attachmentStrip != null ? attachmentStrip.getTag() : null;
        if (tag instanceof View) {
            View strip = (View) tag;
            if (strip.getVisibility() != View.GONE && attachmentStrip.getChildCount() > 0) {
                chrome += strip.getHeight() + Ui.dp(this, 7);
            }
        }
        int available = pageH - topBar.getHeight() - padBottom - chrome;
        int target = Math.max(Ui.dp(this, 44), Math.min(available, Ui.dp(this, 128)));
        if (input.getMaxHeight() != target) input.setMaxHeight(target);
    }

    /** 监听页面可用高度变化（键盘弹出/收起、横竖屏切换），同步收缩输入区。 */
    private void watchComposerSpace() {
        page.getViewTreeObserver().addOnGlobalLayoutListener(
                new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
                    private int lastH = -1;
                    private int lastIme = -1;
                    @Override public void onGlobalLayout() {
                        int h = page.getHeight();
                        if (h <= 0) return;
                        boolean imeChanged = imeInset != lastIme;
                        // 键盘状态变化或页面高度变化（旋转/分屏/软键盘 resize）时，
                        // 重新评估窗口是否缩小，修正 insets 回调首帧的误判。
                        if (imeChanged || h != lastH) {
                            lastH = h;
                            lastIme = imeInset;
                            evaluateWindowResize();
                            applyComposerBottomPadding();
                            clampComposerHeight();
                        }
                    }
                });
    }

    // --------------------------------------------------------------- drawer

    private void buildDrawer() {
        drawerScrim = new View(this);
        drawerScrim.setBackgroundColor(getColor(R.color.scrim));
        drawerScrim.setAlpha(0f);
        drawerScrim.setVisibility(View.GONE);
        drawerScrim.setOnClickListener(v -> closeDrawer());
        root.addView(drawerScrim, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        int width = Math.min((int) (getResources().getDisplayMetrics().widthPixels * 0.84f),
                Ui.dp(this, 340));
        LinearLayout panel = Ui.column(this);
        panel.setBackground(Ui.rounded(this, getColor(R.color.surface), new float[]{0, 22, 22, 0}));
        panel.setElevation(Ui.dpf(this, 18));
        panel.setPadding(0, 0, 0, Ui.dp(this, 12));
        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(width, ViewGroup.LayoutParams.MATCH_PARENT);
        plp.gravity = Gravity.START;
        panel.setTranslationX(-width);
        root.addView(panel, plp);
        drawerPanel = panel;

        LinearLayout head = Ui.row(this);
        head.setPadding(Ui.dp(this, 18), Ui.dp(this, 18), Ui.dp(this, 14), Ui.dp(this, 10));
        LogoView logo = new LogoView(this);
        logo.setBadge(true);
        head.addView(logo, new LinearLayout.LayoutParams(Ui.dp(this, 38), Ui.dp(this, 38)));
        LinearLayout nameCol = Ui.column(this);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        nlp.leftMargin = Ui.dp(this, 10);
        head.addView(nameCol, nlp);
        nameCol.addView(Ui.text(this, "大肥鱼", 18, getColor(R.color.text), true));
        nameCol.addView(Ui.text(this, "DeepSeek V4.1 · API 直连", 10.5f,
                getColor(R.color.text_tertiary), false));
        ImageView close = Ui.icon(this, R.drawable.ic_close, getColor(R.color.text_secondary));
        close.setPadding(Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 8));
        close.setOnClickListener(v -> closeDrawer());
        head.addView(close, new LinearLayout.LayoutParams(Ui.dp(this, 34), Ui.dp(this, 34)));
        panel.addView(head);

        LinearLayout newBtn = Ui.row(this);
        newBtn.setGravity(Gravity.CENTER);
        newBtn.setBackground(Ui.ripple(Ui.rounded(this, getColor(R.color.brand_soft), 13),
                getColor(R.color.brand_soft_2)));
        newBtn.setPadding(Ui.dp(this, 12), Ui.dp(this, 12), Ui.dp(this, 12), Ui.dp(this, 12));
        ImageView plus = Ui.icon(this, R.drawable.ic_plus, getColor(R.color.brand));
        newBtn.addView(plus, new LinearLayout.LayoutParams(Ui.dp(this, 17), Ui.dp(this, 17)));
        TextView newTv = Ui.text(this, "开启新对话", 14, getColor(R.color.brand), true);
        LinearLayout.LayoutParams ntlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ntlp.leftMargin = Ui.dp(this, 6);
        newBtn.addView(newTv, ntlp);
        LinearLayout.LayoutParams nblp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nblp.setMargins(Ui.dp(this, 14), 0, Ui.dp(this, 14), Ui.dp(this, 9));
        panel.addView(newBtn, nblp);
        newBtn.setOnClickListener(v -> {
            closeDrawer();
            requestNewChat();
        });

        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        drawerList = Ui.column(this);
        drawerList.setPadding(Ui.dp(this, 10), Ui.dp(this, 4), Ui.dp(this, 10), Ui.dp(this, 8));
        scroll.addView(drawerList);
        panel.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        View divider = new View(this);
        divider.setBackgroundColor(getColor(R.color.divider));
        panel.addView(divider, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 1)));

        LinearLayout footer = Ui.column(this);
        footer.setPadding(Ui.dp(this, 10), Ui.dp(this, 6), Ui.dp(this, 10), 0);
        footer.addView(drawerFooterRow(R.drawable.ic_settings, "设置", v -> {
            closeDrawer();
            openSettings();
        }));
        footer.addView(drawerFooterRow(R.drawable.ic_download, "导入对话", v -> importChat()));
        footer.addView(drawerFooterRow(R.drawable.ic_info, "关于大肥鱼", v -> showAbout()));
        panel.addView(footer);
    }

    private View drawerFooterRow(int icon, String label, View.OnClickListener click) {
        LinearLayout row = Ui.row(this);
        row.setPadding(Ui.dp(this, 12), Ui.dp(this, 10), Ui.dp(this, 12), Ui.dp(this, 10));
        row.setBackground(Ui.ripple(Ui.rounded(this, 0, 10), getColor(R.color.surface_press)));
        ImageView iv = Ui.icon(this, icon, getColor(R.color.text_secondary));
        row.addView(iv, new LinearLayout.LayoutParams(Ui.dp(this, 18), Ui.dp(this, 18)));
        TextView tv = Ui.text(this, label, 13, getColor(R.color.text), false);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.leftMargin = Ui.dp(this, 10);
        row.addView(tv, tlp);
        row.setOnClickListener(click);
        return row;
    }

    private void openDrawer() {
        if (drawerOpen) return;
        rebuildDrawer();
        drawerOpen = true;
        drawerScrim.setVisibility(View.VISIBLE);
        drawerScrim.animate().alpha(1f).setDuration(180).start();
        drawerPanel.animate().translationX(0).setDuration(220)
                .setInterpolator(new DecelerateInterpolator()).start();
    }

    private void closeDrawer() {
        if (!drawerOpen) return;
        drawerOpen = false;
        drawerScrim.animate().alpha(0f).setDuration(160)
                .withEndAction(() -> drawerScrim.setVisibility(View.GONE)).start();
        drawerPanel.animate().translationX(-drawerPanel.getWidth()).setDuration(200).start();
    }

    private void rebuildDrawer() {
        if (drawerList == null) return;
        drawerList.removeAllViews();
        ArrayList<Store.Entry> list = store.list();
        if (list.isEmpty()) {
            TextView empty = Ui.text(this, "还没有对话\n从下方输入框开始吧", 12.5f,
                    getColor(R.color.text_tertiary), false);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, Ui.dp(this, 30), 0, Ui.dp(this, 30));
            drawerList.addView(empty);
            return;
        }
        String group = "";
        for (Store.Entry e : list) {
            String g = groupOf(e.updatedAt);
            if (!g.equals(group)) {
                group = g;
                TextView h = Ui.text(this, g, 10.5f, getColor(R.color.text_tertiary), true);
                h.setPadding(Ui.dp(this, 8), Ui.dp(this, 12), 0, Ui.dp(this, 4));
                drawerList.addView(h);
            }
            LinearLayout row = Ui.column(this);
            row.setPadding(Ui.dp(this, 10), Ui.dp(this, 9), Ui.dp(this, 10), Ui.dp(this, 9));
            boolean selected = conv != null && conv.id.equals(e.id);
            row.setBackground(Ui.ripple(Ui.rounded(this,
                    selected ? getColor(R.color.brand_soft) : 0, 11),
                    getColor(R.color.surface_press)));
            TextView t = Ui.text(this, e.title == null || e.title.isEmpty() ? "新对话" : e.title,
                    13.5f, selected ? getColor(R.color.brand) : getColor(R.color.text),
                    selected);
            t.setMaxLines(1);
            t.setEllipsize(TextUtils.TruncateAt.END);
            row.addView(t);
            TextView time = Ui.text(this, Ui.relative(e.updatedAt), 10.5f,
                    getColor(R.color.text_tertiary), false);
            time.setPadding(0, Ui.dp(this, 3), 0, 0);
            row.addView(time);
            row.setOnClickListener(v -> {
                closeDrawer();
                openConversation(e.id);
            });
            row.setOnLongClickListener(v -> {
                v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                showConversationActions(e);
                return true;
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = Ui.dp(this, 2);
            drawerList.addView(row, lp);
        }
    }

    private String groupOf(long ms) {
        long diff = System.currentTimeMillis() - ms;
        if (diff < 24L * 3600 * 1000 && Ui.dayLabel(ms).equals("今天")) return "今天";
        if (diff < 7L * 24 * 3600 * 1000) return "最近 7 天";
        return "更早";
    }

    // ------------------------------------------------------- conversation

    private void openLastConversation() {
        String id = prefs.lastConvId();
        if (!id.isEmpty()) {
            Conv c = store.load(id);
            if (c != null && !c.messages.isEmpty()) {
                conv = c;
                applyConversationPrefs(c);
                renderConversation();
                return;
            }
        }
        conv = null;
        renderConversation();
    }

    private void applyConversationPrefs(Conv c) {
        c.applyDefaults(prefs);
        if (c.model != null && !c.model.isEmpty()) prefs.setModel(c.model);
        prefs.setThinking(c.thinking);
        prefs.setSearch(c.search);
        if (c.contextLimit > 0) prefs.setContextLimit(c.contextLimit);
    }

    private void openConversation(String id) {
        if (streaming) {
            snack("请先停止当前生成", "停止", this::stopStreaming);
            return;
        }
        saveCurrent();
        Conv c = store.load(id);
        if (c == null) {
            snack("对话加载失败", null, null);
            return;
        }
        conv = c;
        applyConversationPrefs(c);
        prefs.setLastConvId(c.id);
        renderConversation();
        updateEverything();
        maybeWarn();
        input.requestFocus();
    }

    private void requestNewChat() {
        if (streaming) {
            snack("请先停止当前生成", "停止", this::stopStreaming);
            return;
        }
        saveCurrent();
        conv = null;
        prefs.setLastConvId("");
        pending.clear();
        input.setText("");
        updateAttachmentStrip();
        messagesContainer.removeAllViews();
        messageViews.clear();
        aiNote = null;
        lastDayRendered = "";
        welcomeView.setVisibility(View.VISIBLE);
        if (jumpButton != null) jumpButton.setVisibility(View.GONE);
        chatScroll.scrollTo(0, 0);
        updateEverything();
        hideKeyboard();
    }

    private void saveCurrent() {
        if (conv != null && !conv.messages.isEmpty()) {
            store.save(conv);
            prefs.setLastConvId(conv.id);
        }
    }

    private void renderConversation() {
        messagesContainer.removeAllViews();
        messageViews.clear();
        aiNote = null;
        lastDayRendered = "";
        if (conv == null || conv.messages.isEmpty()) {
            welcomeView.setVisibility(View.VISIBLE);
            if (jumpButton != null) jumpButton.setVisibility(View.GONE);
            updateEverything();
            return;
        }
        welcomeView.setVisibility(View.GONE);
        for (Msg m : conv.messages) {
            addDateIfNeeded(m.createdAt);
            MessageView v = new MessageView(this, m, messageCallbacks);
            messagesContainer.addView(v);
            messageViews.put(m.id, v);
        }
        Msg last = conv.last();
        if (last != null && last.isAssistant() && last.hasVisibleContent()
                && last.status == Msg.STATUS_COMPLETE) {
            addAiNote();
        }
        updateEverything();
        scrollBottom(false);
    }

    private void addDateIfNeeded(long time) {
        String day = Ui.dayLabel(time);
        if (!day.equals(lastDayRendered)) {
            lastDayRendered = day;
            TextView chip = Ui.text(this, day, 10.5f, getColor(R.color.text_secondary), false);
            chip.setGravity(Gravity.CENTER);
            chip.setBackground(Ui.rounded(this, getColor(R.color.surface_alt), 10));
            chip.setPadding(Ui.dp(this, 9), Ui.dp(this, 3), Ui.dp(this, 9), Ui.dp(this, 3));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.gravity = Gravity.CENTER_HORIZONTAL;
            lp.topMargin = Ui.dp(this, 10);
            lp.bottomMargin = Ui.dp(this, 4);
            messagesContainer.addView(chip, lp);
        }
    }

    private void appendMessageView(Msg m) {
        welcomeView.setVisibility(View.GONE);
        if (aiNote != null) {
            messagesContainer.removeView(aiNote);
            aiNote = null;
        }
        addDateIfNeeded(m.createdAt);
        MessageView v = new MessageView(this, m, messageCallbacks);
        messagesContainer.addView(v);
        messageViews.put(m.id, v);
    }

    private void addAiNote() {
        if (aiNote != null) messagesContainer.removeView(aiNote);
        aiNote = Ui.text(this, getString(R.string.ai_note), 10.5f,
                getColor(R.color.text_tertiary), false);
        ((TextView) aiNote).setGravity(Gravity.CENTER);
        ((TextView) aiNote).setPadding(0, Ui.dp(this, 12), 0, Ui.dp(this, 18));
        messagesContainer.addView(aiNote);
    }

    private void scrollBottom(boolean smooth) {
        chatScroll.post(() -> {
            int y = Math.max(0, messagesContainer.getHeight() - chatScroll.getHeight());
            if (smooth) chatScroll.smoothScrollTo(0, y);
            else chatScroll.scrollTo(0, y);
            userNearBottom = true;
        });
    }

    private void updateEverything() {
        updateTitle();
        updateContextPill();
        updateExportVisibility();
        updateChips();
        updateSendButton();
        updateWarningBanner();
    }

    private void updateTitle() {
        if (conv == null || conv.messages.isEmpty()) {
            titleView.setText("大肥鱼");
            subtitleView.setText("DeepSeek API 直连");
        } else {
            titleView.setText(conv.titleOr("新对话"));
            String m = prefs.model();
            String model = "deepseek-flash".equals(m) ? "V4.1 Flash"
                    : (m != null && m.contains("pro") ? "V4 Pro" : m);
            subtitleView.setText(model + " · " + (prefs.thinking() ? "深度思考" : "快速回答")
                    + (prefs.search() ? " · 联网" : ""));
        }
    }

    private void updateContextPill() {
        if (conv == null || conv.messages.isEmpty()) {
            contextPill.setText(TokenMeter.limitLabel(prefs.contextLimit()) + " 上下文");
            contextPill.setTextColor(getColor(R.color.brand));
            contextPill.setBackground(Ui.rounded(this, getColor(R.color.brand_soft), 14));
            return;
        }
        int rounds = TokenMeter.roundsLeft(conv, prefs.maxTokens());
        int threshold = prefs.warnThreshold();
        String text;
        if (rounds <= 0) text = "上下文已满";
        else if (rounds > 999) text = "≈ 999+ 轮";
        else text = "≈ " + rounds + " 轮";
        contextPill.setText(text);
        if (rounds <= 3) {
            contextPill.setTextColor(getColor(R.color.danger));
            contextPill.setBackground(Ui.rounded(this, getColor(R.color.danger_soft), 14));
        } else if (rounds <= threshold) {
            contextPill.setTextColor(getColor(R.color.warning_text));
            contextPill.setBackground(Ui.rounded(this, getColor(R.color.warning_bg), 14));
        } else {
            contextPill.setTextColor(getColor(R.color.brand));
            contextPill.setBackground(Ui.rounded(this, getColor(R.color.brand_soft), 14));
        }
    }

    private void updateExportVisibility() {
        if (exportButton == null) return;
        exportButton.setVisibility(conv != null && !conv.messages.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void updateChips() {
        paintChip(thinkChip, thinkChipIcon, thinkChipText, prefs.thinking());
        paintChip(searchChip, searchChipIcon, searchChipText, prefs.search());
        modelChipText.setText((prefs.model().contains("pro") ? "V4 Pro" : "V4.1 Flash") + "  ⌄");
        modelChipText.setTextColor(getColor(R.color.text_secondary));
        modelChip.setBackground(Ui.ripple(Ui.rounded(this, getColor(R.color.surface), 16),
                getColor(R.color.surface_press)));
        ImageView mi = (ImageView) modelChip.getChildAt(0);
        mi.setColorFilter(getColor(R.color.text_secondary));
    }

    private void paintChip(LinearLayout chip, ImageView icon, TextView label, boolean active) {
        int fg = active ? getColor(R.color.brand) : getColor(R.color.text_secondary);
        label.setTextColor(fg);
        icon.setColorFilter(fg);
        chip.setBackground(Ui.ripple(Ui.rounded(this,
                active ? getColor(R.color.brand_soft) : getColor(R.color.surface), 16),
                getColor(R.color.surface_press)));
        label.setTypeface(active ? Ui.medium() : Ui.regular());
    }

    private void updateSendButton() {
        if (sendButton == null) return;
        boolean has = input != null && !input.getText().toString().trim().isEmpty();
        if (streaming) {
            sendIcon.setImageResource(R.drawable.ic_stop);
            sendIcon.setColorFilter(getColor(R.color.text));
            sendButton.setBackground(Ui.ripple(Ui.rounded(this, getColor(R.color.surface_alt), 19),
                    getColor(R.color.surface_press)));
            sendButton.setAlpha(1f);
            sendButton.setContentDescription("停止生成");
        } else {
            sendIcon.setImageResource(R.drawable.ic_send);
            sendIcon.setColorFilter(Color.WHITE);
            sendButton.setBackground(Ui.ripple(Ui.rounded(this, getColor(R.color.brand), 19),
                    getColor(R.color.brand_dark)));
            sendButton.setAlpha(has || !pending.isEmpty() ? 1f : 0.45f);
            sendButton.setContentDescription("发送");
        }
    }

    private void toggleThinking() {
        if (streaming) {
            snack("生成过程中不能切换", null, null);
            return;
        }
        haptic();
        prefs.setThinking(!prefs.thinking());
        if (conv != null) conv.thinking = prefs.thinking();
        updateChips();
        updateTitle();
        saveCurrent();
    }

    private void toggleSearch() {
        if (streaming) {
            snack("生成过程中不能切换", null, null);
            return;
        }
        haptic();
        prefs.setSearch(!prefs.search());
        if (conv != null) conv.search = prefs.search();
        updateChips();
        updateTitle();
        saveCurrent();
    }

    // --------------------------------------------------------------- send

    private void sendMessage() {
        if (streaming) { stopStreaming(); return; }
        String text = input.getText().toString().trim();
        if (text.isEmpty() && pending.isEmpty()) return;
        if (!prefs.hasApiKey()) {
            snack("请先设置 DeepSeek API Key", "去设置", this::openSettings);
            openOnboarding();
            return;
        }
        if (prefs.model().contains("pro")) {
            boolean hasImage = false;
            for (Att a : pending) if (a.isImage()) hasImage = true;
            if (conv != null) {
                for (Msg m : conv.messages) {
                    for (Att a : m.attachments) if (a.isImage()) hasImage = true;
                }
            }
            if (hasImage) {
                showConfirm("需要切换模型", "V4 Pro 不支持图片输入。大肥鱼 V4.1 Flash 支持图片，切换后继续？",
                        "切换并发送", false, () -> {
                            prefs.setModel("deepseek-flash");
                            updateChips();
                            updateTitle();
                            sendMessage();
                        });
                return;
            }
        }
        ArrayList<Att> atts = new ArrayList<>(pending);
        input.setText("");
        pending.clear();
        updateAttachmentStrip();
        hideKeyboard();
        prepareSend(text, atts);
    }

    private void prepareSend(String text, ArrayList<Att> atts) {
        haptic();
        if (atts.isEmpty()) {
            commitUserMessage(text, atts);
            return;
        }
        showBusy("正在准备附件…");
        uploadNext(text, atts, 0);
    }

    private void uploadNext(String text, ArrayList<Att> atts, int index) {
        if (index >= atts.size()) {
            hideBusy();
            commitUserMessage(text, atts);
            return;
        }
        Att a = atts.get(index);
        if (a.fileId != null && !a.fileId.isEmpty()) {
            uploadNext(text, atts, index + 1);
            return;
        }
        File f = store.attachmentFile(a.localName);
        if (f == null || !f.isFile()) {
            uploadFailed(text, atts, "找不到附件文件");
            return;
        }
        showBusy("正在上传 " + (index + 1) + "/" + atts.size() + " · " + a.name);
        api.uploadFile(f, a.name, a.mime, a.purpose, prefs, new ApiClient.UploadCallback() {
            @Override public void onProgress(int percent) {
                showBusy("正在上传 " + (index + 1) + "/" + atts.size()
                        + " · " + a.name + " " + percent + "%");
            }

            @Override public void onDone(String fileId) {
                a.fileId = fileId;
                a.state = 2;
                uploadNext(text, atts, index + 1);
            }

            @Override public void onError(String message) {
                uploadFailed(text, atts, message);
            }
        });
    }

    private void uploadFailed(String text, ArrayList<Att> atts, String message) {
        hideBusy();
        input.setText(text);
        input.setSelection(input.length());
        pending.clear();
        pending.addAll(atts);
        updateAttachmentStrip();
        showConfirm("附件上传失败", message + "\n\n可以重试，或移除附件后先发文字。",
                "重试", true, () -> prepareSend(text, atts));
    }

    private void commitUserMessage(String text, ArrayList<Att> atts) {
        if (conv == null) {
            conv = store.create(prefs);
            conv.contextLimit = prefs.contextLimit();
        }
        Msg user = new Msg();
        user.role = Msg.ROLE_USER;
        user.content = text;
        user.attachments = atts;
        user.thinking = prefs.thinking();
        user.search = prefs.search();
        user.model = prefs.model();
        user.createdAt = System.currentTimeMillis();
        conv.messages.add(user);
        conv.thinking = prefs.thinking();
        conv.search = prefs.search();
        conv.model = prefs.model();
        if (conv.title == null || conv.title.trim().isEmpty()) {
            conv.title = fallbackTitle(text);
            conv.titleAuto = true;
        }
        conv.touch();
        prefs.setLastConvId(conv.id);
        welcomeView.setVisibility(View.GONE);
        appendMessageView(user);
        saveCurrent();
        updateEverything();
        scrollBottom(true);
        startAssistant();
    }

    private String fallbackTitle(String text) {
        String t = text == null ? "" : text.trim().replace('\n', ' ');
        if (t.isEmpty()) t = "新的对话";
        return t.length() > 20 ? t.substring(0, 20) + "…" : t;
    }

    private void startAssistant() {
        if (conv == null || conv.messages.isEmpty()) return;
        Msg a = new Msg();
        a.role = Msg.ROLE_ASSISTANT;
        a.status = Msg.STATUS_STREAMING;
        a.thinking = prefs.thinking();
        a.search = prefs.search();
        a.model = prefs.model();
        a.createdAt = System.currentTimeMillis();
        conv.messages.add(a);
        conv.touch();
        saveCurrent();
        streaming = true;
        streamingMsg = a;
        appendMessageView(a);
        streamingView = messageViews.get(a.id);
        updateSendButton();
        thinkingStart = System.currentTimeMillis();
        scrollBottom(true);
        api.sendChat(conv, prefs, chatCallback());
    }

    private ApiClient.ChatCallback chatCallback() {
        final Msg m = streamingMsg;
        return new ApiClient.ChatCallback() {
            @Override public void onStarted() {
                if (m != streamingMsg) return;
            }

            // 所有 SSE 回调都来自 ApiClient 的后台线程池，必须切回主线程后再操作
            // Msg 对象与 UI，否则会 CalledFromWrongThreadException / ConcurrentModificationException。
            @Override public void onReasoning(String delta) {
                ui.post(() -> {
                    if (m != streamingMsg) return;
                    m.reasoning = m.reasoning + delta;
                    scheduleStreamRefresh();
                });
            }

            @Override public void onContent(String delta) {
                ui.post(() -> {
                    if (m != streamingMsg) return;
                    m.content = m.content + delta;
                    scheduleStreamRefresh();
                });
            }

            @Override public void onToolEvent(JSONObject event) {
                ui.post(() -> {
                    if (m != streamingMsg || event == null) return;
                    String type = event.optString("type", "");
                    JSONObject call = event.optJSONObject("call");
                    if (call == null) return;
                    ToolCall tc = ToolCall.fromCall(call, event.optInt("step", 0));
                    if ("call_started".equals(type)) {
                        tc.status = "running";
                        m.tools.add(tc);
                    } else {
                        boolean found = false;
                        for (int i = 0; i < m.tools.size(); i++) {
                            if (!m.tools.get(i).id.isEmpty() && m.tools.get(i).id.equals(tc.id)) {
                                m.tools.set(i, tc);
                                found = true;
                                break;
                            }
                        }
                        if (!found) m.tools.add(tc);
                    }
                    scheduleStreamRefresh();
                });
            }

            @Override public void onTitle(String title) {
                ui.post(() -> {
                    if (m != streamingMsg || title == null || title.trim().isEmpty()) return;
                    conv.title = title.trim();
                    conv.titleAuto = true;
                    updateTitle();
                    saveCurrent();
                });
            }

            @Override public void onUsage(int promptTokens, int completionTokens) {
                ui.post(() -> {
                    if (m != streamingMsg) return;
                    m.promptTokens = promptTokens;
                    m.completionTokens = completionTokens;
                });
            }

            @Override public void onFinal(JSONArray calls, JSONArray annotations, String finishReason) {
                ui.post(() -> {
                    if (m != streamingMsg) return;
                    m.finishReason = finishReason == null ? "" : finishReason;
                    m.serverToolCalls = calls == null ? "[]" : calls.toString();
                    m.annotations = annotations == null ? "[]" : annotations.toString();
                    if (calls != null && calls.length() > 0) {
                        m.tools = ToolCall.fromServerCalls(calls);
                    }
                    m.cites = Cite.fromAnnotations(annotations);
                    scheduleStreamRefresh();
                });
            }

            @Override public void onComplete() {
                ui.post(() -> {
                    if (m != streamingMsg) return;
                    finishAssistant(m, null);
                });
            }

            @Override public void onError(String message, int httpCode, boolean contextError) {
                ui.post(() -> {
                    if (m != streamingMsg) return;
                    m.status = Msg.STATUS_ERROR;
                    m.error = message;
                    m.thinkingMs = prefs.thinking() ? System.currentTimeMillis() - thinkingStart : 0;
                    streaming = false;
                    streamingMsg = null;
                    updateSendButton();
                    if (streamingView != null) streamingView.refresh();
                    streamingView = null;
                    if (conv != null) {
                        conv.touch();
                        saveCurrent();
                    }
                    updateEverything();
                    snack(message, "重试", () -> retryMessage(m));
                    if (contextError) showContextFullDialog();
                    if (httpCode == 401) openSettings();
                });
            }

            @Override public void onCancelled() {
                ui.post(() -> {
                    if (m != streamingMsg) return;
                    if (m.content.trim().isEmpty() && m.reasoning.trim().isEmpty()) {
                        if (conv != null) conv.messages.remove(m);
                        if (streamingView != null) messagesContainer.removeView(streamingView);
                        renderConversation();
                    } else {
                        m.status = Msg.STATUS_COMPLETE;
                        m.finishReason = "cancelled";
                        m.thinkingMs = prefs.thinking() ? System.currentTimeMillis() - thinkingStart : 0;
                        if (streamingView != null) streamingView.refresh();
                        addAiNote();
                    }
                    streaming = false;
                    streamingMsg = null;
                    streamingView = null;
                    updateSendButton();
                    if (conv != null) {
                        conv.touch();
                        saveCurrent();
                    }
                    updateEverything();
                    snack("已停止生成", null, null);
                });
            }
        };
    }

    private void finishAssistant(Msg m, String forcedError) {
        if (forcedError != null) {
            m.status = Msg.STATUS_ERROR;
            m.error = forcedError;
        } else if ("content_filter".equals(m.finishReason)) {
            m.status = Msg.STATUS_ERROR;
            m.error = "内容被安全策略拦截，请换一种方式提问。";
        } else if (m.content.trim().isEmpty() && m.reasoning.trim().isEmpty() && m.tools.isEmpty()) {
            m.status = Msg.STATUS_ERROR;
            m.error = "没有收到内容，请重试。";
        } else {
            m.status = Msg.STATUS_COMPLETE;
        }
        m.thinkingMs = prefs.thinking() ? System.currentTimeMillis() - thinkingStart : 0;
        if (conv != null) {
            conv.lastPromptTokens = m.promptTokens;
            conv.lastCompletionTokens = m.completionTokens;
            if ((conv.title == null || conv.title.trim().isEmpty()) && !conv.messages.isEmpty()) {
                for (Msg x : conv.messages) {
                    if (x.isUser() && x.content != null && !x.content.trim().isEmpty()) {
                        conv.title = fallbackTitle(x.content);
                        break;
                    }
                }
            }
            conv.touch();
            saveCurrent();
        }
        streaming = false;
        streamingMsg = null;
        updateSendButton();
        if (streamingView != null) streamingView.refresh();
        streamingView = null;
        if (m.status == Msg.STATUS_COMPLETE) addAiNote();
        updateEverything();
        if ("length".equals(m.finishReason)) snack("回答达到长度上限", null, null);
        maybeWarn();
    }

    private void scheduleStreamRefresh() {
        if (streamRefresh != null) ui.removeCallbacks(streamRefresh);
        streamRefresh = () -> {
            if (streamingView != null) streamingView.refresh();
            if (userNearBottom) scrollBottom(false);
        };
        ui.postDelayed(streamRefresh, 110);
    }

    private void stopStreaming() {
        if (!streaming) return;
        api.cancelChat();
        snack("正在停止…", null, null);
    }

    private void retryMessage(Msg assistant) {
        if (conv == null || streaming) return;
        int idx = conv.messages.indexOf(assistant);
        if (idx < 0) return;
        int userIdx = -1;
        for (int i = idx - 1; i >= 0; i--) {
            if (conv.messages.get(i).isUser()) { userIdx = i; break; }
        }
        if (userIdx < 0) return;
        while (conv.messages.size() > userIdx + 1) conv.messages.remove(conv.messages.size() - 1);
        conv.touch();
        saveCurrent();
        renderConversation();
        startAssistant();
    }

    // ---------------------------------------------------------- warnings

    private void maybeWarn() {
        if (conv == null || conv.messages.isEmpty()) return;
        int rounds = TokenMeter.roundsLeft(conv, prefs.maxTokens());
        int threshold = prefs.warnThreshold();
        int level = rounds <= 3 ? 2 : (rounds <= threshold ? 1 : 0);
        int old = conv.warnLevel;
        if (level > old) {
            conv.warnLevel = level;
            conv.warningDismissed = false;
            saveCurrent();
            if (!prefs.warningDialogShown(conv.id + "_" + level)) {
                prefs.setWarningDialogShown(conv.id + "_" + level, true);
                showContextWarning(rounds, level);
            }
        } else if (level < old) {
            conv.warnLevel = level;
            conv.warningDismissed = false;
            saveCurrent();
        }
        updateWarningBanner();
        updateContextPill();
    }

    private void updateWarningBanner() {
        if (warningBanner == null) return;
        if (conv == null || conv.messages.isEmpty() || conv.warnLevel <= 0 || conv.warningDismissed) {
            warningBanner.setVisibility(View.GONE);
            return;
        }
        int rounds = TokenMeter.roundsLeft(conv, prefs.maxTokens());
        warningBanner.setVisibility(View.VISIBLE);
        if (conv.warnLevel >= 2) {
            warningText.setText("上下文快满了 · 仅剩约 " + rounds + " 轮，建议立即导出并开新对话");
        } else {
            warningText.setText("上下文将满 · 按当前节奏还可聊约 " + rounds + " 轮");
        }
    }

    private void showContextWarning(int rounds, int level) {
        String title = level >= 2 ? "仅剩约 " + rounds + " 轮" : "上下文快满了";
        String msg = level >= 2
                ? "按当前对话的平均长度估算，只剩约 " + rounds + " 轮。现在导出对话最稳妥，之后可以随时导入，继续聊下去。"
                : "按当前节奏，还能聊约 " + rounds + " 轮。上下文用满后模型将无法继续，建议先导出一份备份。";
        showConfirm(title, msg, "导出备份", false, () -> {
            quickExport();
        });
    }

    private void showContextFullDialog() {
        showConfirm("内容超出上下文",
                "这次请求的内容超过了模型可接收的长度。大文件（如几十页的 PDF）解析后会占用大量 Token，建议：\n\n"
                        + "1) 把长文档拆成几个小文件分开发送；\n"
                        + "2) 在设置里调低“最大输出”，或调高“上下文上限”；\n"
                        + "3) 另开新对话再试。\n\n如果经常误报，可在设置中手动调高上下文上限。",
                "知道了", false, null);
        if (conv != null) {
            conv.warnLevel = 2;
            conv.warningDismissed = false;
            // 不再伪造 lastPromptTokens：之前把它顶到上限会让后续每轮都被误判为“已满”。
            saveCurrent();
            updateContextPill();
            updateWarningBanner();
        }
    }

    // -------------------------------------------------------- attachments

    private void updateAttachmentStrip() {
        if (attachmentStrip == null) return;
        attachmentStrip.removeAllViews();
        HorizontalScrollView scroll = (HorizontalScrollView) attachmentStrip.getTag();
        if (pending.isEmpty()) {
            if (scroll != null) scroll.setVisibility(View.GONE);
            updateSendButton();
            return;
        }
        if (scroll != null) scroll.setVisibility(View.VISIBLE);
        for (Att a : pending) {
            if (a.isImage()) {
                FrameLayout wrap = new FrameLayout(this);
                ImageView iv = new ImageView(this);
                iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                iv.setBackground(Ui.rounded(this, getColor(R.color.surface_alt), 12));
                iv.setClipToOutline(true);
                File f = store.attachmentFile(a.localName);
                if (f != null) ImageLoader.load(f, iv, 180, getColor(R.color.surface_alt));
                FrameLayout.LayoutParams ilp = new FrameLayout.LayoutParams(Ui.dp(this, 56), Ui.dp(this, 56));
                wrap.addView(iv, ilp);
                View del = removeBadge();
                FrameLayout.LayoutParams dlp = new FrameLayout.LayoutParams(Ui.dp(this, 19), Ui.dp(this, 19));
                dlp.gravity = Gravity.TOP | Gravity.END;
                wrap.addView(del, dlp);
                del.setOnClickListener(v -> {
                    pending.remove(a);
                    updateAttachmentStrip();
                });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        Ui.dp(this, 62), Ui.dp(this, 62));
                lp.rightMargin = Ui.dp(this, 6);
                attachmentStrip.addView(wrap, lp);
            } else {
                LinearLayout chip = Ui.row(this);
                chip.setBackground(Ui.stroke(this, getColor(R.color.surface),
                        getColor(R.color.divider), 0.7f, 12));
                chip.setPadding(Ui.dp(this, 8), Ui.dp(this, 7), Ui.dp(this, 6), Ui.dp(this, 7));
                ImageView icon = Ui.icon(this, R.drawable.ic_file, getColor(R.color.brand));
                chip.addView(icon, new LinearLayout.LayoutParams(Ui.dp(this, 18), Ui.dp(this, 18)));
                LinearLayout col = Ui.column(this);
                TextView name = Ui.text(this, a.name, 11.5f, getColor(R.color.text), false);
                name.setMaxLines(1);
                name.setEllipsize(TextUtils.TruncateAt.MIDDLE);
                col.addView(name);
                col.addView(Ui.text(this, FileUtils.humanSize(a.size), 9.5f,
                        getColor(R.color.text_tertiary), false));
                LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(Ui.dp(this, 120),
                        ViewGroup.LayoutParams.WRAP_CONTENT);
                clp.leftMargin = Ui.dp(this, 6);
                chip.addView(col, clp);
                View del = removeBadge();
                FrameLayout.LayoutParams dlp = new FrameLayout.LayoutParams(Ui.dp(this, 18), Ui.dp(this, 18));
                dlp.gravity = Gravity.CENTER_VERTICAL;
                del.setOnClickListener(v -> {
                    pending.remove(a);
                    updateAttachmentStrip();
                });
                chip.addView(del);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(this, 54));
                lp.rightMargin = Ui.dp(this, 6);
                attachmentStrip.addView(chip, lp);
            }
        }
        updateSendButton();
    }

    private View removeBadge() {
        FrameLayout f = new FrameLayout(this);
        f.setBackground(Ui.ripple(Ui.rounded(this, 0xCC000000, 10), 0x44FFFFFF));
        ImageView x = Ui.icon(this, R.drawable.ic_close, Color.WHITE);
        int p = Ui.dp(this, 4);
        f.addView(x, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        x.setPadding(p, p, p, p);
        return f;
    }

    private void showAttachSheet() {
        LinearLayout box = Ui.column(this);
        box.addView(sheetTitle("添加内容"));
        box.addView(sheetRow(R.drawable.ic_image, "照片", "从相册选择，最多 10 张", v -> {
            dismissSheet();
            startPhotoPicker();
        }));
        box.addView(sheetRow(R.drawable.ic_folder, "文件", "PDF、Word、PPT、表格、文本", v -> {
            dismissSheet();
            startFilePicker();
        }));
        showSheet(box);
    }

    private void startPhotoPicker() {
        try {
            Intent i;
            if (Build.VERSION.SDK_INT >= 33) {
                i = new Intent(MediaStore.ACTION_PICK_IMAGES);
                i.setType("image/*");
                i.putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, 10);
            } else {
                i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("image/*");
                i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            }
            startActivityForResult(i, REQ_IMAGES);
        } catch (ActivityNotFoundException e) {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("image/*");
            i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            startActivityForResult(i, REQ_IMAGES);
        }
    }

    private void startFilePicker() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        startActivityForResult(i, REQ_FILES);
    }

    private void processPickedUris(Intent data, boolean imagesOnly) {
        if (data == null) return;
        ArrayList<Uri> uris = new ArrayList<>();
        ClipData clip = data.getClipData();
        if (clip != null) {
            for (int i = 0; i < clip.getItemCount(); i++) {
                Uri u = clip.getItemAt(i).getUri();
                if (u != null) uris.add(u);
            }
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }
        if (uris.isEmpty()) return;
        showBusy("正在读取附件…");
        new Thread(() -> {
            ArrayList<Att> added = new ArrayList<>();
            ArrayList<String> errors = new ArrayList<>();
            for (Uri u : uris) {
                if (pending.size() + added.size() >= 10) {
                    errors.add("最多添加 10 个附件");
                    break;
                }
                try {
                    String name = FileUtils.queryName(this, u, "附件");
                    String mime = getContentResolver().getType(u);
                    Att a;
                    if (FileUtils.isImageMime(mime, name)) {
                        a = FileUtils.importImage(this, u, store.attachmentsDir());
                    } else if (imagesOnly) {
                        errors.add(name + " 不是图片");
                        continue;
                    } else if (!FileUtils.supportedFile(name)) {
                        errors.add(name + " 的格式暂不支持");
                        continue;
                    } else {
                        a = FileUtils.importFile(this, u, store.attachmentsDir());
                    }
                    added.add(a);
                } catch (Exception e) {
                    errors.add(e.getMessage() == null ? "读取失败" : e.getMessage());
                }
            }
            ui.post(() -> {
                hideBusy();
                pending.addAll(added);
                updateAttachmentStrip();
                if (!errors.isEmpty()) snack(errors.get(0), null, null);
                else if (!added.isEmpty()) snack("已添加 " + added.size() + " 个附件", null, null);
            });
        }).start();
    }

    // ------------------------------------------------------------ export

    private void quickExport() {
        if (conv == null || conv.messages.isEmpty()) {
            snack("还没有可导出的对话", null, null);
            return;
        }
        if (streaming) {
            snack("请等回答生成完成后再导出", null, null);
            return;
        }
        showBusy("正在导出对话…");
        new Thread(() -> {
            try {
                Uri uri = ExportManager.quickExportToDownloads(this, conv, store);
                ui.post(() -> {
                    hideBusy();
                    if (uri == null) {
                        exportAs();
                    } else {
                        snack("已保存到 下载/大肥鱼", "分享", () -> shareUri(uri));
                    }
                });
            } catch (Exception e) {
                ui.post(() -> {
                    hideBusy();
                    snack("导出失败：" + e.getMessage(), "重试", this::exportAs);
                });
            }
        }).start();
    }

    private void exportAs() {
        if (conv == null || conv.messages.isEmpty()) {
            snack("还没有可导出的对话", null, null);
            return;
        }
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType(ExportManager.MIME);
        i.putExtra(Intent.EXTRA_TITLE, ExportManager.defaultName(conv));
        startActivityForResult(i, REQ_EXPORT);
    }

    private void shareUri(Uri uri) {
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType(ExportManager.MIME);
        send.putExtra(Intent.EXTRA_STREAM, uri);
        send.setClipData(ClipData.newRawUri("大肥鱼对话", uri));
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(Intent.createChooser(send, "分享对话"));
        } catch (Exception e) {
            snack("没有可用的分享应用", null, null);
        }
    }

    // ------------------------------------------------------------ 全量备份 / 恢复

    /** 手动备份全部对话到公共 Download；showUi 控制是否显示进度遮罩与结果提示。 */
    private void backupAll(boolean showUi) {
        if (backupInProgress) return;
        if (store.list().isEmpty()) {
            if (showUi) snack("还没有可备份的对话", null, null);
            return;
        }
        backupInProgress = true;
        if (showUi) showBusy("正在备份所有对话…");
        new Thread(() -> {
            try {
                // 先把当前正在编辑的对话落盘，保证备份是最新内容
                saveCurrentNow();
                Uri uri = ExportManager.fullBackupToDownloads(this, store, label -> {
                    if (showUi) ui.post(() -> showBusy(label));
                });
                ui.post(() -> {
                    backupInProgress = false;
                    if (showUi) {
                        hideBusy();
                        snack("已备份 " + store.list().size() + " 个对话到「下载/大肥鱼」",
                                uri != null ? "分享" : null,
                                uri != null ? () -> shareUri(uri) : null);
                    } else {
                        snack("已自动备份到「下载/大肥鱼」", null, null);
                    }
                });
            } catch (Exception e) {
                ui.post(() -> {
                    backupInProgress = false;
                    if (showUi) {
                        hideBusy();
                        snack("备份失败：" + safeMsg(e), "重试", () -> backupAll(true));
                    }
                });
            }
        }).start();
    }

    /** 后台自动备份：仅在开关开启、有对话、距上次超过 10 分钟时执行，静默无打扰。 */
    private void autoBackupIfNeeded(boolean force) {
        if (!prefs.autoBackup() && !force) return;
        if (backupInProgress || importInProgress || streaming) return;
        if (store.list().isEmpty()) return;
        long now = System.currentTimeMillis();
        if (!force && now - prefs.lastAutoBackupAt() < 10 * 60 * 1000L) return;
        backupInProgress = true;
        new Thread(() -> {
            try {
                saveCurrentNow();
                ExportManager.fullBackupToDownloads(this, store, null);
                prefs.setLastAutoBackupAt(System.currentTimeMillis());
            } catch (Throwable ignore) {
                // 自动备份失败不打扰用户，下次再试
            } finally {
                backupInProgress = false;
            }
        }).start();
    }

    /** 同步落盘当前对话（供备份前调用）。 */
    private void saveCurrentNow() {
        try {
            if (conv != null && !conv.messages.isEmpty()) store.saveNow(conv);
        } catch (Throwable ignore) { }
    }

    private static String safeMsg(Exception e) {
        String m = e == null ? null : e.getMessage();
        return m == null || m.isEmpty() ? "未知错误" : m;
    }

    /** 打开备份文件选择器。 */
    private void restoreAll() {
        if (streaming) {
            snack("请先停止当前生成", null, null);
            return;
        }
        if (importInProgress) return;
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        startActivityForResult(i, REQ_RESTORE);
    }

    /** 从备份文件恢复全部对话。 */
    private void doRestore(Uri uri) {
        if (importInProgress) return;
        importInProgress = true;
        showBusy("正在从备份恢复…");
        new Thread(() -> {
            try {
                int count = ExportManager.restoreFromUri(this, store, uri, label ->
                        ui.post(() -> showBusy(label)));
                ui.post(() -> {
                    importInProgress = false;
                    hideBusy();
                    renderConversation();
                    updateEverything();
                    snack("已从备份恢复 " + count + " 个对话", null, null);
                });
            } catch (Exception e) {
                ui.post(() -> {
                    importInProgress = false;
                    hideBusy();
                    snack("恢复失败：" + safeMsg(e), null, null);
                });
            }
        }).start();
    }

    private void importChat() {
        if (streaming) {
            snack("请先停止当前生成", null, null);
            return;
        }
        if (importInProgress) return;
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        startActivityForResult(i, REQ_IMPORT);
    }

    private void doImport(Uri uri) {
        if (importInProgress) return;
        importInProgress = true;
        showBusy("正在导入对话…");
        new Thread(() -> {
            try {
                Conv imported = ExportManager.importFromUri(this, store, uri, label -> {
                    ui.post(() -> showBusy(label));
                });
                ui.post(() -> {
                    importInProgress = false;
                    hideBusy();
                    closeDrawer();
                    closeSettings();
                    conv = imported;
                    conv.warnLevel = 0;
                    conv.warningDismissed = false;
                    applyConversationPrefs(imported);
                    prefs.setLastConvId(imported.id);
                    prefs.setWarningDialogShown(imported.id + "_1", true);
                    prefs.setWarningDialogShown(imported.id + "_2", true);
                    renderConversation();
                    updateEverything();
                    maybeWarn();
                    snack("已无缝接续「" + imported.titleOr("对话") + "」，继续聊吧", null, null);
                });
            } catch (Exception e) {
                ui.post(() -> {
                    importInProgress = false;
                    hideBusy();
                    snack("导入失败：" + (e.getMessage() == null ? "文件无法识别" : e.getMessage()),
                            null, null);
                });
            }
        }).start();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK) return;
        if (requestCode == REQ_IMAGES) {
            processPickedUris(data, true);
        } else if (requestCode == REQ_FILES) {
            processPickedUris(data, false);
        } else if (requestCode == REQ_EXPORT) {
            if (data == null || data.getData() == null) return;
            Uri uri = data.getData();
            showBusy("正在导出对话…");
            new Thread(() -> {
                try {
                    ExportManager.exportToUri(this, conv, store, uri, label ->
                            ui.post(() -> showBusy(label)));
                    ui.post(() -> {
                        hideBusy();
                        snack("对话已导出", "分享", () -> shareUri(uri));
                    });
                } catch (Exception e) {
                    ui.post(() -> {
                        hideBusy();
                        snack("导出失败：" + e.getMessage(), null, null);
                    });
                }
            }).start();
        } else if (requestCode == REQ_IMPORT) {
            if (data == null || data.getData() == null) return;
            doImport(data.getData());
        } else if (requestCode == REQ_RESTORE) {
            if (data == null || data.getData() == null) return;
            doRestore(data.getData());
        }
    }

    // ------------------------------------------------------------ images

    private void showImagePreview(Att a) {
        if (previewOverlay != null) return;
        File f = store.attachmentFile(a.localName);
        if (f == null || !f.isFile()) {
            snack("图片文件已丢失", null, null);
            return;
        }
        FrameLayout overlay = new FrameLayout(this);
        overlay.setBackgroundColor(0xF2000000);
        overlay.setClickable(true);
        ImageView iv = new ImageView(this);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        FrameLayout.LayoutParams ilp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        ilp.setMargins(0, Ui.dp(this, 30), 0, Ui.dp(this, 40));
        overlay.addView(iv, ilp);
        View close = Ui.iconButton(this, R.drawable.ic_close, Color.WHITE, 0x22FFFFFF, 0x44FFFFFF);
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(Ui.dp(this, 42), Ui.dp(this, 42));
        clp.gravity = Gravity.TOP | Gravity.END;
        clp.setMargins(0, Ui.dp(this, 38), Ui.dp(this, 12), 0);
        overlay.addView(close, clp);
        close.setOnClickListener(v -> closePreview());
        overlay.setOnClickListener(v -> closePreview());
        previewOverlay = overlay;
        root.addView(overlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        new Thread(() -> {
            android.graphics.Bitmap bmp = ImageLoader.decode(f, 2400);
            ui.post(() -> {
                if (bmp != null) iv.setImageBitmap(bmp);
            });
        }).start();
    }

    private void closePreview() {
        if (previewOverlay != null) {
            root.removeView(previewOverlay);
            previewOverlay = null;
        }
    }

    // ----------------------------------------------------------- messages

    private void showMessageSheet(Msg m) {
        LinearLayout box = Ui.column(this);
        box.addView(sheetTitle(m.isUser() ? "你的消息" : "大肥鱼的回答"));
        box.addView(sheetRow(R.drawable.ic_copy, "复制", null, v -> {
            dismissSheet();
            Ui.copy(this, "消息", m.content);
            snack("已复制", null, null);
        }));
        if (m.isUser()) {
            box.addView(sheetRow(R.drawable.ic_edit, "编辑并重发", "会重新开始这条消息之后的对话", v -> {
                dismissSheet();
                editUserMessage(m);
            }));
        } else {
            box.addView(sheetRow(R.drawable.ic_refresh, "重新生成", "从这条回答重新开始", v -> {
                dismissSheet();
                regenerate(m);
            }));
        }
        showSheet(box);
    }

    private void editUserMessage(Msg m) {
        if (conv == null || streaming) {
            snack("请先停止当前生成", null, null);
            return;
        }
        int idx = conv.messages.indexOf(m);
        if (idx < 0) return;
        ArrayList<Att> atts = new ArrayList<>(m.attachments);
        while (conv.messages.size() > idx) conv.messages.remove(conv.messages.size() - 1);
        conv.touch();
        saveCurrent();
        pending.clear();
        pending.addAll(atts);
        updateAttachmentStrip();
        input.setText(m.content);
        input.setSelection(input.length());
        renderConversation();
        input.requestFocus();
        updateEverything();
    }

    private void regenerate(Msg m) {
        if (conv == null || streaming) {
            snack("请先停止当前生成", null, null);
            return;
        }
        int idx = conv.messages.indexOf(m);
        if (idx < 0) return;
        int userIdx = -1;
        for (int i = idx - 1; i >= 0; i--) {
            if (conv.messages.get(i).isUser()) { userIdx = i; break; }
        }
        if (userIdx < 0) return;
        boolean notLast = idx < conv.messages.size() - 1;
        Runnable go = () -> {
            while (conv.messages.size() > idx) conv.messages.remove(conv.messages.size() - 1);
            conv.touch();
            saveCurrent();
            renderConversation();
            startAssistant();
        };
        if (notLast) {
            showConfirm("重新生成", "这条回答之后的内容会被删除，然后重新生成。", "继续", false, go);
        } else go.run();
    }

    private void showConversationActions(Store.Entry e) {
        if (streaming) {
            snack("请先停止当前生成", null, null);
            return;
        }
        LinearLayout box = Ui.column(this);
        box.addView(sheetTitle(e.title == null ? "对话" : e.title));
        box.addView(sheetRow(R.drawable.ic_edit, "重命名", null, v -> {
            dismissSheet();
            showRenameDialog(e);
        }));
        box.addView(sheetRow(R.drawable.ic_trash, "删除对话", "同时删除本地附件", v -> {
            dismissSheet();
            showConfirm("删除对话", "删除后无法恢复。确定删除「" + (e.title == null ? "对话" : e.title) + "」吗？",
                    "删除", true, () -> {
                        store.delete(e.id);
                        if (conv != null && conv.id.equals(e.id)) {
                            conv = null;
                            prefs.setLastConvId("");
                            renderConversation();
                            updateEverything();
                        }
                        rebuildDrawer();
                        snack("已删除", null, null);
                    });
        }));
        showSheet(box);
    }

    private void showRenameDialog(Store.Entry e) {
        EditText et = styledInput(e.title == null ? "" : e.title, "对话名称");
        et.setSingleLine(true);
        showInputDialog("重命名对话", et, "保存", () -> {
            String t = et.getText().toString().trim();
            if (t.isEmpty()) return;
            store.rename(e.id, t);
            if (conv != null && conv.id.equals(e.id)) {
                conv.title = t;
                conv.titleAuto = false;
                updateTitle();
            }
            rebuildDrawer();
        });
    }

    // --------------------------------------------------------- overlays

    private void showSheet(LinearLayout content) {
        dismissSheet();
        FrameLayout overlay = new FrameLayout(this);
        overlay.setBackgroundColor(getColor(R.color.scrim));
        overlay.setClickable(true);
        overlay.setOnClickListener(v -> dismissSheet());

        LinearLayout panel = Ui.column(this);
        panel.setBackground(Ui.rounded(this, getColor(R.color.surface), new float[]{20, 20, 0, 0}));
        panel.setPadding(Ui.dp(this, 8), Ui.dp(this, 7), Ui.dp(this, 8),
                Ui.dp(this, 8) + bottomInset);
        panel.setElevation(Ui.dpf(this, 16));
        panel.setOnClickListener(v -> { });

        View handle = new View(this);
        handle.setBackground(Ui.rounded(this, getColor(R.color.divider), 2));
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(Ui.dp(this, 34), Ui.dp(this, 4));
        hlp.gravity = Gravity.CENTER_HORIZONTAL;
        hlp.bottomMargin = Ui.dp(this, 6);
        panel.addView(handle, hlp);

        content.setPadding(Ui.dp(this, 8), 0, Ui.dp(this, 8), Ui.dp(this, 8));
        panel.addView(content, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        plp.gravity = Gravity.BOTTOM;
        panel.setTranslationY(Ui.dp(this, 40));
        panel.setAlpha(0f);
        overlay.addView(panel, plp);
        overlay.setAlpha(0f);
        sheetOverlay = overlay;
        root.addView(overlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        overlay.animate().alpha(1f).setDuration(150).start();
        panel.animate().translationY(0).alpha(1f).setDuration(200)
                .setInterpolator(new DecelerateInterpolator()).start();
    }

    private void dismissSheet() {
        if (sheetOverlay == null) return;
        View v = sheetOverlay;
        sheetOverlay = null;
        v.animate().alpha(0f).setDuration(140)
                .withEndAction(() -> root.removeView(v)).start();
    }

    private TextView sheetTitle(String text) {
        TextView tv = Ui.text(this, text, 15, getColor(R.color.text), true);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 10));
        return tv;
    }

    private View sheetRow(int icon, String title, String subtitle, View.OnClickListener click) {
        LinearLayout row = Ui.row(this);
        row.setPadding(Ui.dp(this, 12), Ui.dp(this, 11), Ui.dp(this, 12), Ui.dp(this, 11));
        row.setBackground(Ui.ripple(Ui.rounded(this, 0, 12), getColor(R.color.surface_press)));
        ImageView iv = Ui.icon(this, icon, getColor(R.color.text_secondary));
        row.addView(iv, new LinearLayout.LayoutParams(Ui.dp(this, 21), Ui.dp(this, 21)));
        LinearLayout col = Ui.column(this);
        col.addView(Ui.text(this, title, 14, getColor(R.color.text), false));
        if (subtitle != null && !subtitle.isEmpty()) {
            TextView s = Ui.text(this, subtitle, 11.5f, getColor(R.color.text_tertiary), false);
            s.setPadding(0, Ui.dp(this, 2), 0, 0);
            col.addView(s);
        }
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        clp.leftMargin = Ui.dp(this, 12);
        row.addView(col, clp);
        row.setOnClickListener(click);
        return row;
    }

    private void showConfirm(String title, String message, String positive, boolean danger, Runnable action) {
        closeDialog();
        FrameLayout overlay = new FrameLayout(this);
        overlay.setBackgroundColor(getColor(R.color.scrim));
        overlay.setClickable(true);
        LinearLayout card = Ui.column(this);
        card.setBackground(Ui.rounded(this, getColor(R.color.surface), 20));
        card.setPadding(Ui.dp(this, 20), Ui.dp(this, 18), Ui.dp(this, 20), Ui.dp(this, 14));
        card.setOnClickListener(v -> { });
        TextView t = Ui.text(this, title, 17, getColor(R.color.text), true);
        card.addView(t);
        TextView m = Ui.text(this, message, 13.5f, getColor(R.color.text_secondary), false);
        m.setPadding(0, Ui.dp(this, 9), 0, Ui.dp(this, 18));
        card.addView(m);
        LinearLayout buttons = Ui.row(this);
        buttons.setGravity(Gravity.END);
        TextView cancel = Ui.text(this, "取消", 14, getColor(R.color.text_secondary), false);
        cancel.setPadding(Ui.dp(this, 14), Ui.dp(this, 9), Ui.dp(this, 14), Ui.dp(this, 9));
        cancel.setOnClickListener(v -> closeDialog());
        buttons.addView(cancel);
        TextView ok = Ui.text(this, positive, 14,
                getColor(danger ? R.color.danger : R.color.brand), true);
        ok.setPadding(Ui.dp(this, 14), Ui.dp(this, 9), Ui.dp(this, 4), Ui.dp(this, 9));
        ok.setOnClickListener(v -> {
            closeDialog();
            if (action != null) action.run();
        });
        buttons.addView(ok);
        card.addView(buttons);

        int w = Math.min((int) (getResources().getDisplayMetrics().widthPixels * 0.88f), Ui.dp(this, 360));
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.gravity = Gravity.CENTER;
        overlay.addView(card, clp);
        overlay.setAlpha(0f);
        dialogOverlay = overlay;
        root.addView(overlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        overlay.animate().alpha(1f).setDuration(140).start();
    }

    private void showInputDialog(String title, EditText inputView, String positive, Runnable action) {
        closeDialog();
        FrameLayout overlay = new FrameLayout(this);
        overlay.setBackgroundColor(getColor(R.color.scrim));
        overlay.setClickable(true);
        LinearLayout card = Ui.column(this);
        card.setBackground(Ui.rounded(this, getColor(R.color.surface), 20));
        card.setPadding(Ui.dp(this, 20), Ui.dp(this, 18), Ui.dp(this, 20), Ui.dp(this, 14));
        card.setOnClickListener(v -> { });
        card.addView(Ui.text(this, title, 17, getColor(R.color.text), true));
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ilp.topMargin = Ui.dp(this, 14);
        card.addView(inputView, ilp);
        LinearLayout buttons = Ui.row(this);
        buttons.setGravity(Gravity.END);
        buttons.setPadding(0, Ui.dp(this, 14), 0, 0);
        TextView cancel = Ui.text(this, "取消", 14, getColor(R.color.text_secondary), false);
        cancel.setPadding(Ui.dp(this, 14), Ui.dp(this, 9), Ui.dp(this, 14), Ui.dp(this, 9));
        cancel.setOnClickListener(v -> closeDialog());
        buttons.addView(cancel);
        TextView ok = Ui.text(this, positive, 14, getColor(R.color.brand), true);
        ok.setPadding(Ui.dp(this, 14), Ui.dp(this, 9), Ui.dp(this, 4), Ui.dp(this, 9));
        ok.setOnClickListener(v -> {
            closeDialog();
            if (action != null) action.run();
        });
        buttons.addView(ok);
        card.addView(buttons);
        int w = Math.min((int) (getResources().getDisplayMetrics().widthPixels * 0.88f), Ui.dp(this, 360));
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.gravity = Gravity.CENTER;
        overlay.addView(card, clp);
        overlay.setAlpha(0f);
        dialogOverlay = overlay;
        root.addView(overlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        overlay.animate().alpha(1f).setDuration(140).start();
        inputView.requestFocus();
        inputView.postDelayed(() -> {
            android.view.inputmethod.InputMethodManager imm =
                    (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(inputView, 0);
        }, 150);
    }

    private void closeDialog() {
        if (dialogOverlay != null) {
            root.removeView(dialogOverlay);
            dialogOverlay = null;
        }
    }

    private void snack(String message, String actionLabel, Runnable action) {
        snackSeq++;
        if (snackTimeout != null) ui.removeCallbacks(snackTimeout);
        if (snackView != null) {
            root.removeView(snackView);
            snackView = null;
        }
        final int seq = snackSeq;
        LinearLayout bar = Ui.row(this);
        bar.setBackground(Ui.rounded(this, night ? 0xFF2A2C31 : 0xFF25272C, 13));
        bar.setPadding(Ui.dp(this, 14), Ui.dp(this, 11), Ui.dp(this, 10), Ui.dp(this, 11));
        bar.setElevation(Ui.dpf(this, 8));
        TextView tv = Ui.text(this, message, 12.5f, Color.WHITE, false);
        tv.setMaxLines(2);
        tv.setEllipsize(TextUtils.TruncateAt.END);
        bar.addView(tv, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        if (actionLabel != null) {
            TextView actionView = Ui.text(this, actionLabel, 12.5f, 0xFF9DB0FF, true);
            actionView.setPadding(Ui.dp(this, 12), Ui.dp(this, 2), Ui.dp(this, 4), Ui.dp(this, 2));
            actionView.setOnClickListener(v -> {
                removeSnack();
                if (action != null) action.run();
            });
            bar.addView(actionView);
        }
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.BOTTOM;
        lp.setMargins(Ui.dp(this, 14), 0, Ui.dp(this, 14), bottomInset + Ui.dp(this, 92));
        bar.setAlpha(0f);
        bar.setTranslationY(Ui.dp(this, 12));
        snackView = bar;
        root.addView(bar, lp);
        bar.animate().alpha(1f).translationY(0).setDuration(160).start();
        snackTimeout = () -> {
            if (seq == snackSeq) removeSnack();
        };
        ui.postDelayed(snackTimeout, 4200);
    }

    private void removeSnack() {
        if (snackView == null) return;
        snackSeq++;
        if (snackTimeout != null) ui.removeCallbacks(snackTimeout);
        View v = snackView;
        snackView = null;
        v.animate().alpha(0f).translationY(Ui.dp(this, 10)).setDuration(130)
                .withEndAction(() -> root.removeView(v)).start();
    }

    private void showBusy(String label) {
        if (busyOverlay == null) {
            FrameLayout overlay = new FrameLayout(this);
            overlay.setBackgroundColor(0x66000000);
            overlay.setClickable(true);
            LinearLayout card = Ui.column(this);
            card.setGravity(Gravity.CENTER);
            card.setBackground(Ui.rounded(this, getColor(R.color.surface), 18));
            card.setPadding(Ui.dp(this, 26), Ui.dp(this, 22), Ui.dp(this, 26), Ui.dp(this, 22));
            ProgressBar bar = new ProgressBar(this);
            bar.setIndeterminate(true);
            bar.setIndeterminateTintList(ColorStateList.valueOf(getColor(R.color.brand)));
            card.addView(bar, new LinearLayout.LayoutParams(Ui.dp(this, 30), Ui.dp(this, 30)));
            busyLabel = Ui.text(this, label, 12.5f, getColor(R.color.text_secondary), false);
            busyLabel.setGravity(Gravity.CENTER);
            busyLabel.setPadding(0, Ui.dp(this, 10), 0, 0);
            busyLabel.setMaxLines(3);
            card.addView(busyLabel);
            FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(
                    Ui.dp(this, 230), ViewGroup.LayoutParams.WRAP_CONTENT);
            clp.gravity = Gravity.CENTER;
            overlay.addView(card, clp);
            busyOverlay = overlay;
            root.addView(overlay, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        } else {
            busyLabel.setText(label);
        }
    }

    private void hideBusy() {
        if (busyOverlay != null) {
            root.removeView(busyOverlay);
            busyOverlay = null;
            busyLabel = null;
        }
    }

    private void haptic() {
        if (prefs != null && prefs.haptics()) {
            root.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
        }
    }

    private void hideKeyboard() {
        android.view.inputmethod.InputMethodManager imm =
                (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(input.getWindowToken(), 0);
        input.clearFocus();
    }

    private EditText styledInput(String value, String hint) {
        EditText et = new EditText(this);
        et.setText(value == null ? "" : value);
        et.setHint(hint);
        et.setTextSize(14);
        et.setTextColor(getColor(R.color.text));
        et.setHintTextColor(getColor(R.color.text_tertiary));
        et.setBackground(Ui.rounded(this, getColor(R.color.surface_alt), 10));
        et.setPadding(Ui.dp(this, 12), Ui.dp(this, 10), Ui.dp(this, 12), Ui.dp(this, 10));
        et.setSingleLine(true);
        return et;
    }

    // ---------------------------------------------------------- settings

    private void openSettings() {
        if (settingsOverlay != null) return;
        closeDrawer();
        FrameLayout overlay = new FrameLayout(this);
        overlay.setBackgroundColor(getColor(R.color.bg));
        settingsOverlay = overlay;
        root.addView(overlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout page = Ui.column(this);
        page.setPadding(0, statusBarHeight(), 0, 0);
        overlay.addView(page, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout bar = Ui.row(this);
        bar.setPadding(Ui.dp(this, 4), 0, Ui.dp(this, 8), 0);
        View back = Ui.iconButton(this, R.drawable.ic_back, getColor(R.color.text), 0,
                getColor(R.color.surface_press));
        back.setOnClickListener(v -> closeSettings());
        bar.addView(back);
        TextView t = Ui.text(this, "设置", 17, getColor(R.color.text), true);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        tlp.leftMargin = Ui.dp(this, 6);
        bar.addView(t, tlp);
        page.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 56)));

        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout content = Ui.column(this);
        content.setPadding(Ui.dp(this, 16), Ui.dp(this, 4), Ui.dp(this, 16), Ui.dp(this, 40));
        scroll.addView(content);
        page.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        final EditText keyInput = styledInput(prefs.apiKey(), "sk-...");
        keyInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        final EditText baseInput = styledInput(prefs.baseUrl(), "https://api.deepseek.com");

        content.addView(section("API 与模型"));
        LinearLayout apiCard = settingsCard();
        apiCard.addView(fieldRow("API Key", keyInput));
        apiCard.addView(divider());
        apiCard.addView(fieldRow("API 地址", baseInput));
        apiCard.addView(divider());
        LinearLayout saveRow = Ui.row(this);
        saveRow.setPadding(Ui.dp(this, 14), Ui.dp(this, 10), Ui.dp(this, 14), Ui.dp(this, 10));
        TextView save = Ui.text(this, "保存 API 设置", 13, getColor(R.color.brand), true);
        saveRow.addView(save, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView test = Ui.text(this, "测试连接", 12.5f, getColor(R.color.text_secondary), false);
        test.setPadding(Ui.dp(this, 10), Ui.dp(this, 4), 0, Ui.dp(this, 4));
        saveRow.addView(test);
        apiCard.addView(saveRow);
        TextView testStatus = Ui.text(this, "", 11, getColor(R.color.text_tertiary), false);
        testStatus.setPadding(Ui.dp(this, 14), 0, Ui.dp(this, 14), Ui.dp(this, 10));
        apiCard.addView(testStatus);

        Runnable saveApi = () -> {
            String key = keyInput.getText().toString().trim();
            String base = baseInput.getText().toString().trim();
            if (base.isEmpty()) base = "https://api.deepseek.com";
            if (!base.startsWith("https://")) {
                snack("API 地址必须以 https:// 开头", null, null);
                return;
            }
            prefs.setApiKey(key);
            prefs.setBaseUrl(base);
            snack("API 设置已保存", null, null);
        };
        save.setOnClickListener(v -> saveApi.run());
        test.setOnClickListener(v -> {
            saveApi.run();
            if (!prefs.hasApiKey()) {
                testStatus.setText("请先填写 API Key");
                return;
            }
            testStatus.setText("正在连接…");
            api.testConnection(prefs, (ok, message) -> {
                testStatus.setText(message);
                testStatus.setTextColor(getColor(ok ? R.color.success : R.color.danger));
            });
        });

        apiCard.addView(divider());
        apiCard.addView(choiceRow("切换 / 管理 API Key",
                prefs.keyCount() > 0 ? "当前：" + prefs.keyName(prefs.currentKeyIndex()) + "（共 " + prefs.keyCount() + " 个）" : "添加多个 Key，随时切换",
                v -> showKeyManager()));
        apiCard.addView(divider());
        apiCard.addView(choiceRow("模型", modelLabel(), v -> showModelSheet()));
        apiCard.addView(divider());
        apiCard.addView(choiceRow("思考强度", effortLabel(), v -> showEffortSheet()));
        apiCard.addView(divider());
        apiCard.addView(choiceRow("上下文上限", TokenMeter.limitLabel(prefs.contextLimit()),
                v -> showContextLimitSheet()));
        apiCard.addView(divider());
        apiCard.addView(choiceRow("预警轮数", "剩余 " + prefs.warnThreshold() + " 轮时提醒",
                v -> showWarnThresholdSheet()));
        apiCard.addView(divider());
        apiCard.addView(choiceRow("最大输出", TokenMeter.formatTokens(prefs.maxTokens()),
                v -> showMaxTokensSheet()));
        apiCard.addView(divider());
        apiCard.addView(switchRow("自动生成标题", prefs.autoTitle(), prefs::setAutoTitle));
        apiCard.addView(divider());
        apiCard.addView(switchRow("触感反馈", prefs.haptics(), prefs::setHaptics));
        content.addView(apiCard);
        View apiSpacer = new View(this);
        LinearLayout.LayoutParams aclp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 14));
        content.addView(apiSpacer, aclp);

        content.addView(section("数据"));
        LinearLayout dataCard = settingsCard();
        dataCard.addView(choiceRow("导出当前对话", "保存为 .dfy 文件", v -> {
            closeSettings();
            quickExport();
        }));
        dataCard.addView(divider());
        dataCard.addView(choiceRow("导出到指定位置", "文件管理器里选择", v -> {
            closeSettings();
            exportAs();
        }));
        dataCard.addView(divider());
        dataCard.addView(choiceRow("导入对话", "无缝接续之前的聊天", v -> {
            closeSettings();
            importChat();
        }));
        dataCard.addView(divider());
        dataCard.addView(switchRow("自动备份到手机", prefs.autoBackup(), v -> {
            prefs.setAutoBackup(v);
            if (v) autoBackupIfNeeded(true);
        }));
        dataCard.addView(divider());
        dataCard.addView(choiceRow("立即备份全部对话", "存到 下载/大肥鱼 · 卸载软件不丢失", v -> {
            closeSettings();
            backupAll(true);
        }));
        dataCard.addView(divider());
        dataCard.addView(choiceRow("从完整备份恢复", "换手机 / 重装后恢复所有对话", v -> {
            closeSettings();
            restoreAll();
        }));
        dataCard.addView(divider());
        dataCard.addView(choiceRowDanger("清空全部对话", "删除所有本地记录与附件", v -> {
            if (streaming) {
                snack("请先停止当前生成", null, null);
                return;
            }
            showConfirm("清空全部对话", "这会删除本机上的全部对话和附件，无法恢复。建议先导出备份。",
                    "清空", true, () -> {
                        store.clearAll();
                        conv = null;
                        pending.clear();
                        updateAttachmentStrip();
                        prefs.setLastConvId("");
                        renderConversation();
                        updateEverything();
                        snack("已清空", null, null);
                    });
        }));
        content.addView(dataCard);

        content.addView(section("关于"));
        LinearLayout aboutCard = settingsCard();
        aboutCard.addView(choiceRow("关于大肥鱼", "v1.0.0 · DeepSeek API", v -> showAbout()));
        aboutCard.addView(divider());
        aboutCard.addView(choiceRow("DeepSeek 开放平台", "获取 API Key 与用量", v ->
                Ui.openUrl(this, "https://platform.deepseek.com/api_keys")));
        content.addView(aboutCard);

        TextView footer = Ui.text(this, "API Key 使用 Android Keystore 加密，只保存在本机\n聊天记录可自动备份到「下载/大肥鱼」，不会上传到任何服务器",
                10.5f, getColor(R.color.text_tertiary), false);
        footer.setGravity(Gravity.CENTER);
        footer.setPadding(0, Ui.dp(this, 22), 0, Ui.dp(this, 10));
        content.addView(footer);

        overlay.setAlpha(0f);
        overlay.animate().alpha(1f).setDuration(180).start();
    }

    private void closeSettings() {
        if (settingsOverlay != null) {
            root.removeView(settingsOverlay);
            settingsOverlay = null;
        }
    }

    private TextView section(String title) {
        TextView tv = Ui.text(this, title, 11, getColor(R.color.text_tertiary), true);
        tv.setLetterSpacing(0.06f);
        tv.setPadding(Ui.dp(this, 6), Ui.dp(this, 8), 0, Ui.dp(this, 7));
        return tv;
    }

    private LinearLayout settingsCard() {
        LinearLayout card = Ui.column(this);
        card.setBackground(Ui.stroke(this, getColor(R.color.surface), getColor(R.color.divider),
                0.7f, 15));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        card.setLayoutParams(lp);
        return card;
    }

    private View fieldRow(String label, EditText inputView) {
        LinearLayout col = Ui.column(this);
        col.setPadding(Ui.dp(this, 14), Ui.dp(this, 10), Ui.dp(this, 14), Ui.dp(this, 10));
        TextView l = Ui.text(this, label, 11, getColor(R.color.text_tertiary), false);
        col.addView(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(this, 4);
        col.addView(inputView, lp);
        return col;
    }

    private View choiceRow(String label, String value, View.OnClickListener click) {
        LinearLayout row = Ui.row(this);
        row.setPadding(Ui.dp(this, 14), Ui.dp(this, 13), Ui.dp(this, 10), Ui.dp(this, 13));
        row.setBackground(Ui.ripple(Ui.rounded(this, 0, 0), getColor(R.color.surface_press)));
        TextView l = Ui.text(this, label, 14, getColor(R.color.text), false);
        row.addView(l, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView v = Ui.text(this, value, 12.5f, getColor(R.color.text_tertiary), false);
        v.setMaxLines(1);
        v.setEllipsize(TextUtils.TruncateAt.END);
        row.addView(v);
        ImageView chev = Ui.icon(this, R.drawable.ic_chevron_right, getColor(R.color.text_tertiary));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(Ui.dp(this, 16), Ui.dp(this, 16));
        clp.leftMargin = Ui.dp(this, 4);
        row.addView(chev, clp);
        row.setOnClickListener(click);
        return row;
    }

    private View choiceRowDanger(String label, String value, View.OnClickListener click) {
        LinearLayout row = Ui.row(this);
        row.setPadding(Ui.dp(this, 14), Ui.dp(this, 13), Ui.dp(this, 10), Ui.dp(this, 13));
        row.setBackground(Ui.ripple(Ui.rounded(this, 0, 0), getColor(R.color.surface_press)));
        TextView l = Ui.text(this, label, 14, getColor(R.color.danger), false);
        row.addView(l, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView v = Ui.text(this, value, 11.5f, getColor(R.color.text_tertiary), false);
        row.addView(v);
        row.setOnClickListener(click);
        return row;
    }

    private View switchRow(String label, boolean checked, Toggle toggle) {
        LinearLayout row = Ui.row(this);
        row.setPadding(Ui.dp(this, 14), Ui.dp(this, 10), Ui.dp(this, 12), Ui.dp(this, 10));
        TextView l = Ui.text(this, label, 14, getColor(R.color.text), false);
        row.addView(l, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Switch sw = new Switch(this);
        sw.setChecked(checked);
        if (Build.VERSION.SDK_INT >= 23) {
            sw.setThumbTintList(ColorStateList.valueOf(getColor(R.color.brand)));
            sw.setTrackTintList(ColorStateList.valueOf(getColor(R.color.brand_soft_2)));
        }
        sw.setOnCheckedChangeListener((buttonView, isChecked) -> {
            toggle.on(isChecked);
            haptic();
        });
        row.addView(sw);
        return row;
    }

    private View divider() {
        View v = new View(this);
        v.setBackgroundColor(getColor(R.color.divider));
        v.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 0.7f)));
        return v;
    }

    private String modelLabel() {
        String m = prefs.model();
        if (m == null || m.isEmpty()) return "DeepSeek V4.1 Flash";
        if ("deepseek-flash".equals(m)) return "大肥鱼 V4.1 Flash";
        if (m.contains("pro")) return "DeepSeek V4 Pro";
        return m; // 自定义模型名
    }

    private String effortLabel() {
        String e = prefs.effort();
        if ("low".equals(e)) return "快速";
        if ("max".equals(e)) return "极致";
        return "标准";
    }

    private int statusBarHeight() {
        int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
        if (id > 0) return getResources().getDimensionPixelSize(id);
        return Ui.dp(this, 24);
    }

    private void showChoiceSheet(String title, String[] labels, String[] values, String current, Choice cb) {
        LinearLayout box = Ui.column(this);
        box.addView(sheetTitle(title));
        for (int i = 0; i < labels.length; i++) {
            final String value = values[i];
            LinearLayout row = Ui.row(this);
            row.setPadding(Ui.dp(this, 14), Ui.dp(this, 13), Ui.dp(this, 14), Ui.dp(this, 13));
            row.setBackground(Ui.ripple(Ui.rounded(this, 0, 12), getColor(R.color.surface_press)));
            TextView tv = Ui.text(this, labels[i], 14, getColor(R.color.text), false);
            row.addView(tv, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            if (value.equals(current)) {
                ImageView check = Ui.icon(this, R.drawable.ic_check, getColor(R.color.brand));
                row.addView(check, new LinearLayout.LayoutParams(Ui.dp(this, 18), Ui.dp(this, 18)));
            }
            row.setOnClickListener(v -> {
                dismissSheet();
                cb.on(value);
            });
            box.addView(row);
        }
        showSheet(box);
    }

    // ------------------------------------------------------------ API Key 管理

    private String maskKey(String key) {
        if (key == null) return "";
        String k = key.trim();
        if (k.length() <= 8) return k.isEmpty() ? "" : "••••";
        return k.substring(0, 6) + "••••" + k.substring(k.length() - 4);
    }

    private void showKeyManager() {
        LinearLayout box = Ui.column(this);
        box.addView(sheetTitle("API Key 管理"));

        TextView hint = Ui.text(this, "点击某个 Key 切换为当前使用；可添加多个（如不同账号 / 不同服务商）。",
                11.5f, getColor(R.color.text_secondary), false);
        hint.setPadding(Ui.dp(this, 15), 0, Ui.dp(this, 15), Ui.dp(this, 8));
        box.addView(hint);

        int count = prefs.keyCount();
        int current = prefs.currentKeyIndex();
        for (int i = 0; i < count; i++) {
            final int idx = i;
            LinearLayout row = Ui.row(this);
            row.setPadding(Ui.dp(this, 14), Ui.dp(this, 12), Ui.dp(this, 10), Ui.dp(this, 12));
            row.setBackground(Ui.ripple(Ui.rounded(this, 0, 12), getColor(R.color.surface_press)));
            LinearLayout info = Ui.column(this);
            LinearLayout.LayoutParams iw = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            row.addView(info, iw);
            info.addView(Ui.text(this, prefs.keyName(i) + (i == current ? "  ✓ 当前" : ""), 14,
                    getColor(i == current ? R.color.brand : R.color.text), true));
            info.addView(Ui.text(this, maskKey(prefs.apiKeyIfIndex(i)) + "  ·  " + prefs.keyBaseAt(i),
                    11, getColor(R.color.text_tertiary), false));

            // 切换
            row.setOnClickListener(v -> {
                if (idx == prefs.currentKeyIndex()) { dismissSheet(); return; }
                saveCurrent();
                prefs.setCurrentKeyIndex(idx);
                dismissSheet();
                closeSettings();
                refreshCurrentKeySettings();
                snack("已切换到 " + prefs.keyName(idx), null, null);
            });

            // 编辑
            TextView edit = Ui.text(this, "编辑", 12.5f, getColor(R.color.text_secondary), false);
            edit.setPadding(Ui.dp(this, 10), Ui.dp(this, 8), Ui.dp(this, 6), Ui.dp(this, 8));
            edit.setOnClickListener(v -> {
                dismissSheet();
                showKeyEditor(idx);
            });
            row.addView(edit);

            // 删除
            TextView del = Ui.text(this, "删除", 12.5f, getColor(R.color.danger), false);
            del.setPadding(Ui.dp(this, 6), Ui.dp(this, 8), Ui.dp(this, 4), Ui.dp(this, 8));
            del.setOnClickListener(v -> {
                if (prefs.keyCount() <= 1) {
                    snack("至少保留一个 Key", null, null);
                    return;
                }
                showConfirm("删除 " + prefs.keyName(idx) + "？", "这会删除该 Key 及其配置。",
                        "删除", true, () -> {
                            prefs.removeKey(idx);
                            dismissSheet();
                            showKeyManager();
                        });
            });
            row.addView(del);
            box.addView(row);
        }

        TextView add = primaryButton("＋ 新增 API Key", getColor(R.color.brand), Color.WHITE, v -> {
            dismissSheet();
            showKeyEditor(-1);
        });
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 46));
        blp.topMargin = Ui.dp(this, 12);
        box.addView(add, blp);
        showSheet(box);
    }

    /** 新增(idx=-1)或编辑(idx>=0)一个 Key 配置。 */
    private void showKeyEditor(final int idx) {
        boolean isNew = idx < 0;
        LinearLayout box = Ui.column(this);
        box.setPadding(Ui.dp(this, 4), Ui.dp(this, 4), Ui.dp(this, 4), Ui.dp(this, 4));
        box.addView(sheetTitle(isNew ? "新增 API Key" : "编辑 " + prefs.keyName(idx)));

        EditText nameField = styledInput(isNew ? "" : prefs.keyName(idx), "备注名称，如：主账号 / 备用");
        EditText keyField = styledInput(isNew ? "" : prefs.apiKeyIfIndex(idx), "sk-... 或第三方 Key");
        keyField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        EditText baseField = styledInput(isNew ? "https://api.deepseek.com" : prefs.keyBaseAt(idx),
                "https://api.deepseek.com");
        EditText modelField = styledInput(isNew ? "deepseek-flash" : prefs.keyModelAt(idx), "模型名称");

        box.addView(fieldRow("备注名称", nameField));
        box.addView(fieldRow("API Key", keyField));
        box.addView(fieldRow("API 地址（支持其他兼容模型）", baseField));
        box.addView(fieldRow("模型名称", modelField));

        TextView tip = Ui.text(this, "其他模型示例：\n"
                        + "OpenAI:  https://api.openai.com/v1   /   gpt-4o-mini\n"
                        + "本地 Ollama:  http://192.168.1.10:11434/v1   /   qwen2.5\n"
                        + "通义/智谱等 OpenAI 兼容端点同样适用。",
                11, getColor(R.color.text_tertiary), false);
        tip.setPadding(Ui.dp(this, 14), Ui.dp(this, 8), Ui.dp(this, 14), Ui.dp(this, 4));
        box.addView(tip);

        TextView save = primaryButton("保存", getColor(R.color.brand), Color.WHITE, v -> {
            String key = keyField.getText().toString().trim();
            String base = baseField.getText().toString().trim();
            String model = modelField.getText().toString().trim();
            String name = nameField.getText().toString().trim();
            if (base.isEmpty()) base = "https://api.deepseek.com";
            if (!base.startsWith("http://") && !base.startsWith("https://")) {
                snack("API 地址需以 http:// 或 https:// 开头", null, null);
                return;
            }
            if (model.isEmpty()) model = "deepseek-flash";
            if (isNew) {
                if (key.isEmpty()) { snack("请填入 API Key", null, null); return; }
                prefs.addKey(name, key, base, model);
            } else {
                // 编辑时若 Key 输入框为空，保留原 Key
                String finalKey = key.isEmpty() ? prefs.apiKeyIfIndex(idx) : key;
                prefs.updateKey(idx, name, finalKey, base, model);
            }
            dismissSheet();
            closeSettings();
            refreshCurrentKeySettings();
            snack("已保存", null, null);
        });
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 46));
        blp.topMargin = Ui.dp(this, 10);
        box.addView(save, blp);
        showSheet(box);
    }

    /** 切换/编辑 Key 后，刷新界面上的当前配置显示。 */
    private void refreshCurrentKeySettings() {
        // 切换 Key 会改变 model/baseUrl，同步到当前对话与顶部显示。
        if (conv != null) conv.model = prefs.model();
        updateTitle();
        updateEverything();
    }

    private void showModelSheet() {
        LinearLayout box = Ui.column(this);
        box.addView(sheetTitle("选择模型"));
        String[] labels = {"大肥鱼 V4.1 Flash（推荐）", "DeepSeek V4 Pro（深度推理）",
                "自定义模型名…"};
        String[] values = {"deepseek-flash", "deepseek-v4-pro", "custom"};
        for (int i = 0; i < labels.length; i++) {
            final String value = values[i];
            LinearLayout row = Ui.row(this);
            row.setPadding(Ui.dp(this, 14), Ui.dp(this, 13), Ui.dp(this, 14), Ui.dp(this, 13));
            row.setBackground(Ui.ripple(Ui.rounded(this, 0, 12), getColor(R.color.surface_press)));
            TextView tv = Ui.text(this, labels[i], 14, getColor(R.color.text), false);
            row.addView(tv, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            if (!"custom".equals(value) && value.equals(prefs.model())) {
                ImageView check = Ui.icon(this, R.drawable.ic_check, getColor(R.color.brand));
                row.addView(check, new LinearLayout.LayoutParams(Ui.dp(this, 18), Ui.dp(this, 18)));
            }
            row.setOnClickListener(v -> {
                dismissSheet();
                if ("custom".equals(value)) showCustomModelInput();
                else setModelValue(value);
            });
            box.addView(row);
        }
        showSheet(box);
    }

    private void showCustomModelInput() {
        EditText field = styledInput(prefs.model(), "如 gpt-4o-mini / qwen2.5:7b");
        showInputDialog("自定义模型名称", field, "保存", () -> {
            String m = field.getText().toString().trim();
            if (m.isEmpty()) { snack("模型名不能为空", null, null); return; }
            setModelValue(m);
        });
    }

    private void setModelValue(String value) {
        prefs.setModel(value);
        if (conv != null) conv.model = value;
        updateChips();
        updateTitle();
        saveCurrent();
        snack("模型已设为 " + value, null, null);
    }

    private void showEffortSheet() {
        showChoiceSheet("思考强度", new String[]{"快速", "标准", "极致"},
                new String[]{"low", "high", "max"}, prefs.effort(), value -> {
                    prefs.setEffort(value);
                    snack("思考强度已调整", null, null);
                });
    }

    private void showContextLimitSheet() {
        applyContextLimit(prefs.contextLimit());
    }

    /** 实际弹出上下文上限选择，含“手动输入”。供设置页与上下文卡片共用。 */
    private void applyContextLimit(int current) {
        LinearLayout box = Ui.column(this);
        box.addView(sheetTitle("上下文上限"));
        String[] labels = {"128K", "256K", "512K", "1M（默认）", "手动输入…"};
        String[] values = {"128000", "256000", "512000", "1000000", "custom"};
        for (int i = 0; i < labels.length; i++) {
            final String value = values[i];
            LinearLayout row = Ui.row(this);
            row.setPadding(Ui.dp(this, 14), Ui.dp(this, 13), Ui.dp(this, 14), Ui.dp(this, 13));
            row.setBackground(Ui.ripple(Ui.rounded(this, 0, 12), getColor(R.color.surface_press)));
            TextView tv = Ui.text(this, labels[i], 14, getColor(R.color.text), false);
            row.addView(tv, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            if (!"custom".equals(value) && Integer.parseInt(value) == current) {
                ImageView check = Ui.icon(this, R.drawable.ic_check, getColor(R.color.brand));
                row.addView(check, new LinearLayout.LayoutParams(Ui.dp(this, 18), Ui.dp(this, 18)));
            }
            row.setOnClickListener(v -> {
                dismissSheet();
                if ("custom".equals(value)) {
                    showCustomContextInput(current);
                } else {
                    setContextLimitValue(Integer.parseInt(value));
                }
            });
            box.addView(row);
        }
        showSheet(box);
    }

    private void showCustomContextInput(int current) {
        EditText field = styledInput(String.valueOf(current), "例如 200000");
        field.setInputType(InputType.TYPE_CLASS_NUMBER);
        showInputDialog("自定义上下文上限（Token）", field, "保存", () -> {
            String raw = field.getText().toString().trim();
            try {
                int v = Integer.parseInt(raw);
                if (v < 4096 || v > 100_000_000) {
                    snack("请输入 4096 到 100000000 之间的数值", null, null);
                    return;
                }
                setContextLimitValue(v);
            } catch (Exception e) {
                snack("请输入有效的数字", null, null);
            }
        });
    }

    private void setContextLimitValue(int v) {
        prefs.setContextLimit(v);
        if (conv != null) {
            conv.contextLimit = v;
            conv.warnLevel = 0;
            conv.warningDismissed = false;
            saveCurrent();
        }
        maybeWarn();
        updateContextPill();
        snack("上下文上限已设为 " + TokenMeter.formatTokens(v), null, null);
    }

    private void showWarnThresholdSheet() {
        showChoiceSheet("预警轮数", new String[]{"剩余 5 轮", "剩余 10 轮（推荐）", "剩余 15 轮", "剩余 20 轮"},
                new String[]{"5", "10", "15", "20"}, String.valueOf(prefs.warnThreshold()), value -> {
                    prefs.setWarnThreshold(Integer.parseInt(value));
                    if (conv != null) {
                        conv.warnLevel = 0;
                        conv.warningDismissed = false;
                        saveCurrent();
                    }
                    maybeWarn();
                    updateContextPill();
                });
    }

    private void showMaxTokensSheet() {
        showChoiceSheet("最大输出", new String[]{"4K Token", "8K Token（推荐）", "16K Token", "32K Token"},
                new String[]{"4096", "8192", "16384", "32768"}, String.valueOf(prefs.maxTokens()), value -> {
                    prefs.setMaxTokens(Integer.parseInt(value));
                    maybeWarn();
                    updateContextPill();
                });
    }

    // ----------------------------------------------------------- context

    private void showContextSheet() {
        if (conv == null || conv.messages.isEmpty()) {
            snack("开始聊天后，这里会显示还能聊多少轮", null, null);
            return;
        }
        int rounds = TokenMeter.roundsLeft(conv, prefs.maxTokens());
        int used = TokenMeter.used(conv);
        int limit = conv.contextLimit > 0 ? conv.contextLimit : prefs.contextLimit();
        int percent = TokenMeter.percent(conv);
        int avg = TokenMeter.averageRound(conv);

        LinearLayout box = Ui.column(this);
        box.addView(sheetTitle("上下文"));
        TextView big = Ui.text(this, "还能聊约 " + rounds + " 轮", 24,
                rounds <= 3 ? getColor(R.color.danger)
                        : (rounds <= prefs.warnThreshold() ? getColor(R.color.warning_text) : getColor(R.color.text)),
                true);
        big.setGravity(Gravity.CENTER);
        box.addView(big);
        TextView sub = Ui.text(this, "按最近的平均对话长度估算", 11.5f,
                getColor(R.color.text_tertiary), false);
        sub.setGravity(Gravity.CENTER);
        sub.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 14));
        box.addView(sub);

        ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        bar.setProgress(Math.max(1, percent));
        bar.setProgressTintList(ColorStateList.valueOf(
                percent >= 90 ? getColor(R.color.danger) : getColor(R.color.brand)));
        bar.setProgressBackgroundTintList(ColorStateList.valueOf(getColor(R.color.surface_alt)));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 6));
        blp.leftMargin = Ui.dp(this, 4);
        blp.rightMargin = Ui.dp(this, 4);
        box.addView(bar, blp);

        LinearLayout stats = Ui.column(this);
        stats.setPadding(Ui.dp(this, 4), Ui.dp(this, 9), Ui.dp(this, 4), Ui.dp(this, 4));
        stats.addView(contextRow("已使用", TokenMeter.formatTokens(used) + "  (" + percent + "%)"));
        stats.addView(contextRow("上下文上限", TokenMeter.limitLabel(limit)));
        stats.addView(contextRow("平均每轮", "约 " + TokenMeter.formatTokens(avg) + " Token"));
        stats.addView(contextRow("已聊轮数", conv.turns() + " 轮"));
        box.addView(stats);

        TextView note = Ui.text(this, "估算基于最近一次 API 返回的真实 Token 用量；长文件、图片和长回答会让实际轮数减少。",
                10.5f, getColor(R.color.text_tertiary), false);
        note.setPadding(Ui.dp(this, 6), Ui.dp(this, 10), Ui.dp(this, 6), Ui.dp(this, 12));
        box.addView(note);

        TextView export = primaryButton("导出备份", getColor(R.color.brand), Color.WHITE, v -> {
            dismissSheet();
            quickExport();
        });
        box.addView(export);
        TextView fresh = primaryButton("开启新对话", getColor(R.color.surface_alt),
                getColor(R.color.text), v -> {
                    dismissSheet();
                    requestNewChat();
                });
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 46));
        flp.topMargin = Ui.dp(this, 8);
        box.addView(fresh, flp);
        TextView adjust = Ui.text(this, "调整上下文上限", 12, getColor(R.color.text_secondary), false);
        adjust.setGravity(Gravity.CENTER);
        adjust.setPadding(0, Ui.dp(this, 12), 0, Ui.dp(this, 4));
        adjust.setOnClickListener(v -> {
            dismissSheet();
            showContextLimitSheet();
        });
        box.addView(adjust);
        showSheet(box);
    }

    private View contextRow(String label, String value) {
        LinearLayout row = Ui.row(this);
        row.setPadding(Ui.dp(this, 4), Ui.dp(this, 6), Ui.dp(this, 4), Ui.dp(this, 6));
        row.addView(Ui.text(this, label, 12.5f, getColor(R.color.text_secondary), false),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        row.addView(Ui.text(this, value, 12.5f, getColor(R.color.text), true));
        return row;
    }

    private TextView primaryButton(String label, int bg, int fg, View.OnClickListener click) {
        TextView tv = Ui.text(this, label, 14, fg, true);
        tv.setGravity(Gravity.CENTER);
        tv.setBackground(Ui.ripple(Ui.rounded(this, bg, 13), Ui.blend(bg, Color.BLACK, 0.08f)));
        tv.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 46)));
        tv.setOnClickListener(click);
        return tv;
    }

    // --------------------------------------------------------- onboarding

    private void openOnboarding() {
        if (onboardingOverlay != null) return;
        FrameLayout overlay = new FrameLayout(this);
        overlay.setBackgroundColor(getColor(R.color.bg));
        onboardingOverlay = overlay;
        root.addView(overlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        TextView skip = Ui.text(this, "跳过", 12.5f, getColor(R.color.text_secondary), false);
        skip.setPadding(Ui.dp(this, 16), Ui.dp(this, 10), Ui.dp(this, 16), Ui.dp(this, 10));
        FrameLayout.LayoutParams sklp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sklp.gravity = Gravity.TOP | Gravity.END;
        sklp.topMargin = statusBarHeight() + Ui.dp(this, 6);
        overlay.addView(skip, sklp);
        skip.setOnClickListener(v -> {
            prefs.setOnboardingDone(true);
            closeOnboarding();
        });

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        overlay.addView(scroll, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        LinearLayout box = Ui.column(this);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(Ui.dp(this, 28), statusBarHeight() + Ui.dp(this, 38),
                Ui.dp(this, 28), Ui.dp(this, 34));
        scroll.addView(box);
        skip.bringToFront();

        LogoView logo = new LogoView(this);
        logo.setBadge(true);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(Ui.dp(this, 92), Ui.dp(this, 92));
        llp.bottomMargin = Ui.dp(this, 18);
        box.addView(logo, llp);

        TextView title = Ui.text(this, "欢迎使用大肥鱼", 27, getColor(R.color.text), true);
        title.setGravity(Gravity.CENTER);
        box.addView(title);
        TextView sub = Ui.text(this, "一条胖乎乎的小鱼，直连 DeepSeek。", 13.5f,
                getColor(R.color.text_secondary), false);
        sub.setGravity(Gravity.CENTER);
        sub.setPadding(0, Ui.dp(this, 9), 0, Ui.dp(this, 24));
        box.addView(sub);

        box.addView(featureRow(R.drawable.ic_sparkle, "DeepSeek V4.1 直连",
                "1M 上下文 · 深度思考 · 联网搜索"));
        box.addView(featureRow(R.drawable.ic_file, "照片和文件都能读",
                "PDF、Word、PPT、表格、图片，直接分析"));
        box.addView(featureRow(R.drawable.ic_warning, "上下文预警",
                "剩余 10 轮时提醒，右上角实时显示"));
        box.addView(featureRow(R.drawable.ic_download, "一键导出，无缝续聊",
                "导出 .dfy 备份，导入后像没离开过"));

        TextView keyLabel = Ui.text(this, "填入你的 DeepSeek API Key", 12,
                getColor(R.color.text_secondary), true);
        keyLabel.setPadding(Ui.dp(this, 4), Ui.dp(this, 24), 0, Ui.dp(this, 7));
        box.addView(keyLabel);
        EditText key = styledInput(prefs.apiKey(), "sk-...");
        key.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        box.addView(key, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 48)));

        TextView start = primaryButton("开始使用", getColor(R.color.brand), Color.WHITE, v -> {
            String k = key.getText().toString().trim();
            if (k.isEmpty()) {
                snack("请先填入 API Key，或点右上角跳过", null, null);
                return;
            }
            prefs.setApiKey(k);
            prefs.setOnboardingDone(true);
            closeOnboarding();
            snack("设置完成，开始聊天吧", null, null);
        });
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 50));
        slp.topMargin = Ui.dp(this, 16);
        box.addView(start, slp);

        TextView get = Ui.text(this, "去 DeepSeek 开放平台获取 API Key  ›", 12,
                getColor(R.color.brand), false);
        get.setGravity(Gravity.CENTER);
        get.setPadding(0, Ui.dp(this, 14), 0, Ui.dp(this, 8));
        get.setOnClickListener(v -> Ui.openUrl(this, "https://platform.deepseek.com/api_keys"));
        box.addView(get);

        TextView privacy = Ui.text(this, "Key 只会加密保存在这台手机上。对话内容仅发送给 DeepSeek。",
                10.5f, getColor(R.color.text_tertiary), false);
        privacy.setGravity(Gravity.CENTER);
        privacy.setPadding(0, Ui.dp(this, 10), 0, 0);
        box.addView(privacy);

        overlay.setAlpha(0f);
        overlay.animate().alpha(1f).setDuration(220).start();
    }

    private View featureRow(int icon, String title, String subtitle) {
        LinearLayout row = Ui.row(this);
        row.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 8));
        FrameLayout badge = new FrameLayout(this);
        badge.setBackground(Ui.rounded(this, getColor(R.color.brand_soft), 11));
        ImageView iv = Ui.icon(this, icon, getColor(R.color.brand));
        int p = Ui.dp(this, 8);
        badge.addView(iv, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        iv.setPadding(p, p, p, p);
        row.addView(badge, new LinearLayout.LayoutParams(Ui.dp(this, 38), Ui.dp(this, 38)));
        LinearLayout col = Ui.column(this);
        col.addView(Ui.text(this, title, 13.5f, getColor(R.color.text), true));
        TextView s = Ui.text(this, subtitle, 11.5f, getColor(R.color.text_secondary), false);
        s.setPadding(0, Ui.dp(this, 2), 0, 0);
        col.addView(s);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        clp.leftMargin = Ui.dp(this, 11);
        row.addView(col, clp);
        return row;
    }

    private void closeOnboarding() {
        if (onboardingOverlay != null) {
            root.removeView(onboardingOverlay);
            onboardingOverlay = null;
        }
    }

    // -------------------------------------------------------------- about

    private void showAbout() {
        closeDrawer();
        LinearLayout box = Ui.column(this);
        box.setPadding(Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 8));
        LogoView logo = new LogoView(this);
        logo.setBadge(true);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(Ui.dp(this, 64), Ui.dp(this, 64));
        llp.gravity = Gravity.CENTER_HORIZONTAL;
        llp.topMargin = Ui.dp(this, 4);
        box.addView(logo, llp);
        TextView name = Ui.text(this, "大肥鱼", 22, getColor(R.color.text), true);
        name.setGravity(Gravity.CENTER);
        name.setPadding(0, Ui.dp(this, 10), 0, 0);
        box.addView(name);
        TextView version = Ui.text(this, "v1.0.0 · Android 15", 11, getColor(R.color.text_tertiary), false);
        version.setGravity(Gravity.CENTER);
        version.setPadding(0, Ui.dp(this, 3), 0, Ui.dp(this, 14));
        box.addView(version);
        TextView desc = Ui.text(this, "一个直接连接 DeepSeek API 的轻量聊天客户端。"
                        + "没有中间服务器，没有广告，也不收集你的聊天记录。\n\n"
                        + "深度思考、联网搜索、图片与文档解析由 DeepSeek 提供。"
                        + "上下文剩余轮数为本地估算，仅供参考。",
                12.5f, getColor(R.color.text_secondary), false);
        desc.setLineSpacing(Ui.dp(this, 4), 1.02f);
        desc.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 12), Ui.dp(this, 16));
        box.addView(desc);
        box.addView(sheetRow(R.drawable.ic_link, "DeepSeek 开放平台",
                "platform.deepseek.com", v -> Ui.openUrl(this, "https://platform.deepseek.com")));
        box.addView(sheetRow(R.drawable.ic_info, "API 文档",
                "api-docs.deepseek.com", v -> Ui.openUrl(this, "https://api-docs.deepseek.com/zh-cn/")));
        TextView legal = Ui.text(this, "本应用为第三方客户端，与 DeepSeek 官方无隶属关系。",
                10, getColor(R.color.text_tertiary), false);
        legal.setGravity(Gravity.CENTER);
        legal.setPadding(0, Ui.dp(this, 14), 0, Ui.dp(this, 4));
        box.addView(legal);
        showSheet(box);
    }

    // ---------------------------------------------------------- lifecycle

    @Override
    public void onBackPressed() {
        if (previewOverlay != null) { closePreview(); return; }
        if (sheetOverlay != null) { dismissSheet(); return; }
        if (dialogOverlay != null) { closeDialog(); return; }
        if (settingsOverlay != null) { closeSettings(); return; }
        if (onboardingOverlay != null) {
            prefs.setOnboardingDone(true);
            closeOnboarding();
            return;
        }
        if (drawerOpen) { closeDrawer(); return; }
        if (streaming) {
            showConfirm("停止生成", "大肥鱼还在回答，停止并退出吗？", "停止", false, () -> {
                stopStreaming();
                finish();
            });
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onPause() {
        saveCurrent();
        super.onPause();
    }

    @Override
    protected void onStop() {
        saveCurrent();
        // 退到后台时静默自动备份（节流在方法内部），保证卸载前记录仍在手机存储里。
        ui.postDelayed(() -> autoBackupIfNeeded(false), 300);
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        if (api != null) api.cancelChat();
        super.onDestroy();
    }
}
