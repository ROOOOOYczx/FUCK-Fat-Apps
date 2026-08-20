package com.fatapps.cleaner.hook;

import android.app.Application;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.util.Base64;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.ImageView;
import android.widget.TextView;

import com.fatapps.cleaner.Config;
import com.fatapps.cleaner.Config.Flags;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

/** LSPosed entry point. Hooks are deliberately scoped to AMap's package. */
public final class MainHook implements IXposedHookLoadPackage {
    private static final String TARGET_PACKAGE = "com.autonavi.minimap";
    private static final String TAG = "[FUCK Fat Apps] ";
    private static final String MIRRORED_PREFS = "fatapps_cleaner_mirrored_preferences";
    private static final String MIRRORED_PREFS_VERSION = "_mirror_version";
    private static volatile boolean installed;
    private static volatile Flags flags = Flags.defaults();
    private static final Map<Object, Integer> PAGE_ADAPTER_LIMITS = new WeakHashMap<>();
    private static final Set<Method> HOOKED_ITEM_COUNT_METHODS =
            Collections.newSetFromMap(new ConcurrentHashMap<>());
    private static final Set<Object> BLOCKED_HTTP_REQUEST_OBJECTS =
            Collections.newSetFromMap(Collections.synchronizedMap(new WeakHashMap<>()));
    private static final Map<View, CleanToolIconDrawable> HOME_CLEAN_TOOL_ICONS =
            new WeakHashMap<>();
    private static final Map<String, Bitmap> CLEAN_TOOL_BITMAPS = new ConcurrentHashMap<>();
    private static volatile boolean loggedOnlineSceneRecommendBlock;

