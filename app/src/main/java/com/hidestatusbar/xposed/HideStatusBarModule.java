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

            // Hook onApplyWindowInsets - prevent setPadding(0, statusBarHeight, 0, bottom)
            hook(statusBarLayout.getMethod("onApplyWindowInsets", WindowInsets.class))
                .intercept(chain -> {
                    return chain.getArg(0);
                });
            log(Log.INFO, TAG, "Hooked onApplyWindowInsets");

            // Hook onDraw - completely skip (no super call to avoid recursion)
            hook(statusBarLayout.getMethod("onDraw", android.graphics.Canvas.class))
                .intercept(chain -> null);
            log(Log.INFO, TAG, "Hooked onDraw (blocked)");

            // Hook draw - completely skip (no super call to avoid recursion)
            hook(statusBarLayout.getMethod("draw", android.graphics.Canvas.class))
                .intercept(chain -> null);
            log(Log.INFO, TAG, "Hooked draw (blocked)");

            // Hide the StatusBarDrawingFrameLayout view completely
            // Do this every time the view is laid out
            try {
                java.lang.reflect.Method addOnLayoutChangeListener =
                    View.class.getMethod("addOnLayoutChangeListener", View.OnLayoutChangeListener.class);
                // Can't use lambda directly, use a post approach instead
            } catch (Exception ignored) {}

            Class<?> browserActivity = Class.forName(
                "com.opera.android.BrowserActivity", false, cl);

            // Hook onCreate - set full screen + hide status bar
            hook(browserActivity.getMethod("onCreate", android.os.Bundle.class))
                .intercept(chain -> {
                    Object result = chain.proceed();
                    if (chain.getThisObject() instanceof Activity) {
                        applyFullScreen((Activity) chain.getThisObject());
                    }
                    return result;
                });

            // Hook onResume - repeatedly force full screen and hide status bar view
            hook(browserActivity.getMethod("onResume")).intercept(chain -> {
                Object result = chain.proceed();
                if (chain.getThisObject() instanceof Activity) {
                    Activity a = (Activity) chain.getThisObject();
                    applyFullScreen(a);

                    // Repeatedly hide the status bar view with delays
                    // Opera may recreate/show it at various times
                    View decor = a.getWindow().getDecorView();
                    for (int delay : new int[]{0, 200, 500, 1000, 2000}) {
                        decor.postDelayed(() -> {
                            applyFullScreen(a);
                            hideStatusBarView(a);
                        }, delay);
                    }
                }
                return result;
            });

            // Hook onWindowFocusChanged - re-apply when window gets focus
            hook(browserActivity.getMethod("onWindowFocusChanged", boolean.class))
                .intercept(chain -> {
                    Object result = chain.proceed();
                    if ((boolean) chain.getArg(0)
                            && chain.getThisObject() instanceof Activity) {
                        Activity a = (Activity) chain.getThisObject();
                        applyFullScreen(a);
                        hideStatusBarView(a);
                    }
                    return result;
                });

            log(Log.INFO, TAG, "All hooks installed");
        } catch (Exception e) {
            log(Log.ERROR, TAG, "Hook failed: " + e.getMessage());
        }
    }

    private void hideStatusBarView(Activity activity) {
        try {
            View decor = activity.getWindow().getDecorView();
            // Find and hide the StatusBarDrawingFrameLayout recursively
            hideViewRecursive(decor);
        } catch (Exception e) {
            log(Log.WARN, TAG, "hideStatusBarView error: " + e.getMessage());
        }
    }

    private void hideViewRecursive(View view) {
        if (view == null) return;
        String name = view.getClass().getName();
        if (name.contains("StatusBarDrawingFrameLayout")) {
            view.setVisibility(View.GONE);
            view.setPadding(0, 0, 0, 0);
            log(Log.INFO, TAG, "Hid StatusBarDrawingFrameLayout");
            return;
        }
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup vg = (android.view.ViewGroup) view;
            for (int i = 0; i < vg.getChildCount(); i++) {
                hideViewRecursive(vg.getChildAt(i));
            }
        }
    }

    private void applyFullScreen(Activity activity) {
        if (activity == null || activity.isFinishing()) return;
        Window window = activity.getWindow();
        if (window == null) return;

        try {
            // Make status bar transparent
            window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
            window.setStatusBarColor(Color.TRANSPARENT);
            window.setNavigationBarColor(Color.TRANSPARENT);

            // Let content draw behind system bars
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                window.setDecorFitsSystemWindows(false);
            }

            // Hide status bar via system UI
            WindowInsetsController ctrl = window.getInsetsController();
            if (ctrl != null) {
                ctrl.hide(WindowInsets.Type.statusBars());
                ctrl.setSystemBarsBehavior(
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } catch (Exception e) {
            log(Log.ERROR, TAG, "Error: " + e.getMessage());
        }
    }
}
