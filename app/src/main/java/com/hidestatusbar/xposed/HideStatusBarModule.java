package com.hidestatusbar.xposed;

import android.app.Activity;
import android.graphics.Canvas;
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
            Class<?> statusBarLayout = Class.forName(
                "com.opera.android.StatusBarDrawingFrameLayout", false, cl);

            // Hook onApplyWindowInsets:
            // Skip Opera's FrameLayout.onApplyWindowInsets (which calls setPadding)
            // But still call ViewGroup.dispatchApplyWindowInsets so children get insets
            hook(statusBarLayout.getMethod("onApplyWindowInsets", WindowInsets.class))
                .intercept(chain -> {
                    WindowInsets insets = (WindowInsets) chain.getArg(0);
                    View view = (View) chain.getThisObject();

                    // Remove all padding from this view
                    view.setPadding(0, 0, 0, 0);

                    // Make this view zero-height
                    ViewGroup.LayoutParams lp = view.getLayoutParams();
                    if (lp != null) {
                        lp.height = 0;
                        view.setLayoutParams(lp);
                    }

                    // Still dispatch insets to children so Opera's layout works
                    if (view instanceof ViewGroup) {
                        ViewGroup vg = (ViewGroup) view;
                        for (int i = 0; i < vg.getChildCount(); i++) {
                            vg.getChildAt(i).dispatchApplyWindowInsets(insets);
                        }
                    }

                    return insets;
                });
            log(Log.INFO, TAG, "Hooked onApplyWindowInsets (padding removed, insets forwarded)");

            // Hook draw - skip drawing status bar background
            // Use ThreadLocal to prevent recursion
            final ThreadLocal<Boolean> inDraw = new ThreadLocal<>();
            hook(statusBarLayout.getMethod("draw", Canvas.class))
                .intercept(chain -> {
                    if (inDraw.get() != null) return null;
                    inDraw.set(true);
                    try {
                        Canvas canvas = (Canvas) chain.getArg(0);
                        View view = (View) chain.getThisObject();
                        // Only draw children, not this view's own background
                        if (view instanceof ViewGroup) {
                            ViewGroup vg = (ViewGroup) view;
                            int count = vg.getChildCount();
                            for (int i = 0; i < count; i++) {
                                vg.getChildAt(i).draw(canvas);
                            }
                        }
                    } finally {
                        inDraw.remove();
                    }
                    return null;
                });
            log(Log.INFO, TAG, "Hooked draw (skip status bar rect, draw children only)");

            // Hook onDraw - completely skip
            hook(statusBarLayout.getMethod("onDraw", Canvas.class))
                .intercept(chain -> null);
            log(Log.INFO, TAG, "Hooked onDraw (blocked)");

            // Hook e(I) - runtime color setter, block it
            try {
                hook(statusBarLayout.getDeclaredMethod("e", int.class))
                    .intercept(chain -> null);
                log(Log.INFO, TAG, "Hooked e(I) (blocked)");
            } catch (Exception e) {
                log(Log.WARN, TAG, "e(I) hook skipped: " + e.getMessage());
            }

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
