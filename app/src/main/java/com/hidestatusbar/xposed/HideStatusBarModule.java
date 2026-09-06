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
import android.widget.FrameLayout;
import io.github.libxposed.api.XposedModule;

public class HideStatusBarModule extends XposedModule {

    private static final String TAG = "HideStatusBar";
    private int statusBarHeight = 0;

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

        // 获取状态栏高度
        try {
            int resId = param.getApplicationInfo().targetContext.getResources()
                .getIdentifier("status_bar_height", "dimen", "android");
            if (resId > 0) {
                statusBarHeight = param.getApplicationInfo().targetContext.getResources()
                    .getDimensionPixelSize(resId);
            }
        } catch (Exception e) {
            statusBarHeight = 141; // fallback from logs
        }
        log(Log.INFO, TAG, "statusBarHeight=" + statusBarHeight);

        try {
            Class<?> browserActivity = Class.forName(
                "com.opera.android.BrowserActivity", false, cl);

            // Hook onCreate
            hook(browserActivity.getMethod("onCreate", android.os.Bundle.class))
                .intercept(chain -> {
                    Object result = chain.proceed();
                    if (chain.getThisObject() instanceof Activity) {
                        fullScreen((Activity) chain.getThisObject());
                    }
                    return result;
                });

            // Hook onResume
            hook(browserActivity.getMethod("onResume")).intercept(chain -> {
                Object result = chain.proceed();
                if (chain.getThisObject() instanceof Activity) {
                    Activity a = (Activity) chain.getThisObject();
                    fullScreen(a);
                    View decor = a.getWindow().getDecorView();
                    decor.postDelayed(() -> fullScreen(a), 200);
                    decor.postDelayed(() -> fullScreen(a), 600);
                }
                return result;
            });

            // Hook onWindowFocusChanged
            hook(browserActivity.getMethod("onWindowFocusChanged", boolean.class))
                .intercept(chain -> {
                    Object result = chain.proceed();
                    if ((boolean) chain.getArg(0)
                            && chain.getThisObject() instanceof Activity) {
                        fullScreen((Activity) chain.getThisObject());
                    }
                    return result;
                });

            // Hook View.setPadding - 阻止设置状态栏相关的 padding
            hook(View.class.getMethod("setPadding", int.class, int.class, int.class, int.class))
                .intercept(chain -> {
                    int top = (int) chain.getArg(1);
                    // 如果某个 View 被设置了等于状态栏高度的 top padding，强制改为 0
                    if (top == statusBarHeight || top == statusBarHeight + 1
                            || top == statusBarHeight - 1) {
                        String name = "unknown";
                        if (chain.getThisObject() instanceof View) {
                            name = ((View) chain.getThisObject()).getClass().getSimpleName();
                        }
                        log(Log.INFO, TAG, "BLOCKED setPadding top=" + top + " on " + name);
                        chain.proceed(); // 先执行原方法
                        // 然后把 top padding 改为 0
                        ((View) chain.getThisObject()).setPadding(
                            (int) chain.getArg(0), 0,
                            (int) chain.getArg(2), (int) chain.getArg(3));
                        return null;
                    }
                    return chain.proceed();
                });

            log(Log.INFO, TAG, "All hooks installed");
        } catch (Exception e) {
            log(Log.ERROR, TAG, "Hook failed: " + e.getMessage());
        }
    }

    private void fullScreen(Activity activity) {
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

            // 强制清除所有 padding
            clearAllPadding(window.getDecorView());

            log(Log.INFO, TAG, "fullScreen done");
        } catch (Exception e) {
            log(Log.ERROR, TAG, "Error: " + e.getMessage());
        }
    }

    private void clearAllPadding(View view) {
        // 清除 padding 为 0（如果之前被设置为状态栏高度）
        int top = view.getPaddingTop();
        if (top > 0 && statusBarHeight > 0
                && Math.abs(top - statusBarHeight) <= 2) {
            log(Log.INFO, TAG, "Clearing padding top=" + top
                + " on " + view.getClass().getSimpleName());
            view.setPadding(view.getPaddingLeft(), 0,
                view.getPaddingRight(), view.getPaddingBottom());
        }
        if (view instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) view;
            for (int i = 0; i < vg.getChildCount(); i++) {
                clearAllPadding(vg.getChildAt(i));
            }
        }
    }
}
