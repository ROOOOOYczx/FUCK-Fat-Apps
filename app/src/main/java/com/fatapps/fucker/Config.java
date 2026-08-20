package com.fatapps.fucker;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;

import java.util.concurrent.atomic.AtomicReference;

/** Shared configuration for the module UI and the hooked target process. */
public final class Config {
    public static final String PREFS = "fucker_preferences";
    public static final String KEY_ENABLED = "enabled";
    public static final String KEY_SKIP_SPLASH_AD = "skip_splash_ad";
    public static final String KEY_HIDE_BANNER = "hide_banner";
    public static final String KEY_SUPPRESS_BANNER_INIT = "suppress_banner_init";
    public static final String KEY_HIDE_HOME_TODAY = "hide_home_today";
    public static final String KEY_HIDE_AI_TAB = "hide_ai_tab";
    public static final String KEY_HIDE_MINE_TAIL = "hide_mine_tail";

    public static final Uri CONFIG_URI = Uri.parse("content://com.fatapps.fucker.config/config");

    private static final AtomicReference<Flags> CACHED_TARGET_FLAGS = new AtomicReference<>();

    private Config() {
    }

    public static Flags readLocal(Context context) {
        SharedPreferences preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return fromPreferences(preferences);
    }

    public static Flags fromPreferences(SharedPreferences preferences) {
        return new Flags(
                preferences.getBoolean(KEY_ENABLED, true),
                preferences.getBoolean(KEY_SKIP_SPLASH_AD, true),
                preferences.getBoolean(KEY_HIDE_BANNER, true),
                preferences.getBoolean(KEY_SUPPRESS_BANNER_INIT, true),
                preferences.getBoolean(KEY_HIDE_HOME_TODAY, true),
                preferences.getBoolean(KEY_HIDE_AI_TAB, true),
                preferences.getBoolean(KEY_HIDE_MINE_TAIL, true)
        );
    }

    public static Flags readFromTarget(Context targetContext) {
        Flags flags = readFromTargetOrNull(targetContext);
        return flags != null ? flags : Flags.defaults();
    }

    /** Returns {@code null} when MIUI prevents the target from starting this app's provider. */
    public static Flags readFromTargetOrNull(Context targetContext) {
        Flags cached = CACHED_TARGET_FLAGS.get();
        if (cached != null) {
            return cached;
        }
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                Bundle result = targetContext.getContentResolver().call(CONFIG_URI, "get_config", null, null);
                if (result != null) {
                    Flags flags = Flags.fromBundle(result);
                    CACHED_TARGET_FLAGS.compareAndSet(null, flags);
                    return flags;
                }
            } catch (Throwable ignored) {
                // The target can start before the module provider process is ready.
            }
            if (attempt < 1) {
                try {
                    Thread.sleep(50L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        // Do not cache failures: a later process or retry may find the provider.
        return null;
    }

    public static Bundle toBundle(Context context) {
        return readLocal(context).toBundle();
    }

    public static final class Flags {
        public final boolean enabled;
        public final boolean skipSplashAd;
        public final boolean hideBanner;
        public final boolean suppressBannerInit;
        public final boolean hideHomeToday;
        public final boolean hideAiTab;
        public final boolean hideMineTail;

        public Flags(boolean enabled, boolean skipSplashAd, boolean hideBanner, boolean suppressBannerInit,
                     boolean hideHomeToday, boolean hideAiTab, boolean hideMineTail) {
            this.enabled = enabled;
            this.skipSplashAd = skipSplashAd;
            this.hideBanner = hideBanner;
            this.suppressBannerInit = suppressBannerInit;
            this.hideHomeToday = hideHomeToday;
            this.hideAiTab = hideAiTab;
            this.hideMineTail = hideMineTail;
        }

        public static Flags defaults() {
            return new Flags(true, true, true, true, true, true, true);
        }

        public static Flags fromBundle(Bundle bundle) {
            if (bundle == null) {
                return defaults();
            }
            return new Flags(
                    bundle.getBoolean(KEY_ENABLED, true),
                    bundle.getBoolean(KEY_SKIP_SPLASH_AD, true),
                    bundle.getBoolean(KEY_HIDE_BANNER, true),
                    bundle.getBoolean(KEY_SUPPRESS_BANNER_INIT, true),
                    bundle.getBoolean(KEY_HIDE_HOME_TODAY, true),
                    bundle.getBoolean(KEY_HIDE_AI_TAB, true),
                    bundle.getBoolean(KEY_HIDE_MINE_TAIL, true)
            );
        }

        public Bundle toBundle() {
            Bundle bundle = new Bundle();
            bundle.putBoolean(KEY_ENABLED, enabled);
            bundle.putBoolean(KEY_SKIP_SPLASH_AD, skipSplashAd);
            bundle.putBoolean(KEY_HIDE_BANNER, hideBanner);
            bundle.putBoolean(KEY_SUPPRESS_BANNER_INIT, suppressBannerInit);
            bundle.putBoolean(KEY_HIDE_HOME_TODAY, hideHomeToday);
            bundle.putBoolean(KEY_HIDE_AI_TAB, hideAiTab);
            bundle.putBoolean(KEY_HIDE_MINE_TAIL, hideMineTail);
            return bundle;
        }
    }
}
