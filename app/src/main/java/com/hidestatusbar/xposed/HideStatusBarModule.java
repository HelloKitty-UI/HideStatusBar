package com.hidestatusbar.xposed;

import android.app.Activity;
import android.graphics.Color;
import android.os.Build;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
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
            // Hook StatusBarDrawingFrameLayout.onApplyWindowInsets
            try {
                Class<?> statusBarLayout = Class.forName(
                    "com.opera.android.StatusBarDrawingFrameLayout", false, cl);
                hook(statusBarLayout.getMethod("onApplyWindowInsets", WindowInsets.class))
                    .intercept(chain -> {
                        log(Log.INFO, TAG, "BLOCKED onApplyWindowInsets on StatusBarDrawingFrameLayout");
                        return chain.getArg(0);
                    });
                log(Log.INFO, TAG, "Hooked StatusBarDrawingFrameLayout.onApplyWindowInsets");
            } catch (Exception e) {
                log(Log.WARN, TAG, "StatusBarDrawingFrameLayout hook failed: " + e.getMessage());
            }

            // Hook View.setPadding - 拦截所有设置 141px top padding 的调用
            hook(View.class.getMethod("setPadding", int.class, int.class, int.class, int.class))
                .intercept(chain -> {
                    int top = (int) chain.getArg(1);
                    if (top >= 140 && top <= 142) {
                        String name = "unknown";
                        int viewId = -1;
                        if (chain.getThisObject() instanceof View) {
                            View v = (View) chain.getThisObject();
                            name = v.getClass().getSimpleName();
                            viewId = v.getId();
                        }
                        // 获取调用栈
                        StackTraceElement[] stack = Thread.currentThread().getStackTrace();
                        String caller = "unknown";
                        for (StackTraceElement s : stack) {
                            if (!s.getClassName().contains("HideStatusBar")
                                    && !s.getClassName().contains("Xposed")
                                    && !s.getClassName().contains("java.lang")) {
                                caller = s.getClassName() + "." + s.getMethodName()
                                    + ":" + s.getLineNumber();
                                break;
                            }
                        }
                        log(Log.INFO, TAG, "BLOCKED setPadding top=" + top
                            + " view=" + name + " id=" + viewId + " caller=" + caller);
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
                    decor.postDelayed(() -> {
                        // 延迟后强制遍历视图树，清除所有 padding
                        clearAllPadding(a.getWindow().getDecorView());
                    }, 300);
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

            // 强制禁用 DecorView 的 fitsSystemWindows
            View decorView = window.getDecorView();
            decorView.setFitsSystemWindows(false);

            // 清除 DecorView padding
            if (decorView.getPaddingTop() > 0) {
                log(Log.INFO, TAG, "Clearing DecorView padding top=" + decorView.getPaddingTop());
                decorView.setPadding(decorView.getPaddingLeft(), 0,
                    decorView.getPaddingRight(), decorView.getPaddingBottom());
            }

            log(Log.INFO, TAG, "fullScreen done");
        } catch (Exception e) {
            log(Log.ERROR, TAG, "Error: " + e.getMessage());
        }
    }

    private void clearAllPadding(View view) {
        if (view == null) return;
        try {
            // 清除任何有 top padding 的 View
            int top = view.getPaddingTop();
            if (top > 0) {
                String name = view.getClass().getSimpleName();
                int viewId = view.getId();
                log(Log.INFO, TAG, "Clearing padding top=" + top
                    + " view=" + name + " id=" + viewId);
                view.setPadding(view.getPaddingLeft(), 0,
                    view.getPaddingRight(), view.getPaddingBottom());
            }
        } catch (Exception e) {
            // 忽略
        }
        if (view instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) view;
            for (int i = 0; i < vg.getChildCount(); i++) {
                clearAllPadding(vg.getChildAt(i));
            }
        }
    }
}
