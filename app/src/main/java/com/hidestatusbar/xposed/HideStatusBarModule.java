package com.hidestatusbar.xposed;

import android.app.Activity;
import android.graphics.Color;
import android.os.Build;
import android.util.Log;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import io.github.libxposed.api.XposedModule;

public class HideStatusBarModule extends XposedModule {

    private static final String TAG = "HideStatusBar";

    public HideStatusBarModule() {
        super();
    }

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        log(Log.INFO, TAG, "Module loaded");
    }

    @Override
    public void onPackageLoaded(PackageLoadedParam param) {
        if (!"com.opera.browser.beta".equals(param.getPackageName())) return;

        log(Log.INFO, TAG, "Hooking Opera Beta, pid=" + android.os.Process.myPid());
        ClassLoader cl = param.getDefaultClassLoader();

        try {
            // 核心修复：Hook StatusBarDrawingFrameLayout.onApplyWindowInsets
            // 这个方法每次 insets 变化时都会调用 setPadding(0, statusBarHeight, 0, bottom)
            // 直接让它跳过自定义逻辑，只调用 super
            try {
                Class<?> statusBarLayout = Class.forName(
                    "com.opera.android.StatusBarDrawingFrameLayout", false, cl);
                hook(statusBarLayout.getMethod("onApplyWindowInsets", WindowInsets.class))
                    .intercept(chain -> {
                        log(Log.INFO, TAG, "BLOCKED onApplyWindowInsets on StatusBarDrawingFrameLayout");
                        // 调用 super.onApplyWindowInsets，但跳过 Opera 自己的 setPadding 逻辑
                        // 通过设置 padding 为 0 并返回 insets 来阻止状态栏空间
                        View view = (View) chain.getThisObject();
                        // 不调用原方法，直接返回 insets（跳过所有自定义逻辑）
                        return chain.getArg(0);
                    });
                log(Log.INFO, TAG, "Hooked StatusBarDrawingFrameLayout.onApplyWindowInsets");
            } catch (Exception e) {
                log(Log.WARN, TAG, "StatusBarDrawingFrameLayout hook failed: " + e.getMessage());
            }

            // Hook SuggestionsContainer.onApplyWindowInsets（如果存在）
            try {
                Class<?> suggestionsClass = Class.forName(
                    "com.opera.android.SuggestionsContainer", false, cl);
                hook(suggestionsClass.getMethod("onApplyWindowInsets", WindowInsets.class))
                    .intercept(chain -> {
                        log(Log.INFO, TAG, "BLOCKED onApplyWindowInsets on SuggestionsContainer");
                        return chain.getArg(0);
                    });
                log(Log.INFO, TAG, "Hooked SuggestionsContainer.onApplyWindowInsets");
            } catch (Exception e) {
                // 尝试其他类名
                try {
                    Class<?> suggestionsClass = Class.forName(
                        "com.opera.browser.urlinput.SuggestionsContainer", false, cl);
                    hook(suggestionsClass.getMethod("onApplyWindowInsets", WindowInsets.class))
                        .intercept(chain -> {
                            log(Log.INFO, TAG, "BLOCKED onApplyWindowInsets on SuggestionsContainer");
                            return chain.getArg(0);
                        });
                    log(Log.INFO, TAG, "Hooked SuggestionsContainer.onApplyWindowInsets");
                } catch (Exception e2) {
                    log(Log.WARN, TAG, "SuggestionsContainer hook failed: " + e2.getMessage());
                }
            }

            // 兜底：Hook View.setPadding 拦截任何设置 141px top padding 的调用
            hook(View.class.getMethod("setPadding", int.class, int.class, int.class, int.class))
                .intercept(chain -> {
                    int top = (int) chain.getArg(1);
                    if (top >= 140 && top <= 142) {
                        String name = "unknown";
                        if (chain.getThisObject() instanceof View) {
                            name = ((View) chain.getThisObject()).getClass().getSimpleName();
                        }
                        log(Log.INFO, TAG, "BLOCKED setPadding top=" + top + " on " + name);
                        // 直接设 top=0，不调用原方法
                        ((View) chain.getThisObject()).setPadding(
                            (int) chain.getArg(0), 0,
                            (int) chain.getArg(2), (int) chain.getArg(3));
                        return null;
                    }
                    return chain.proceed();
                });

            Class<?> browserActivity = Class.forName(
                "com.opera.android.BrowserActivity", false, cl);

            // Hook onCreate
            hook(browserActivity.getMethod("onCreate", android.os.Bundle.class))
                .intercept(chain -> {
                    Object result = chain.proceed();
                    if (chain.getThisObject() instanceof Activity) {
                        applyFullScreen((Activity) chain.getThisObject());
                    }
                    return result;
                });

            // Hook onResume
            hook(browserActivity.getMethod("onResume")).intercept(chain -> {
                Object result = chain.proceed();
                if (chain.getThisObject() instanceof Activity) {
                    Activity a = (Activity) chain.getThisObject();
                    applyFullScreen(a);
                    View decor = a.getWindow().getDecorView();
                    decor.postDelayed(() -> applyFullScreen(a), 200);
                    decor.postDelayed(() -> applyFullScreen(a), 600);
                }
                return result;
            });

            // Hook onWindowFocusChanged
            hook(browserActivity.getMethod("onWindowFocusChanged", boolean.class))
                .intercept(chain -> {
                    Object result = chain.proceed();
                    if ((boolean) chain.getArg(0)
                            && chain.getThisObject() instanceof Activity) {
                        applyFullScreen((Activity) chain.getThisObject());
                    }
                    return result;
                });

            log(Log.INFO, TAG, "All hooks installed");
        } catch (Exception e) {
            log(Log.ERROR, TAG, "Hook failed: " + e.getMessage());
        }
    }

    private void applyFullScreen(Activity activity) {
        if (activity == null || activity.isFinishing()) return;
        Window window = activity.getWindow();
        if (window == null) return;

        try {
            window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
            window.setStatusBarColor(Color.TRANSPARENT);
            window.setNavigationBarColor(Color.TRANSPARENT);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                window.setDecorFitsSystemWindows(false);
                WindowInsetsController ctrl = window.getInsetsController();
                if (ctrl != null) {
                    ctrl.hide(WindowInsets.Type.statusBars()
                            | WindowInsets.Type.navigationBars());
                    ctrl.setSystemBarsBehavior(
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                }
            } else {
                window.getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
            }

            log(Log.INFO, TAG, "fullScreen done");
        } catch (Exception e) {
            log(Log.ERROR, TAG, "Error: " + e.getMessage());
        }
    }
}
