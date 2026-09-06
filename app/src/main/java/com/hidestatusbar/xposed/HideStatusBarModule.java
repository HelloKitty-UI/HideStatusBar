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
    private static volatile boolean inSetPadding = false;

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
            // 直接 Hook StatusBarDrawingFrameLayout - 拦截所有 padding 设置
            try {
                Class<?> statusBarLayout = Class.forName(
                    "com.opera.browser.urlinput.AddressBarCoordinator$StatusBarDrawingFrameLayout",
                    false, cl);
                hook(statusBarLayout.getMethod("setPadding", int.class, int.class, int.class, int.class))
                    .intercept(chain -> {
                        log(Log.INFO, TAG, "BLOCKED StatusBarDrawingFrameLayout.setPadding");
                        // 不执行原方法，直接设 top=0
                        ((View) chain.getThisObject()).setPadding(
                            (int) chain.getArg(0), 0,
                            (int) chain.getArg(2), (int) chain.getArg(3));
                        return null;
                    });
                log(Log.INFO, TAG, "Hooked StatusBarDrawingFrameLayout.setPadding");
            } catch (Exception e) {
                log(Log.WARN, TAG, "StatusBarDrawingFrameLayout hook failed: " + e.getMessage());
                // 尝试其他类名
                try {
                    Class<?> statusBarLayout = Class.forName("x0o$a", false, cl);
                    hook(statusBarLayout.getMethod("setPadding", int.class, int.class, int.class, int.class))
                        .intercept(chain -> {
                            log(Log.INFO, TAG, "BLOCKED x0a.setPadding");
                            ((View) chain.getThisObject()).setPadding(
                                (int) chain.getArg(0), 0,
                                (int) chain.getArg(2), (int) chain.getArg(3));
                            return null;
                        });
                } catch (Exception e2) {
                    log(Log.WARN, TAG, "x0o$a hook also failed: " + e2.getMessage());
                }
            }

            // Hook SuggestionsContainer.setPadding
            try {
                Class<?> suggestionsClass = Class.forName(
                    "com.opera.browser.urlinput.SuggestionsContainer",
                    false, cl);
                hook(suggestionsClass.getMethod("setPadding", int.class, int.class, int.class, int.class))
                    .intercept(chain -> {
                        log(Log.INFO, TAG, "BLOCKED SuggestionsContainer.setPadding");
                        ((View) chain.getThisObject()).setPadding(
                            (int) chain.getArg(0), 0,
                            (int) chain.getArg(2), (int) chain.getArg(3));
                        return null;
                    });
                log(Log.INFO, TAG, "Hooked SuggestionsContainer.setPadding");
            } catch (Exception e) {
                log(Log.WARN, TAG, "SuggestionsContainer hook failed: " + e.getMessage());
            }

            // Hook View.setPadding 作为兜底 - 只拦截设置等于状态栏高度的 top padding
            hook(View.class.getMethod("setPadding", int.class, int.class, int.class, int.class))
                .intercept(chain -> {
                    if (inSetPadding) return chain.proceed();
                    int top = (int) chain.getArg(1);
                    // 拦截任何被设置为 141px 的 top padding
                    if (top >= 140 && top <= 142) {
                        inSetPadding = true;
                        ((View) chain.getThisObject()).setPadding(
                            (int) chain.getArg(0), 0,
                            (int) chain.getArg(2), (int) chain.getArg(3));
                        inSetPadding = false;
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
