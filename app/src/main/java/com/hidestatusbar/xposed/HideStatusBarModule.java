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
            Class<?> statusBarLayout = Class.forName(
                "com.opera.android.StatusBarDrawingFrameLayout", false, cl);

            // Hook 构造函数 - 在 Opera 读取 XML 属性后，强制将背景色设为透明
            // 字段 k = status bar 背景色, 字段 l = 另一个颜色
            hook(statusBarLayout.getDeclaredConstructors()[1])  // 3参构造
                .intercept(chain -> {
                    chain.proceed(); // 先执行原构造函数
                    Object obj = chain.getThisObject();
                    try {
                        // 强制将背景色设为透明
                        java.lang.reflect.Field fieldK = statusBarLayout.getDeclaredField("k");
                        fieldK.setAccessible(true);
                        fieldK.setInt(obj, Color.TRANSPARENT);

                        java.lang.reflect.Field fieldL = statusBarLayout.getDeclaredField("l");
                        fieldL.setAccessible(true);
                        fieldL.setInt(obj, Color.TRANSPARENT);

                        log(Log.INFO, TAG, "Forced StatusBarDrawingFrameLayout colors to TRANSPARENT");
                    } catch (Exception e) {
                        log(Log.WARN, TAG, "Failed to set colors: " + e.getMessage());
                    }
                    return null;
                });

            // Hook onApplyWindowInsets - 阻止 setPadding
            hook(statusBarLayout.getMethod("onApplyWindowInsets", WindowInsets.class))
                .intercept(chain -> {
                    return chain.getArg(0);
                });

            log(Log.INFO, TAG, "All StatusBarDrawingFrameLayout hooks installed");

            Class<?> browserActivity = Class.forName(
                "com.opera.android.BrowserActivity", false, cl);

            hook(browserActivity.getMethod("onCreate", android.os.Bundle.class))
                .intercept(chain -> {
                    Object result = chain.proceed();
                    if (chain.getThisObject() instanceof Activity) {
                        applyFullScreen((Activity) chain.getThisObject());
                    }
                    return result;
                });

            hook(browserActivity.getMethod("onResume")).intercept(chain -> {
                Object result = chain.proceed();
                if (chain.getThisObject() instanceof Activity) {
                    Activity a = (Activity) chain.getThisObject();
                    applyFullScreen(a);
                    View decor = a.getWindow().getDecorView();
                    decor.postDelayed(() -> applyFullScreen(a), 300);
                    decor.postDelayed(() -> applyFullScreen(a), 800);
                }
                return result;
            });

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
            }
        } catch (Exception e) {
            log(Log.ERROR, TAG, "Error: " + e.getMessage());
        }
    }
}
