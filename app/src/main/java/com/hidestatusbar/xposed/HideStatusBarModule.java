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
        if (!"com.opera.browser.beta".equals(param.getPackageName())) {
            return;
        }

        log(Log.INFO, TAG, "Hooking Opera Beta");

        ClassLoader cl = param.getDefaultClassLoader();

        try {
            Class<?> browserActivity = Class.forName(
                "com.opera.android.BrowserActivity", false, cl);

            // Hook onCreate - 最早设置窗口属性
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
                    // 多次延迟确保生效
                    View decor = a.getWindow().getDecorView();
                    decor.postDelayed(() -> applyFullScreen(a), 100);
                    decor.postDelayed(() -> applyFullScreen(a), 500);
                    decor.postDelayed(() -> applyFullScreen(a), 1000);
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

            // Hook x0o.e() - 阻止 Opera 恢复状态栏
            try {
                Class<?> x0oClass = Class.forName("x0o", false, cl);
                for (java.lang.reflect.Method m : x0oClass.getDeclaredMethods()) {
                    if (m.getName().equals("e") && m.getParameterCount() == 0) {
                        hook(m).intercept(chain -> {
                            // 不执行 Opera 的状态栏设置
                            log(Log.INFO, TAG, "Blocked x0o.e()");
                            return null;
                        });
                        log(Log.INFO, TAG, "Hooked x0o.e()");
                    }
                }
            } catch (Exception e) {
                log(Log.WARN, TAG, "Could not hook x0o: " + e.getMessage());
            }

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
            // 设置透明状态栏
            window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
            window.setStatusBarColor(Color.TRANSPARENT);
            window.setNavigationBarColor(Color.TRANSPARENT);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // Android 11+ : 让 DecorView 不为系统栏留空间
                window.setDecorFitsSystemWindows(false);
                WindowInsetsController ctrl = window.getInsetsController();
                if (ctrl != null) {
                    ctrl.hide(WindowInsets.Type.statusBars()
                            | WindowInsets.Type.navigationBars());
                    ctrl.setSystemBarsBehavior(
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                }
            } else {
                // Android 10 及以下
                View decorView = window.getDecorView();
                decorView.setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
            }

            // 确保根布局不为状态栏留空间
            View rootView = window.getDecorView().findViewById(android.R.id.content);
            if (rootView != null) {
                rootView.setFitsSystemWindows(false);
            }
        } catch (Exception e) {
            log(Log.ERROR, TAG, "Error: " + e.getMessage());
        }
    }
}
