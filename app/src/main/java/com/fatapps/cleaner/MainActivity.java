package com.fatapps.cleaner;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Space;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.fatapps.cleaner.Config.Flags;

import java.io.File;

/** Small dependency-free settings screen for the LSPosed module. */
public final class MainActivity extends Activity {
    private static final int DP = 1;
    private SharedPreferences preferences;
    private Switch moduleSwitch;
    private Switch splashSwitch;
    private Switch bannerSwitch;
    private Switch bannerInitSwitch;
    private Switch homeTodaySwitch;
    private Switch aiTabSwitch;
    private Switch mineTailSwitch;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        preferences = getSharedPreferences(Config.PREFS, MODE_PRIVATE);
        securePrivatePreferences();
        setContentView(createContent());
    }

    private View createContent() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.rgb(245, 247, 251));

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(20), dp(20), dp(20), dp(32));
        scroll.addView(page, new ScrollView.LayoutParams(-1, -2));

        TextView title = text("FUCK Fat Apps", 28, Color.rgb(28, 36, 48), true);
        page.addView(title, params(-1, -2));
        TextView subtitle = text("LSPosed 应用净化模块", 14, Color.rgb(107, 116, 128), false);
        page.addView(subtitle, params(-1, -2));
        page.addView(space(18));

        LinearLayout moduleCard = card();
        moduleSwitch = addSwitch(moduleCard, "模块总开关", "关闭后不对任何目标应用执行 Hook", Config.KEY_ENABLED, true);
        page.addView(moduleCard, params(-1, -2));

        page.addView(sectionTitle("适配应用"), params(-1, -2));
        LinearLayout amapCard = card();
        TextView target = text("高德地图", 17, Color.rgb(28, 36, 48), true);
        amapCard.addView(target, params(-1, -2));
        TextView packageName = text("com.autonavi.minimap · 16.22 / 16.23", 12, Color.rgb(107, 116, 128), false);
        amapCard.addView(packageName, params(-1, -2));
        amapCard.addView(space(8));
        splashSwitch = addSwitch(amapCard, "跳过启动页广告", "拦截高德启动页广告生命周期；地图主体不受影响", Config.KEY_SKIP_SPLASH_AD, true);
        bannerSwitch = addSwitch(amapCard, "隐藏推广横幅 / Banner", "隐藏高德 Banner 视图，可能影响部分非广告横幅", Config.KEY_HIDE_BANNER, true);
        bannerInitSwitch = addSwitch(amapCard, "阻止 Banner 初始化（实验）", "阻止 Banner 管理器初始化，适合希望更彻底净化的场景", Config.KEY_SUPPRESS_BANNER_INIT, false);
        homeTodaySwitch = addSwitch(amapCard, "精简首页内容", "保留搜索栏、15 个工具、路线卡片和常用地点；移除天气及以下内容", Config.KEY_HIDE_HOME_TODAY, false);
        aiTabSwitch = addSwitch(amapCard, "移除 AI 长按对话入口", "移除底部中间的“长按说话”按钮", Config.KEY_HIDE_AI_TAB, false);
        mineTailSwitch = addSwitch(amapCard, "精简“我的”页", "隐藏“借钱”入口，并移除“达人任务”及其下方内容", Config.KEY_HIDE_MINE_TAIL, false);
        page.addView(amapCard, params(-1, -2));

        page.addView(sectionTitle("使用说明"), params(-1, -2));
        LinearLayout infoCard = card();
        TextView info = text("安装后请在 LSPosed 中启用本模块，并只勾选高德地图作用域。修改开关后，需要强制停止并重新打开高德地图才能生效。页面隐藏功能按高德当前版本的资源和页面结构适配，遇到显示异常时可单独关闭对应开关。", 13, Color.rgb(107, 116, 128), false);
        info.setLineSpacing(dp(3), 1.0f);
        infoCard.addView(info, params(-1, -2));
        Button settings = new Button(this);
        settings.setText("打开 LSPosed / 应用详情");
        settings.setOnClickListener(v -> openAppDetails());
        infoCard.addView(settings, params(-1, -2));
        page.addView(infoCard, params(-1, -2));

        moduleSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            save(Config.KEY_ENABLED, isChecked);
            setFeatureEnabled(isChecked);
        });
        setFeatureEnabled(moduleSwitch.isChecked());
        return scroll;
    }

    private Switch addSwitch(LinearLayout parent, String title, String summary, String key, boolean defaultValue) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(8), 0, dp(8));

        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1));
        labels.addView(text(title, 15, Color.rgb(28, 36, 48), false), params(-1, -2));
        TextView detail = text(summary, 12, Color.rgb(107, 116, 128), false);
        detail.setLineSpacing(dp(2), 1.0f);
        labels.addView(detail, params(-1, -2));
        row.addView(labels);

        Switch toggle = new Switch(this);
        toggle.setChecked(preferences.getBoolean(key, defaultValue));
        toggle.setOnCheckedChangeListener((buttonView, isChecked) -> save(key, isChecked));
        row.addView(toggle, new LinearLayout.LayoutParams(-2, -2));
        parent.addView(row, params(-1, -2));
        return toggle;
    }

    private void setFeatureEnabled(boolean enabled) {
        if (splashSwitch != null) splashSwitch.setEnabled(enabled);
        if (bannerSwitch != null) bannerSwitch.setEnabled(enabled);
        if (bannerInitSwitch != null) bannerInitSwitch.setEnabled(enabled);
        if (homeTodaySwitch != null) homeTodaySwitch.setEnabled(enabled);
        if (aiTabSwitch != null) aiTabSwitch.setEnabled(enabled);
        if (mineTailSwitch != null) mineTailSwitch.setEnabled(enabled);
    }

    private void save(String key, boolean value) {
        preferences.edit().putBoolean(key, value).apply();
        Toast.makeText(this, "已保存，重启高德地图后生效", Toast.LENGTH_SHORT).show();
    }

    private void securePrivatePreferences() {
        try {
            File dataDir = getDataDir();
            setOwnerOnly(dataDir, true);
            File preferencesDir = new File(dataDir, "shared_prefs");
            setOwnerOnly(preferencesDir, true);
            File preferencesFile = new File(preferencesDir, Config.PREFS + ".xml");
            setOwnerOnly(preferencesFile, false);
        } catch (Throwable ignored) {
            // Android normally creates these paths owner-only already.
        }
    }

    private static void setOwnerOnly(File file, boolean directory) {
        if (!file.exists()) {
            return;
        }
        file.setReadable(false, false);
        file.setWritable(false, false);
        file.setExecutable(false, false);
        file.setReadable(true, true);
        file.setWritable(true, true);
        if (directory) {
            file.setExecutable(true, true);
        }
    }

    private void openAppDetails() {
        try {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setData(Uri.parse("package:com.fatapps.cleaner"));
            startActivity(intent);
        } catch (Throwable ignored) {
            Toast.makeText(this, "请在 LSPosed 中手动启用模块", Toast.LENGTH_LONG).show();
        }
    }

    private TextView sectionTitle(String value) {
        TextView view = text(value, 14, Color.rgb(33, 107, 255), true);
        view.setPadding(dp(2), dp(20), dp(2), dp(8));
        return view;
    }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(14), dp(16), dp(8));
        android.graphics.drawable.GradientDrawable background = new android.graphics.drawable.GradientDrawable();
        background.setColor(Color.WHITE);
        background.setCornerRadius(dp(14));
        card.setBackground(background);
        return card;
    }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setTypeface(bold ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        return view;
    }

    private Space space(int height) {
        Space space = new Space(this);
        space.setLayoutParams(new LinearLayout.LayoutParams(1, dp(height)));
        return space;
    }

    private LinearLayout.LayoutParams params(int width, int height) {
        return new LinearLayout.LayoutParams(width, height);
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
