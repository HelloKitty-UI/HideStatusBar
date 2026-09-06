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
                        // 不执行原方法（跳过 setPadding(0, 141, 0, bottom)）
                        // 但我们仍然需要调用 super.onApplyWindowInsets
                        // 通过反射调用父类的 onApplyWindowInsets
                        try {
                            java.lang.reflect.Method superMethod = View.class.getMethod(
                                "onApplyWindowInsets", WindowInsets.class);
                            return superMethod.invoke(chain.getThisObject(), chain.getArg(0));
                        } catch (Exception e) {
                            return chain.getArg(0);
                        }
                    });
                log(Log.INFO, TAG, "Hooked StatusBarDrawingFrameLayout.onApplyWindowInsets");
            } catch (Exception e) {
                log(Log.WARN, TAG, "StatusBarDrawingFrameLayout hook failed: " + e.getMessage());
            }

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
                    // 多次延迟重试，确保覆盖 Opera 的初始化
                    decor.postDelayed(() -> applyFullScreen(a), 200);
                    decor.postDelayed(() -> applyFullScreen(a), 500);
                    decor.postDelayed(() -> applyFullScreen(a), 1000);
                    decor.postDelayed(() -> {
                        // 最终强制清除所有 padding
                        clearAllPadding(decor);
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

            // 强制禁用 DecorView 及所有子 View 的 fitsSystemWindows
            View decorView = window.getDecorView();
            decorView.setFitsSystemWindows(false);

            // 清除 DecorView padding
            clearPadding(decorView);

            // 强制请求重新布局
            decorView.requestLayout();

            log(Log.INFO, TAG, "fullScreen done, DecorView padding="
                + decorView.getPaddingTop());
        } catch (Exception e) {
            log(Log.ERROR, TAG, "Error: " + e.getMessage());
        }
    }

    private void clearPadding(View view) {
        if (view == null) return;
        try {
            int top = view.getPaddingTop();
            if (top > 0) {
                String name = view.getClass().getSimpleName();
                log(Log.INFO, TAG, "Clearing padding top=" + top + " on " + name);
                view.setPadding(view.getPaddingLeft(), 0,
                    view.getPaddingRight(), view.getPaddingBottom());
            }
        } catch (Exception e) {
            // 忽略
        }
    }

    private void clearAllPadding(View view) {
        if (view == null) return;
        clearPadding(view);
        if (view instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) view;
            for (int i = 0; i < vg.getChildCount(); i++) {
                clearAllPadding(vg.getChildAt(i));
            }
        }
    }
}
