package com.hidestatusbar.xposed;

import android.app.Activity;
import android.graphics.Color;
import android.os.Build;
import android.util.Log;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
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

            // Hook constructors - force all color/draw fields after Opera initializes them
            for (java.lang.reflect.Constructor<?> ctor : statusBarLayout.getDeclaredConstructors()) {
                Class<?>[] params = ctor.getParameterTypes();
                if (params.length == 3
                        && params[0].getName().equals("android.content.Context")) {
                    hook(ctor).intercept(chain -> {
                        chain.proceed();
                        forceTransparent(statusBarLayout, chain.getThisObject());
                        return null;
                    });
                    log(Log.INFO, TAG, "Hooked constructor (" + params.length + " args)");
                    break;
                }
            }

            // Hook onApplyWindowInsets - prevent setPadding
            hook(statusBarLayout.getMethod("onApplyWindowInsets", WindowInsets.class))
                .intercept(chain -> {
                    return chain.getArg(0);
                });
            log(Log.INFO, TAG, "Hooked onApplyWindowInsets");

            // Hook e(I) - runtime setter for status bar color k
            // Block ALL calls to prevent Opera from setting k back to non-transparent
            try {
                java.lang.reflect.Method eMethod = statusBarLayout.getDeclaredMethod("e", int.class);
                hook(eMethod).intercept(chain -> {
                    log(Log.INFO, TAG, "Blocked e() call, was=" + chain.getArg(0));
                    return null;
                });
                log(Log.INFO, TAG, "Hooked e(I)");
            } catch (Exception e) {
                log(Log.WARN, TAG, "e(I) hook failed: " + e.getMessage());
            }

            // Hook onDraw - skip drawRect but let super run
            // This is the backup: even if fields get reset, onDraw won't draw the rectangle
            hook(statusBarLayout.getMethod("onDraw", android.graphics.Canvas.class))
                .intercept(chain -> {
                    // Call super.onDraw() only (View's version, does nothing harmful)
                    try {
                        java.lang.reflect.Method superOnDraw =
                            View.class.getDeclaredMethod("onDraw", android.graphics.Canvas.class);
                        superOnDraw.invoke(chain.getThisObject(), chain.getArg(0));
                    } catch (Exception ignored) {}
                    return null;
                });
            log(Log.INFO, TAG, "Hooked onDraw");

            // Hook draw - skip the overlay rect at bottom
            hook(statusBarLayout.getMethod("draw", android.graphics.Canvas.class))
                .intercept(chain -> {
                    // Call super.draw() only (View's version)
                    try {
                        java.lang.reflect.Method superDraw =
                            View.class.getDeclaredMethod("draw", android.graphics.Canvas.class);
                        superDraw.invoke(chain.getThisObject(), chain.getArg(0));
                    } catch (Exception ignored) {}
                    return null;
                });
            log(Log.INFO, TAG, "Hooked draw");

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

    private void forceTransparent(Class<?> clazz, Object obj) {
        try {
            // f = false (don't draw status bar background)
            java.lang.reflect.Field fField = clazz.getDeclaredField("f");
            fField.setAccessible(true);
            fField.setBoolean(obj, false);
            log(Log.INFO, TAG, "Set f=false (no status bar draw)");

            // k = TRANSPARENT
            java.lang.reflect.Field kField = clazz.getDeclaredField("k");
            kField.setAccessible(true);
            kField.setInt(obj, Color.TRANSPARENT);
            log(Log.INFO, TAG, "Set k=TRANSPARENT");

            // l = TRANSPARENT
            java.lang.reflect.Field lField = clazz.getDeclaredField("l");
            lField.setAccessible(true);
            lField.setInt(obj, Color.TRANSPARENT);
            log(Log.INFO, TAG, "Set l=TRANSPARENT");

            // i = 0 (status bar height = 0)
            java.lang.reflect.Field iField = clazz.getDeclaredField("i");
            iField.setAccessible(true);
            iField.setInt(obj, 0);
            log(Log.INFO, TAG, "Set i=0 (status bar height)");

        } catch (Exception e) {
            log(Log.WARN, TAG, "forceTransparent failed: " + e.getMessage());
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
