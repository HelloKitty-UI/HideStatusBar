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
        if (!"com.opera.browser.beta".equals(param.getPackageName())) {
            return;
        }

        log(Log.INFO, TAG, "Hooking Opera Beta, pid=" + android.os.Process.myPid());

        ClassLoader cl = param.getDefaultClassLoader();

        try {
            Class<?> browserActivity = Class.forName(
                "com.opera.android.BrowserActivity", false, cl);

            // Hook onCreate
            hook(browserActivity.getMethod("onCreate", android.os.Bundle.class))
                .intercept(chain -> {
                    log(Log.INFO, TAG, ">>> onCreate START, pid=" + android.os.Process.myPid());
                    Object result = chain.proceed();
                    if (chain.getThisObject() instanceof Activity) {
                        applyFullScreen((Activity) chain.getThisObject());
                    }
                    log(Log.INFO, TAG, "<<< onCreate END");
                    return result;
                });

            // Hook onResume
            hook(browserActivity.getMethod("onResume")).intercept(chain -> {
                log(Log.INFO, TAG, ">>> onResume START");
                Object result = chain.proceed();
                if (chain.getThisObject() instanceof Activity) {
                    Activity a = (Activity) chain.getThisObject();
                    applyFullScreen(a);
                    View decor = a.getWindow().getDecorView();
                    decor.postDelayed(() -> {
                        log(Log.INFO, TAG, ">>> delayed 100ms");
                        applyFullScreen(a);
                    }, 100);
                    decor.postDelayed(() -> {
                        log(Log.INFO, TAG, ">>> delayed 500ms");
                        applyFullScreen(a);
                    }, 500);
                    decor.postDelayed(() -> {
                        log(Log.INFO, TAG, ">>> delayed 1000ms");
                        applyFullScreen(a);
                    }, 1000);
                }
                log(Log.INFO, TAG, "<<< onResume END");
                return result;
            });

            // Hook onWindowFocusChanged
            hook(browserActivity.getMethod("onWindowFocusChanged", boolean.class))
                .intercept(chain -> {
                    Object result = chain.proceed();
                    boolean hasFocus = (boolean) chain.getArg(0);
                    log(Log.INFO, TAG, "onWindowFocusChanged hasFocus=" + hasFocus);
                    if (hasFocus && chain.getThisObject() instanceof Activity) {
                        applyFullScreen((Activity) chain.getThisObject());
                    }
                    return result;
                });

            // Hook x0o.e()
            try {
                Class<?> x0oClass = Class.forName("x0o", false, cl);
                for (java.lang.reflect.Method m : x0oClass.getDeclaredMethods()) {
                    if (m.getName().equals("e") && m.getParameterCount() == 0) {
                        hook(m).intercept(chain -> {
                            log(Log.INFO, TAG, "BLOCKED x0o.e() call");
                            return null;
                        });
                        log(Log.INFO, TAG, "Hooked x0o.e()");
                    }
                }
            } catch (Exception e) {
                log(Log.WARN, TAG, "Could not hook x0o: " + e.getMessage());
            }

            // Hook x0o.f(I) - 阻止设置系统UI标志
            try {
                Class<?> x0oClass = Class.forName("x0o", false, cl);
                for (java.lang.reflect.Method m : x0oClass.getDeclaredMethods()) {
                    if (m.getName().equals("f") && m.getParameterCount() == 1) {
                        hook(m).intercept(chain -> {
                            log(Log.INFO, TAG, "BLOCKED x0o.f() call, arg=" + chain.getArg(0));
                            return null;
                        });
                        log(Log.INFO, TAG, "Hooked x0o.f()");
                    }
                }
            } catch (Exception e) {
                log(Log.WARN, TAG, "Could not hook x0o.f: " + e.getMessage());
            }

            // Hook setSystemUiVisibility - 监控谁在设置
            try {
                hook(View.class.getMethod("setSystemUiVisibility", int.class))
                    .intercept(chain -> {
                        int vis = (int) chain.getArg(0);
                        String caller = Thread.currentThread().getStackTrace()[4].toString();
                        log(Log.INFO, TAG, "setSystemUiVisibility: 0x" + Integer.toHexString(vis)
                            + " caller=" + caller);
                        return chain.proceed();
                    });
            } catch (Exception e) {
                log(Log.WARN, TAG, "Could not hook setSystemUiVisibility: " + e.getMessage());
            }

            log(Log.INFO, TAG, "All hooks installed, pid=" + android.os.Process.myPid());
        } catch (Exception e) {
            log(Log.ERROR, TAG, "Hook failed: " + e.getMessage());
        }
    }

    private void applyFullScreen(Activity activity) {
        if (activity == null || activity.isFinishing()) return;
        Window window = activity.getWindow();
        if (window == null) return;

        try {
            // 获取当前状态用于日志
            int statusBarHeight = 0;
            int resourceId = activity.getResources().getIdentifier("status_bar_height", "dimen", "android");
            if (resourceId > 0) {
                statusBarHeight = activity.getResources().getDimensionPixelSize(resourceId);
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                WindowInsets insets = window.getDecorView().getRootWindowInsets();
                int statusHeight = insets != null
                    ? insets.getInsets(WindowInsets.Type.statusBars()).top : -1;

                log(Log.INFO, TAG, "applyFullScreen: statusBarHeight=" + statusBarHeight
                    + " actualInsetsTop=" + statusHeight
                    + " decorFitsSystemWindows=" + window.getDecorFitsSystemWindows());

                // Android 11+ 核心设置
                window.setDecorFitsSystemWindows(false);

                // 设置透明状态栏
                window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
                window.setStatusBarColor(Color.TRANSPARENT);
                window.setNavigationBarColor(Color.TRANSPARENT);

                WindowInsetsController ctrl = window.getInsetsController();
                if (ctrl != null) {
                    ctrl.hide(WindowInsets.Type.statusBars()
                            | WindowInsets.Type.navigationBars());
                    ctrl.setSystemBarsBehavior(
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                    log(Log.INFO, TAG, "WindowInsetsController.hide() called");
                }
            } else {
                View decorView = window.getDecorView();
                decorView.setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
            }

            // 强制遍历视图树，关闭所有 fitsSystemWindows
            disableFitsSystemWindows(window.getDecorView(), 0);

            log(Log.INFO, TAG, "applyFullScreen done");
        } catch (Exception e) {
            log(Log.ERROR, TAG, "Error: " + e.getMessage());
        }
    }

    private void disableFitsSystemWindows(View view, int depth) {
        if (depth > 20) return; // 防止过深递归
        try {
            if (view.getFitsSystemWindows()) {
                log(Log.INFO, TAG, "Disabling fitsSystemWindows on: "
                    + view.getClass().getSimpleName()
                    + " id=" + view.getId()
                    + " at depth=" + depth);
                view.setFitsSystemWindows(false);
            }
        } catch (Exception e) {
            // 忽略
        }
        if (view instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) view;
            for (int i = 0; i < vg.getChildCount(); i++) {
                disableFitsSystemWindows(vg.getChildAt(i), depth + 1);
            }
        }
    }
}