    @Override
    public void handleLoadPackage(LoadPackageParam lpparam) throws Throwable {
        if (!TARGET_PACKAGE.equals(lpparam.packageName)) {
            return;
        }

        XposedBridge.log(TAG + "loaded into " + lpparam.packageName + " process=" + lpparam.processName);
        XposedHelpers.findAndHookMethod(Application.class, "attach", Context.class, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (installed) {
                    return;
                }
                Context context = (Context) param.args[0];
                flags = readFlags(context);
                if (TARGET_PACKAGE.equals(lpparam.processName)) {
                    configureCommercialSmartMapCache(
                            context, flags.enabled && flags.hideHomeToday);
                }
                if (!flags.enabled) {
                    XposedBridge.log(TAG + "disabled by user");
                    installed = true;
                    return;
                }
                try {
                    installHooks(context.getClassLoader());
                    installed = true;
                    XposedBridge.log(TAG + "hooks installed: splash=" + flags.skipSplashAd
                            + ", banner=" + flags.hideBanner
                            + ", bannerInit=" + flags.suppressBannerInit
                            + ", homeToday=" + flags.hideHomeToday
                            + ", aiTab=" + flags.hideAiTab
                            + ", mineTail=" + flags.hideMineTail);
                } catch (Throwable error) {
                    XposedBridge.log(TAG + "install failed: " + error);
                }
            }
        });
    }

    private static void configureCommercialSmartMapCache(Context context, boolean clean) {
        try {
            java.io.File filesDir = context.getExternalFilesDir(null);
            if (filesDir == null) {
                return;
            }
            java.io.File renderDir = new java.io.File(filesDir, "render");
            if (!renderDir.isDirectory()) {
                return;
            }
            java.io.File backupRoot = new java.io.File(filesDir,
                    ".fatapps_cleaner_backup/smartmap");
            if (!clean) {
                restoreSmartMapDirectory(renderDir, backupRoot, "dl_sql_smart");
                restoreSmartMapDirectory(renderDir, backupRoot, "dl_sql_smart_temp");
                return;
            }
            if (!backupRoot.isDirectory() && !backupRoot.mkdirs()) {
                XposedBridge.log(TAG + "cannot create SmartMap backup root");
                return;
            }
            blockSmartMapDirectory(renderDir, backupRoot, "dl_sql_smart");
            blockSmartMapDirectory(renderDir, backupRoot, "dl_sql_smart_temp");
        } catch (Throwable error) {
            XposedBridge.log(TAG + "cannot configure SmartMap cache: " + error);
        }
    }

    /**
     * Replaces the recommendation database directory with a regular file. The downloader's
     * mkdir/open sequence then fails before it can refresh the commercial SmartMap layer.
     * The complete original directory is retained beside it for a reversible restore.
     */
    private static void blockSmartMapDirectory(java.io.File renderDir,
            java.io.File backupRoot, String name)
            throws java.io.IOException {
        java.io.File directory = new java.io.File(renderDir, name);
        java.io.File backup = new java.io.File(backupRoot, name);
        java.io.File legacyBackup = new java.io.File(renderDir,
                name + ".cleaner_backup");

        if (!backup.exists() && legacyBackup.isDirectory()) {
            XposedBridge.log(TAG + "migrated SmartMap backup " + name + "="
                    + migrateSmartMapBackup(legacyBackup, backup));
        }

        if (directory.isFile()) {
            XposedBridge.log(TAG + "SmartMap path already blocked: " + name);
            return;
        }
        if (directory.isDirectory() && !backup.exists()) {
            if (!directory.renameTo(backup)) {
                XposedBridge.log(TAG + "cannot quarantine SmartMap directory: " + name);
                return;
            }
        } else if (directory.isDirectory()) {
            removeKnownSmartMapFiles(directory);
            String[] remaining = directory.list();
            if (remaining == null || remaining.length != 0 || !directory.delete()) {
                XposedBridge.log(TAG + "preserved non-empty SmartMap directory: " + name);
                return;
            }
        }
        if (!directory.exists()) {
            XposedBridge.log(TAG + "blocked SmartMap download path " + name + "="
                    + directory.createNewFile());
        }
    }

    private static void restoreSmartMapDirectory(java.io.File renderDir,
            java.io.File backupRoot, String name) {
        java.io.File directory = new java.io.File(renderDir, name);
        java.io.File backup = new java.io.File(backupRoot, name);
        java.io.File legacyBackup = new java.io.File(renderDir,
                name + ".cleaner_backup");
        try {
            if (!backup.exists() && legacyBackup.isDirectory()) {
                migrateSmartMapBackup(legacyBackup, backup);
            }
            if (directory.isFile() && !directory.delete()) {
                XposedBridge.log(TAG + "cannot remove SmartMap blocker: " + name);
                return;
            }
            if (directory.isDirectory() && backup.isDirectory()) {
                removeKnownSmartMapFiles(directory);
                String[] remaining = directory.list();
                if (remaining == null || remaining.length != 0 || !directory.delete()) {
                    XposedBridge.log(TAG + "cannot restore over non-empty directory: " + name);
                    return;
                }
            }
            if (!directory.exists() && backup.isDirectory()) {
                XposedBridge.log(TAG + "restored SmartMap directory " + name + "="
                        + backup.renameTo(directory));
            }
        } catch (Throwable error) {
            XposedBridge.log(TAG + "cannot restore SmartMap directory " + name + ": " + error);
        }
    }

    private static void removeKnownSmartMapFiles(java.io.File directory) {
        String cacheName = "smartmap_file_cache_10000001.ans";
        java.io.File active = new java.io.File(directory, cacheName);
        java.io.File temporary = new java.io.File(directory, cacheName + ".bak");
        if (active.isFile()) {
            active.delete();
        }
        if (temporary.isFile()) {
            temporary.delete();
        }
    }

    private static boolean migrateSmartMapBackup(java.io.File legacy,
            java.io.File destination) throws java.io.IOException {
        legacy.setWritable(true, true);
        if (legacy.renameTo(destination)) {
            return true;
        }
        if (!destination.isDirectory() && !destination.mkdirs()) {
            return false;
        }
        String cacheName = "smartmap_file_cache_10000001.ans";
        moveSmartMapBackupFile(new java.io.File(legacy, cacheName),
                new java.io.File(destination, cacheName));
        moveSmartMapBackupFile(new java.io.File(legacy, cacheName + ".bak"),
                new java.io.File(destination, cacheName + ".bak"));
        String[] remaining = legacy.list();
        return remaining != null && remaining.length == 0 && legacy.delete();
    }

    private static void moveSmartMapBackupFile(java.io.File source,
            java.io.File destination) throws java.io.IOException {
        if (!source.isFile() || destination.exists()) {
            return;
        }
        source.setWritable(true, true);
        if (source.renameTo(destination)) {
            return;
        }
        java.io.FileInputStream input = new java.io.FileInputStream(source);
        java.io.FileOutputStream output = new java.io.FileOutputStream(destination);
        try {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            output.getFD().sync();
        } finally {
            try {
                output.close();
            } finally {
                input.close();
            }
        }
        if (!source.delete()) {
            XposedBridge.log(TAG + "copied but could not remove legacy SmartMap file");
        }
    }

    private static Flags readFlags(Context targetContext) {
        Flags providerFlags = Config.readFromTargetOrNull(targetContext);
        if (providerFlags != null) {
            XposedBridge.log(TAG + "config source: provider");
            rememberFlagsInTarget(targetContext, providerFlags);
            return providerFlags;
        }
        Flags mirroredFlags = readFlagsMirroredInTarget(targetContext);
        if (mirroredFlags != null) {
            XposedBridge.log(TAG + "config source: target mirror");
            return mirroredFlags;
        }
        XposedBridge.log(TAG + "config source: defaults");
        return Flags.defaults();
    }

    private static void rememberFlagsInTarget(Context targetContext, Flags values) {
        try {
            targetContext.getSharedPreferences(MIRRORED_PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .putInt(MIRRORED_PREFS_VERSION, 1)
                    .putBoolean(Config.KEY_ENABLED, values.enabled)
                    .putBoolean(Config.KEY_SKIP_SPLASH_AD, values.skipSplashAd)
                    .putBoolean(Config.KEY_HIDE_BANNER, values.hideBanner)
                    .putBoolean(Config.KEY_SUPPRESS_BANNER_INIT, values.suppressBannerInit)
                    .putBoolean(Config.KEY_HIDE_HOME_TODAY, values.hideHomeToday)
                    .putBoolean(Config.KEY_HIDE_AI_TAB, values.hideAiTab)
                    .putBoolean(Config.KEY_HIDE_MINE_TAIL, values.hideMineTail)
                    .commit();
        } catch (Throwable error) {
            XposedBridge.log(TAG + "cannot mirror configuration in target: " + error);
        }
    }

    private static Flags readFlagsMirroredInTarget(Context targetContext) {
        try {
            android.content.SharedPreferences preferences = targetContext.getSharedPreferences(
                    MIRRORED_PREFS, Context.MODE_PRIVATE);
            if (preferences.getInt(MIRRORED_PREFS_VERSION, 0) != 1) {
                return null;
            }
            return Config.fromPreferences(preferences);
        } catch (Throwable error) {
            XposedBridge.log(TAG + "cannot read target configuration mirror: " + error);
            return null;
        }
    }

    private static void installHooks(ClassLoader classLoader) {
        if (flags.skipSplashAd) {
            hookSplashLifecycle(classLoader);
        }
        if (flags.hideBanner || flags.hideHomeToday || flags.hideAiTab) {
            hookBannerViews(classLoader);
        }
        if (flags.hideMineTail || flags.hideHomeToday) {
            hookMinePageTail(classLoader);
        }
        if (flags.hideHomeToday) {
            hookHomeMapRecommendations(classLoader);
        }
        if (flags.suppressBannerInit) {
            hookBannerManagers(classLoader);
        }
    }

    private static void hookHomeMapRecommendations(ClassLoader classLoader) {
        hookCommercialSceneNetworkBlocker(classLoader);

        Class<?> sceneRecommendService = XposedHelpers.findClassIfExists(
                "com.autonavi.bundle.uitemplate.scenerecommend.SceneRecommendServiceImpl",
                classLoader);
        if (sceneRecommendService != null) {
            int hookCount = XposedBridge.hookAllMethods(sceneRecommendService, "request",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            param.setResult(-1);
                        }
                    }).size();
            XposedBridge.log(TAG + "blocked scene recommendation requests ("
                    + hookCount + ")");
        }

        Class<?> floatingButtonData = XposedHelpers.findClassIfExists(
                "com.autonavi.bundle.uitemplate.mapwidget.widget.maptag.FloatingButtonData",
                classLoader);
        if (floatingButtonData != null) {
            int hookCount = XposedBridge.hookAllMethods(floatingButtonData, "fromJson",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            param.setResult(null);
                        }
                    }).size();
            XposedBridge.log(TAG + "blocked map recommendation floating button ("
                    + hookCount + ")");
        }

        Class<?> activityWidget = XposedHelpers.findClassIfExists(
                "com.autonavi.bundle.uitemplate.mapwidget.widget.activity.OperateActivityMapWidget",
                classLoader);
        if (activityWidget != null) {
            XC_MethodHook forceGone = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.args.length > 0 && param.args[0] instanceof Integer) {
                        param.args[0] = View.GONE;
                    }
                }
            };
            int visibilityHooks = XposedBridge.hookAllMethods(
                    activityWidget, "setContentViewVisibility", forceGone).size();
            visibilityHooks += XposedBridge.hookAllMethods(
                    activityWidget, "setVisibility", forceGone).size();
            XposedBridge.hookAllConstructors(activityWidget, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        Object content = XposedHelpers.callMethod(param.thisObject, "getContentView");
                        if (content instanceof View) {
                            ((View) content).setVisibility(View.GONE);
                        }
                    } catch (Throwable error) {
                        XposedBridge.log(TAG + "cannot hide map activity widget: " + error);
                    }
                }
            });
            XposedBridge.log(TAG + "hidden map activity widget (" + visibilityHooks
                    + " visibility hooks)");
        }
    }

    private static void hookCommercialSceneNetworkBlocker(ClassLoader classLoader) {
        String[] requestClassNames = {
                "com.amap.network.api.http.request.HttpRequest",
                "com.autonavi.core.network.inter.request.HttpRequest"
        };
        int hookCount = 0;
        for (String className : requestClassNames) {
            Class<?> requestClass = XposedHelpers.findClassIfExists(className, classLoader);
            if (requestClass == null) {
                continue;
            }
            boolean coreRequest = className.startsWith("com.autonavi.core.");
            hookCount += XposedBridge.hookAllMethods(requestClass, "setUrl",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (!containsCommercialSceneRecommendation(
                                    param.thisObject, param.args)) {
                                return;
                            }
                            BLOCKED_HTTP_REQUEST_OBJECTS.add(param.thisObject);
                            if (coreRequest) {
                                try {
                                    XposedHelpers.setObjectField(param.thisObject, "f",
                                            "http://127.0.0.1:9/fatapps-blocked-scene-recommend");
                                } catch (Throwable ignored) {
                                    // onStart cancellation below is the primary stop mechanism.
                                }
                            }
                            logOnlineSceneRecommendationBlock();
                        }
                    }).size();
        }

        Class<?> phaseListener = XposedHelpers.findClassIfExists(
                "com.amap.bundle.network.biz.statistic.HttpRequestPhaseListener", classLoader);
        if (phaseListener != null) {
            hookCount += XposedBridge.hookAllMethods(phaseListener, "onStart",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (param.args.length == 0) {
                                return;
                            }
                            Object request = param.args[0];
                            if (!BLOCKED_HTTP_REQUEST_OBJECTS.contains(request)
                                    && !containsCommercialSceneRecommendation(request, null)) {
                                return;
                            }
                            try {
                                XposedHelpers.callMethod(request, "cancel");
                            } catch (Throwable ignored) {
                                try {
                                    XposedHelpers.setBooleanField(request, "n", true);
                                } catch (Throwable ignoredAgain) {
                                    // The URL was already redirected in setUrl.
                                }
                            }
                            logOnlineSceneRecommendationBlock();
                        }
                    }).size();
        }
        XposedBridge.log(TAG + "hooked commercial scene network blocker ("
                + hookCount + ")");
    }

    private static boolean containsCommercialSceneRecommendation(
            Object request, Object[] methodArgs) {
        if (methodArgs != null) {
            for (Object argument : methodArgs) {
                if (isCommercialSceneRecommendationUrl(argument)) {
                    return true;
                }
            }
        }
        if (request == null) {
            return false;
        }
        String[] fields = {"mUrl", "mPath", "f"};
        for (String field : fields) {
            try {
                if (isCommercialSceneRecommendationUrl(
                        XposedHelpers.getObjectField(request, field))) {
                    return true;
                }
            } catch (Throwable ignored) {
                // Different request implementations expose different backing fields.
            }
        }
        return false;
    }

    private static boolean isCommercialSceneRecommendationUrl(Object value) {
        if (!(value instanceof String)) {
            return false;
        }
        String url = ((String) value).toLowerCase(Locale.ROOT);
        return url.contains("/ws/shield/scene/recommend")
                || url.contains("/ws/mps/scene");
    }

    private static void logOnlineSceneRecommendationBlock() {
        if (!loggedOnlineSceneRecommendBlock) {
            loggedOnlineSceneRecommendBlock = true;
            XposedBridge.log(TAG + "blocked online commercial scene recommendation request");
        }
    }


    private static void hookSplashLifecycle(ClassLoader classLoader) {
        String[] candidates = {
                "com.autonavi.minimap.SplashLifeCycleServiceImpl",
                "com.autonavi.minimap.SplashFrequencyController",
                "com.autonavi.minimap.bundle.splashscreen.impl.SplashScreenServiceImpl"
        };
        for (String name : candidates) {
            Class<?> type = XposedHelpers.findClassIfExists(name, classLoader);
            if (type == null) {
                continue;
            }
            for (Method method : type.getDeclaredMethods()) {
                String methodName = method.getName().toLowerCase(Locale.ROOT);
                if (methodName.contains("canshowsplash")
                        || methodName.contains("shouldshowsplash")
                        || methodName.contains("needshowsplash")) {
                    hookBooleanResult(method, "splash decision");
                }
                if (methodName.equals("showsplash") || methodName.equals("showsplashview")) {
                    hookVoidMethod(method, "splash display");
                }
            }
        }
    }

    private static void hookBooleanResult(Method method, String label) {
        if (method.getReturnType() != boolean.class && method.getReturnType() != Boolean.class) {
            return;
        }
        XposedBridge.hookMethod(method, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                param.setResult(false);
            }
        });
        XposedBridge.log(TAG + "hooked " + label + ": " + method);
    }

    private static void hookVoidMethod(Method method, String label) {
        if (method.getReturnType() != void.class) {
            return;
        }
        XposedBridge.hookMethod(method, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                param.setResult(null);
            }
        });
        XposedBridge.log(TAG + "hooked " + label + ": " + method);
    }

    private static void hookBannerViews(ClassLoader classLoader) {
        XposedHelpers.findAndHookMethod(View.class, "setVisibility", int.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (flags.hideBanner && isBannerView(param.thisObject)) {
                    param.args[0] = View.GONE;
                } else if (flags.hideAiTab && isAiTabView(param.thisObject)) {
                    param.args[0] = View.GONE;
                } else if (flags.hideHomeToday && param.thisObject instanceof View
                        && containsResourceEntry(
                                (View) param.thisObject, "widget_lottie", 0, 6)) {
                    param.args[0] = View.GONE;
                }
            }
        });

        XposedHelpers.findAndHookMethod(View.class, "onAttachedToWindow", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                View view = (View) param.thisObject;
                if (flags.hideBanner && isBannerView(view)) {
                    view.setVisibility(View.GONE);
                } else if (flags.hideAiTab && view instanceof TextView && isAiTabText((TextView) view)) {
                    hideAiTabView((TextView) view);
                } else if (flags.hideHomeToday
                        && "widget_lottie".equals(resourceEntryName(view))) {
                    view.post(() -> hideMapActivityWidgetView(view));
                }
            }
        });

        if (flags.hideAiTab) {
            hookAiTabTextUpdates();
        }

        String[] viewCandidates = {
                "com.autonavi.bundle.banner.view.DBanner",
                "com.autonavi.minimap.banner.BannerView"
        };
        for (String name : viewCandidates) {
            Class<?> type = XposedHelpers.findClassIfExists(name, classLoader);
            if (type != null && View.class.isAssignableFrom(type)) {
                XposedBridge.hookAllConstructors(type, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (flags.hideBanner) {
                            ((View) param.thisObject).setVisibility(View.GONE);
                        }
                    }
                });
            }
        }
    }

    private static void hookAiTabTextUpdates() {
        XposedHelpers.findAndHookMethod(TextView.class, "setText", CharSequence.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (flags.hideAiTab && param.thisObject instanceof TextView) {
                            TextView textView = (TextView) param.thisObject;
                            if (isAiTabText(textView)) {
                                hideAiTabView(textView);
                            }
                        }
                    }
                });
        XposedHelpers.findAndHookMethod(TextView.class, "setText", CharSequence.class,
                TextView.BufferType.class, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (flags.hideAiTab && param.thisObject instanceof TextView) {
                            TextView textView = (TextView) param.thisObject;
                            if (isAiTabText(textView)) {
                                hideAiTabView(textView);
                            }
                        }
                    }
                });
    }

    private static boolean isHomeTodayView(Object object) {
        if (!(object instanceof View)) {
            return false;
        }
        View view = (View) object;
        String resourceName = resourceEntryName(view);
        if (isHomeTodayResource(resourceName)) {
            return true;
        }
        ViewParent parent = view.getParent();
        if (parent instanceof View) {
            return isHomeTodayContainer(resourceEntryName((View) parent));
        }
        return false;
    }

    private static void hideHomeTodayView(View view) {
        View target = view;
        String resourceName = resourceEntryName(view);
        if (!isHomeTodayContainer(resourceName)) {
            ViewParent parent = view.getParent();
            if (parent instanceof View && isHomeTodayContainer(resourceEntryName((View) parent))) {
                target = (View) parent;
            }
        }
        target.setVisibility(View.GONE);
    }

    private static boolean isHomeTodayResource(String resourceName) {
        return "today_container".equals(resourceName)
                || "today_btn".equals(resourceName)
                || "today_recommend".equals(resourceName)
                || "today_recommand".equals(resourceName)
                || "recommend_container".equals(resourceName)
                || "weather_container".equals(resourceName)
                || "weather_restrict_container".equals(resourceName)
                || "weather_temperature".equals(resourceName)
                || "weather_temperature_label".equals(resourceName)
                || "weather_icon".equals(resourceName);
    }

    private static boolean isHomeTodayContainer(String resourceName) {
        return "today_container".equals(resourceName)
                || "today_recommend".equals(resourceName)
                || "today_recommand".equals(resourceName)
                || "recommend_container".equals(resourceName)
                || "weather_container".equals(resourceName)
                || "weather_restrict_container".equals(resourceName);
    }

    private static boolean isAiTabView(Object object) {
        if (!(object instanceof View)) {
            return false;
        }
        View view = (View) object;
        if (view instanceof TextView && isAiTabText((TextView) view)) {
            return true;
        }
        return "tab_bar_item_parent".equals(resourceEntryName(view))
                && containsAiTabText(view, 0, 4);
    }

    private static boolean isAiTabText(TextView textView) {
        CharSequence text = textView.getText();
        if (text == null) {
            return false;
        }
        String value = text.toString().replace(" ", "").replace("　", "");
        return value.contains("长按说话") || value.contains("AI长按对话");
    }

    private static void hideAiTabView(TextView textView) {
        View target = textView;
        ViewParent parent = textView.getParent();
        if (parent instanceof View && "tab_bar_item_parent".equals(resourceEntryName((View) parent))) {
            target = (View) parent;
        }
        target.setVisibility(View.GONE);
    }

    private static void hideMapActivityWidgetView(View widgetChild) {
        View target = widgetChild;
        ViewParent parent = widgetChild.getParent();
        for (int depth = 0; parent instanceof View && depth < 8; depth++) {
            View parentView = (View) parent;
            if ("map_widget_container".equals(resourceEntryName(parentView))) {
                target.setVisibility(View.GONE);
                return;
            }
            target = parentView;
            parent = parentView.getParent();
        }
    }

    private static boolean containsAiTabText(View view, int depth, int maxDepth) {
        if (view instanceof TextView && isAiTabText((TextView) view)) {
            return true;
        }
        if (depth >= maxDepth || !(view instanceof ViewGroup)) {
            return false;
        }
        ViewGroup group = (ViewGroup) view;
        for (int index = 0; index < group.getChildCount(); index++) {
            if (containsAiTabText(group.getChildAt(index), depth + 1, maxDepth)) {
                return true;
            }
        }
        return false;
    }

    private static void hookMinePageTail(ClassLoader classLoader) {
        Class<?> recyclerView = XposedHelpers.findClassIfExists(
                "androidx.recyclerview.widget.RecyclerView", classLoader);
        if (recyclerView == null) {
            XposedBridge.log(TAG + "RecyclerView not found; mine page filter skipped");
            return;
        }
        XposedHelpers.findAndHookMethod(recyclerView, "onLayout", boolean.class,
                int.class, int.class, int.class, int.class, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if ((flags.hideMineTail || flags.hideHomeToday)
                                && param.thisObject instanceof ViewGroup) {
                            ViewGroup recyclerView = (ViewGroup) param.thisObject;
                            if (flags.hideHomeToday && isHomeContentRecyclerView(recyclerView)) {
                                hideHomeTodayContent(recyclerView);
                                hideHomePromoBadges(recyclerView);
                            }
                            if (flags.hideMineTail) {
                                hideMinePageTail(recyclerView);
                            }
                        }
                    }
                });
        XposedBridge.log(TAG + "hooked RecyclerView.onLayout for mine page tail");
    }

    private static void hideMinePageTail(ViewGroup recyclerView) {
        if (recyclerView.getChildCount() < 2 || !isMinePageRecyclerView(recyclerView)) {
            return;
        }

        int tailAdapterPosition = -1;
        for (int index = 0; index < recyclerView.getChildCount(); index++) {
            View child = recyclerView.getChildAt(index);
            if (containsAccessibilityMarker(child, "借钱", 0, 16)) {
                hideBorrowEntry(child);
            }
            if (containsMineTailMarker(child, 0, 16)) {
                tailAdapterPosition = childAdapterPosition(recyclerView, child);
                break;
            }
        }
        if (tailAdapterPosition > 0) {
            limitPageAdapter(recyclerView, tailAdapterPosition, "mine page");
        }
    }

    private static void limitPageAdapter(
            ViewGroup recyclerView, int itemLimit, String pageLabel) {
        Object adapter;
        try {
            adapter = XposedHelpers.callMethod(recyclerView, "getAdapter");
        } catch (Throwable error) {
            XposedBridge.log(TAG + "cannot read mine page adapter: " + error);
            return;
        }
        if (adapter == null) {
            return;
        }

        Method itemCountMethod = findItemCountMethod(adapter.getClass());
        if (itemCountMethod == null) {
            XposedBridge.log(TAG + "getItemCount not found for " + adapter.getClass().getName());
            return;
        }
        if (HOOKED_ITEM_COUNT_METHODS.add(itemCountMethod)) {
            try {
                XposedBridge.hookMethod(itemCountMethod, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Integer limit;
                        synchronized (PAGE_ADAPTER_LIMITS) {
                            limit = PAGE_ADAPTER_LIMITS.get(param.thisObject);
                        }
                        Object result = param.getResult();
                        if (limit != null && result instanceof Integer && (Integer) result > limit) {
                            param.setResult(limit);
                        }
                    }
                });
                XposedBridge.log(TAG + "hooked page adapter item count: " + itemCountMethod);
            } catch (Throwable error) {
                HOOKED_ITEM_COUNT_METHODS.remove(itemCountMethod);
                XposedBridge.log(TAG + "cannot hook page adapter item count: " + error);
                return;
            }
        }

        boolean newlyRegistered;
        synchronized (PAGE_ADAPTER_LIMITS) {
            Integer previousLimit = PAGE_ADAPTER_LIMITS.put(adapter, itemLimit);
            newlyRegistered = previousLimit == null || previousLimit != itemLimit;
        }
        if (!newlyRegistered) {
            return;
        }
        XposedBridge.log(TAG + "limiting " + pageLabel + " adapter "
                + adapter.getClass().getName()
                + " before position " + itemLimit);

        recyclerView.post(() -> {
            try {
                XposedHelpers.callMethod(adapter, "notifyDataSetChanged");
                recyclerView.requestLayout();
            } catch (Throwable error) {
                XposedBridge.log(TAG + "cannot refresh limited mine page adapter: " + error);
            }
        });
    }

    private static Method findItemCountMethod(Class<?> adapterClass) {
        Class<?> type = adapterClass;
        while (type != null) {
            try {
                Method method = type.getDeclaredMethod("getItemCount");
                if (!Modifier.isAbstract(method.getModifiers())) {
                    return method;
                }
            } catch (NoSuchMethodException ignored) {
                // Continue with the nearest superclass implementation.
            }
            type = type.getSuperclass();
        }
        return null;
    }

    private static int childAdapterPosition(ViewGroup recyclerView, View child) {
        try {
            Object result = XposedHelpers.callMethod(recyclerView, "getChildAdapterPosition", child);
            return result instanceof Integer ? (Integer) result : -1;
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private static void hideBorrowEntry(View mineToolsItem) {
        View marker = findAccessibilityMarkerView(mineToolsItem, "借钱", 0, 16);
        if (marker == null) {
            return;
        }

        View target = marker;
        ViewGroup actionRow = null;
        ViewParent parent = marker.getParent();
        while (parent instanceof ViewGroup) {
            ViewGroup parentGroup = (ViewGroup) parent;
            ViewParent grandParent = parentGroup.getParent();
            if (grandParent instanceof ViewGroup
                    && ((ViewGroup) grandParent).getChildCount() >= 5) {
                target = parentGroup;
                actionRow = (ViewGroup) grandParent;
                break;
            }
            target = parentGroup;
            parent = grandParent;
        }
        target.setVisibility(View.GONE);
        if (actionRow != null) {
            redistributeActionRow(actionRow, target);
        }
    }

    private static void redistributeActionRow(ViewGroup actionRow, View hiddenAction) {
        int visibleCount = 0;
        for (int index = 0; index < actionRow.getChildCount(); index++) {
            View child = actionRow.getChildAt(index);
            if (child != hiddenAction && child.getVisibility() != View.GONE) {
                visibleCount++;
            }
        }
        if (visibleCount == 0 || actionRow.getWidth() <= 0) {
            return;
        }

        float slotWidth = actionRow.getWidth() / (float) visibleCount;
        int visibleIndex = 0;
        for (int index = 0; index < actionRow.getChildCount(); index++) {
            View child = actionRow.getChildAt(index);
            if (child == hiddenAction || child.getVisibility() == View.GONE) {
                continue;
            }
            float centeredLeft = slotWidth * visibleIndex
                    + (slotWidth - child.getWidth()) / 2f;
            child.setX(centeredLeft);
            visibleIndex++;
        }
        actionRow.invalidate();
    }

    private static View findAccessibilityMarkerView(
            View view, String marker, int depth, int maxDepth) {
        if (hasAccessibilityMarker(view, marker)) {
            return view;
        }
        if (depth >= maxDepth || !(view instanceof ViewGroup)) {
            return null;
        }
        ViewGroup group = (ViewGroup) view;
        for (int index = 0; index < group.getChildCount(); index++) {
            View result = findAccessibilityMarkerView(
                    group.getChildAt(index), marker, depth + 1, maxDepth);
            if (result != null) {
                return result;
            }
        }
        return null;
    }

    private static View findExactAccessibilityTextView(
            View view, String marker, int depth, int maxDepth) {
        AccessibilityNodeInfo info = null;
        try {
            info = view.createAccessibilityNodeInfo();
            CharSequence text = info.getText();
            if (text != null && marker.equals(text.toString().trim())) {
                return view;
            }
        } catch (Throwable ignored) {
            // Some AJX views expose only a content description on intermediate nodes.
        } finally {
            if (info != null) {
                info.recycle();
            }
        }
        if (depth >= maxDepth || !(view instanceof ViewGroup)) {
            return null;
        }
        ViewGroup group = (ViewGroup) view;
        for (int index = 0; index < group.getChildCount(); index++) {
            View result = findExactAccessibilityTextView(
                    group.getChildAt(index), marker, depth + 1, maxDepth);
            if (result != null) {
                return result;
            }
        }
        return null;
    }

    private static boolean isHomeContentRecyclerView(View view) {
        ViewParent parent = view.getParent();
        int depth = 0;
        while (parent instanceof View && depth++ < 16) {
            if ("home_scroll_view".equals(resourceEntryName((View) parent))) {
                return true;
            }
            parent = parent.getParent();
        }
        return false;
    }

    private static void hideHomeTodayContent(ViewGroup recyclerView) {
        for (int index = 0; index < recyclerView.getChildCount(); index++) {
            View child = recyclerView.getChildAt(index);
            if (!containsAccessibilityMarker(child, "天气", 0, 16)) {
                continue;
            }
            int weatherAdapterPosition = childAdapterPosition(recyclerView, child);
            if (weatherAdapterPosition > 0) {
                limitPageAdapter(recyclerView, weatherAdapterPosition, "home page");
            }
            return;
        }
    }

    private static void hideHomePromoBadges(ViewGroup recyclerView) {
        hideRoutePromoBadge(recyclerView, "行程有保障");
        hideRoutePromoBadge(recyclerView, "行程有优惠");
        replacePromoBadgeWithCleanIcon(recyclerView, "订酒店");
        replacePromoBadgeWithCleanIcon(recyclerView, "超划算");
        View activityLottie = findResourceView(
                recyclerView.getRootView(), "widget_lottie", 0, 36);
        if (activityLottie != null) {
            hideMapActivityWidgetView(activityLottie);
        }
    }

    private static void hideRoutePromoBadge(View root, String marker) {
        View badgeText = findAccessibilityMarkerView(root, marker, 0, 20);
        if (badgeText == null) {
            return;
        }
        View target = badgeText;
        ViewParent parent = badgeText.getParent();
        if (parent instanceof View) {
            target = (View) parent;
        }
        target.setVisibility(View.GONE);
    }

    private static void replacePromoBadgeWithCleanIcon(View root, String hostMarker) {
        View marker = findAccessibilityMarkerView(root, hostMarker, 0, 20);
        View host = resolvePromoBadgeHost(marker);
        if (host == null || host.getWidth() <= 0 || host.getHeight() <= 0) {
            return;
        }
        View image = findViewByClassSuffix(host, ".widget.view.Image", 0, 6);
        if (!(image instanceof ImageView)) {
            return;
        }
        View label = findExactAccessibilityTextView(host, hostMarker, 0, 8);
        Bitmap cleanBitmap = decodeCleanToolIcon(hostMarker);
        if (cleanBitmap == null) {
            return;
        }
        CleanToolIconDrawable cleanIcon;
        synchronized (HOME_CLEAN_TOOL_ICONS) {
            cleanIcon = HOME_CLEAN_TOOL_ICONS.get(host);
            if (cleanIcon == null || cleanIcon.imageView != image) {
                if (cleanIcon != null) {
                    host.getOverlay().remove(cleanIcon);
                }
                cleanIcon = new CleanToolIconDrawable(
                        (ImageView) image, cleanBitmap, "超划算".equals(hostMarker));
                HOME_CLEAN_TOOL_ICONS.put(host, cleanIcon);
                host.getOverlay().add(cleanIcon);
            }
        }
        cleanIcon.updateBounds(host, label);
        host.invalidate();
    }

    private static Bitmap decodeCleanToolIcon(String hostMarker) {
        Bitmap cached = CLEAN_TOOL_BITMAPS.get(hostMarker);
        if (cached != null) {
            return cached;
        }
        String encoded = CleanIconData.forMarker(hostMarker);
        if (encoded == null) {
            return null;
        }
        try {
            byte[] bytes = Base64.decode(encoded, Base64.DEFAULT);
            Bitmap decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
            if (decoded != null) {
                CLEAN_TOOL_BITMAPS.put(hostMarker, decoded);
            }
            return decoded;
        } catch (Throwable error) {
            XposedBridge.log(TAG + "cannot decode clean icon " + hostMarker + ": " + error);
            return null;
        }
    }

    private static View findViewByClassSuffix(
            View view, String classSuffix, int depth, int maxDepth) {
        if (view == null) {
            return null;
        }
        if (view.getClass().getName().endsWith(classSuffix)) {
            return view;
        }
        if (depth >= maxDepth || !(view instanceof ViewGroup)) {
            return null;
        }
        ViewGroup group = (ViewGroup) view;
        for (int index = 0; index < group.getChildCount(); index++) {
            View result = findViewByClassSuffix(
                    group.getChildAt(index), classSuffix, depth + 1, maxDepth);
            if (result != null) {
                return result;
            }
        }
        return null;
    }

    private static View resolvePromoBadgeHost(View marker) {
        if (marker == null) {
            return null;
        }
        View candidate = marker;
        View current = marker;
        for (int depth = 0; depth < 5; depth++) {
            int width = current.getWidth();
            int height = current.getHeight();
            if (width >= 180 && width <= 420 && height >= 150 && height <= 360) {
                candidate = current;
            }
            ViewParent parent = current.getParent();
            if (!(parent instanceof View)) {
                break;
            }
            current = (View) parent;
        }
        return candidate;
    }

    private static View findResourceView(
            View view, String resourceEntry, int depth, int maxDepth) {
        if (resourceEntry.equals(resourceEntryName(view))) {
            return view;
        }
        if (depth >= maxDepth || !(view instanceof ViewGroup)) {
            return null;
        }
        ViewGroup group = (ViewGroup) view;
        for (int index = 0; index < group.getChildCount(); index++) {
            View result = findResourceView(
                    group.getChildAt(index), resourceEntry, depth + 1, maxDepth);
            if (result != null) {
                return result;
            }
        }
        return null;
    }

    private static boolean containsResourceEntry(
            View view, String resourceEntry, int depth, int maxDepth) {
        return findResourceView(view, resourceEntry, depth, maxDepth) != null;
    }

    /** Redraws AMap's clean source icon above AJX's non-accessible promo corner mark. */
    private static final class CleanToolIconDrawable extends Drawable {
        private final ImageView imageView;
        private final Bitmap cleanBitmap;
        private final boolean coverExternalBadge;
        private final Rect iconBounds = new Rect();
        private final Rect backgroundBounds = new Rect();
        private final Paint backgroundPaint = new Paint();
        private final Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);

        private CleanToolIconDrawable(
                ImageView imageView, Bitmap cleanBitmap, boolean coverExternalBadge) {
            this.imageView = imageView;
            this.cleanBitmap = cleanBitmap;
            this.coverExternalBadge = coverExternalBadge;
            backgroundPaint.setColor(0xfff1f1f1);
        }

        private void updateBounds(View host, View label) {
            int[] hostLocation = new int[2];
            int[] imageLocation = new int[2];
            host.getLocationOnScreen(hostLocation);
            imageView.getLocationOnScreen(imageLocation);
            int left = imageLocation[0] - hostLocation[0];
            int top = imageLocation[1] - hostLocation[1];
            int right = left + imageView.getWidth();
            int bottom = top + imageView.getHeight();
            iconBounds.set(left, top, right, bottom);
            int backgroundLeft = left;
            int backgroundTop = top;
            int backgroundRight = right;
            int backgroundBottom = bottom;
            if (coverExternalBadge) {
                int topPadding = Math.round(imageView.getHeight() * 0.22f);
                int rightPadding = Math.round(imageView.getWidth() * 0.52f);
                backgroundTop -= topPadding;
                backgroundRight += rightPadding;
            }

            // AJX sometimes reports an image rectangle that reaches into the text row.
            // Use the exact accessibility text node as a hard boundary so removing a
            // corner badge cannot erase characters in “订酒店” or “超划算”.
            if (label != null) {
                int[] labelLocation = new int[2];
                label.getLocationOnScreen(labelLocation);
                int labelTop = labelLocation[1] - hostLocation[1];
                int safetyMargin = Math.max(2, Math.round(
                        host.getResources().getDisplayMetrics().density * 2f));
                if (labelTop > backgroundTop) {
                    backgroundBottom = Math.min(backgroundBottom, labelTop - safetyMargin);
                }
            }
            backgroundBounds.set(
                    Math.max(0, backgroundLeft),
                    Math.max(0, backgroundTop),
                    Math.min(host.getWidth(), backgroundRight),
                    Math.min(host.getHeight(), backgroundBottom));
            Rect dirtyBounds = new Rect(iconBounds);
            dirtyBounds.union(backgroundBounds);
            setBounds(dirtyBounds);
            invalidateSelf();
        }

        @Override
        public void draw(Canvas canvas) {
            if (getBounds().isEmpty()) {
                return;
            }
            if (!backgroundBounds.isEmpty()) {
                canvas.drawRect(backgroundBounds, backgroundPaint);
            }
            canvas.drawBitmap(cleanBitmap, null, iconBounds, bitmapPaint);
        }

        @Override
        public void setAlpha(int alpha) {
            backgroundPaint.setAlpha(alpha);
            bitmapPaint.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(android.graphics.ColorFilter colorFilter) {
            bitmapPaint.setColorFilter(colorFilter);
        }

        @Override
        @SuppressWarnings("deprecation")
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }


    private static boolean isMinePageRecyclerView(ViewGroup recyclerView) {
        if (isHomeContentRecyclerView(recyclerView)) {
            return false;
        }
        View root = recyclerView.getRootView();
        if (!containsMineTab(root, 0, 32)) {
            return false;
        }
        int mineColor = findTabTextColor(root, "我的", 0, 32);
        int homeColor = findTabTextColor(root, "首页", 0, 32);
        int exploreColor = findTabTextColor(root, "探索", 0, 32);
        int taxiColor = findTabTextColor(root, "打车", 0, 32);
        // AMap does not expose the selected state in its accessibility tree, but the
        // active tab uses a different text color from all three inactive tabs.
        return mineColor != Integer.MIN_VALUE
                && homeColor != Integer.MIN_VALUE
                && exploreColor != Integer.MIN_VALUE
                && taxiColor != Integer.MIN_VALUE
                && mineColor != homeColor
                && mineColor != exploreColor
                && mineColor != taxiColor;
    }

    private static boolean containsMineTab(View view, int depth, int maxDepth) {
        if (view instanceof TextView) {
            CharSequence text = ((TextView) view).getText();
            if (text != null && "我的".contentEquals(text)) {
                return true;
            }
        }
        if (depth >= maxDepth || !(view instanceof ViewGroup)) {
            return false;
        }
        ViewGroup group = (ViewGroup) view;
        for (int index = 0; index < group.getChildCount(); index++) {
            if (containsMineTab(group.getChildAt(index), depth + 1, maxDepth)) {
                return true;
            }
        }
        return false;
    }

    private static int findTabTextColor(View view, String tabName, int depth, int maxDepth) {
        if (view instanceof TextView) {
            TextView textView = (TextView) view;
            if ("tab_name_v2".equals(resourceEntryName(textView))
                    && textView.getText() != null
                    && tabName.contentEquals(textView.getText())) {
                return textView.getCurrentTextColor();
            }
        }
        if (depth >= maxDepth || !(view instanceof ViewGroup)) {
            return Integer.MIN_VALUE;
        }
        ViewGroup group = (ViewGroup) view;
        for (int index = 0; index < group.getChildCount(); index++) {
            int color = findTabTextColor(group.getChildAt(index), tabName, depth + 1, maxDepth);
            if (color != Integer.MIN_VALUE) {
                return color;
            }
        }
        return Integer.MIN_VALUE;
    }

    private static boolean containsMineTailMarker(View view, int depth, int maxDepth) {
        if (hasMineTailMarker(view)) {
            return true;
        }
        if (depth >= maxDepth || !(view instanceof ViewGroup)) {
            return false;
        }
        ViewGroup group = (ViewGroup) view;
        for (int index = 0; index < group.getChildCount(); index++) {
            if (containsMineTailMarker(group.getChildAt(index), depth + 1, maxDepth)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasMineTailMarker(View view) {
        return hasAccessibilityMarker(view, "达人任务")
                || hasAccessibilityMarker(view, "达人权益");
    }

    private static boolean containsAccessibilityMarker(
            View view, String marker, int depth, int maxDepth) {
        if (hasAccessibilityMarker(view, marker)) {
            return true;
        }
        if (depth >= maxDepth || !(view instanceof ViewGroup)) {
            return false;
        }
        ViewGroup group = (ViewGroup) view;
        for (int index = 0; index < group.getChildCount(); index++) {
            if (containsAccessibilityMarker(group.getChildAt(index), marker,
                    depth + 1, maxDepth)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasAccessibilityMarker(View view, String marker) {
        AccessibilityNodeInfo info = null;
        try {
            info = view.createAccessibilityNodeInfo();
            String text = String.valueOf(info.getText());
            String description = String.valueOf(info.getContentDescription());
            return text.contains(marker) || description.contains(marker);
        } catch (Throwable ignored) {
            return false;
        } finally {
            if (info != null) {
                info.recycle();
            }
        }
    }

    private static String resourceEntryName(View view) {
        try {
            int id = view.getId();
            if (id == View.NO_ID) {
                return "";
            }
            return view.getResources().getResourceEntryName(id).toLowerCase(Locale.ROOT);
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static boolean isBannerView(Object object) {
        if (!(object instanceof View)) {
            return false;
        }
        Class<?> type = object.getClass();
        while (type != null) {
            String name = type.getName().toLowerCase(Locale.ROOT);
            if (name.equals("com.autonavi.bundle.banner.view.dbanner")
                    || name.equals("com.autonavi.minimap.banner.bannerview")
                    || name.contains(".banner.view.dbanner")) {
                return true;
            }
            type = type.getSuperclass();
        }
        return false;
    }

    private static void hookBannerManagers(ClassLoader classLoader) {
        String[] managerCandidates = {
                "com.autonavi.bundle.banner.manager.BannerManager",
                "com.autonavi.bundle.banner.manager.BannerService"
        };
        for (String name : managerCandidates) {
            Class<?> type = XposedHelpers.findClassIfExists(name, classLoader);
            if (type == null) {
                continue;
            }
            for (Method method : type.getDeclaredMethods()) {
                String methodName = method.getName().toLowerCase(Locale.ROOT);
                boolean isBannerInit = methodName.startsWith("init") && methodName.endsWith("banner");
                boolean retrievesBanner = methodName.contains("retrievebanner") || methodName.contains("getbannerlist");
                if (!isBannerInit && !retrievesBanner) {
                    continue;
                }
                if (Modifier.isAbstract(method.getModifiers())) {
                    continue;
                }
                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        Class<?> returnType = method.getReturnType();
                        if (returnType == void.class) {
                            param.setResult(null);
                        } else if (returnType == boolean.class || returnType == Boolean.class) {
                            param.setResult(false);
                        } else if (retrievesBanner) {
                            // The caller will treat a missing banner list as no content.
                            param.setResult(null);
                        }
                    }
                });
                XposedBridge.log(TAG + "hooked banner manager: " + method);
            }
        }
    }
}
