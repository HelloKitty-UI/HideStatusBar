package com.hidestatusbar.xposed;

import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
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
            // === 核心：让 StatusBarDrawingFrameLayout 完全透明 ===

            // 1. Hook onApplyWindowInsets - 跳过 Opera 的 setPadding 逻辑
            //    但调用 super 确保 insets 正确传播
            try {
                Class<?> statusBarLayout = Class.forName(
                    "com.opera.android.StatusBarDrawingFrameLayout", false, cl);

                hook(statusBarLayout.getMethod("onApplyWindowInsets", WindowInsets.class))
                    .intercept(chain -> {
                        // 调用 View.onApplyWindowInsets（跳过 FrameLayout 的实现）
                        // 这样 insets 能正确传播，但不会设置 padding
                        try {
                            java.lang.reflect.Method superMethod = View.class.getMethod(
                                "onApplyWindowInsets", WindowInsets.class);
                            return superMethod.invoke(chain.getThisObject(), chain.getArg(0));
                        } catch (Exception e) {
                            return chain.getArg(0);
                        }
                    });
                log(Log.INFO, TAG, "Hooked onApplyWindowInsets");
            } catch (Exception e) {
                log(Log.WARN, TAG, "onApplyWindowInsets hook failed: " + e.getMessage());
            }

            // 2. Hook draw() - 让状态栏背景绘制完全透明
            //    不阻止绘制本身（避免 flicker），而是让 Paint 颜色透明
            try {
                Class<?> statusBarLayout = Class.forName(
                    "com.opera.android.StatusBarDrawingFrameLayout", false, cl);

                hook(statusBarLayout.getMethod("draw", Canvas.class))
                    .intercept(chain -> {
                        // 获取 Paint 字段并设为透明
                        View view = (View) chain.getThisObject();
                        try {
                            java.lang.reflect.Field paintField = statusBarLayout.getDeclaredField("c");
                            paintField.setAccessible(true);
                            Paint paint = (Paint) paintField.get(view);
                            if (paint != null) {
                                paint.setColor(Color.TRANSPARENT);
                            }
                        } catch (Exception e) {
                            // 忽略
                        }
                        // 执行原方法（此时 Paint 是透明的，所以画出来是透明的）
                        return chain.proceed();
                    });
                log(Log.INFO, TAG, "Hooked draw()");
            } catch (Exception e) {
                log(Log.WARN, TAG, "draw() hook failed: " + e.getMessage());
            }

            // 3. Hook onDraw() - 同样让 Paint 透明
            try {
                Class<?> statusBarLayout = Class.forName(
                    "com.opera.android.StatusBarDrawingFrameLayout", false, cl);

                hook(statusBarLayout.getMethod("onDraw", Canvas.class))
                    .intercept(chain -> {
                        View view = (View) chain.getThisObject();
                        try {
                            java.lang.reflect.Field paintField = statusBarLayout.getDeclaredField("c");
                            paintField.setAccessible(true);
                            Paint paint = (Paint) paintField.get(view);
                            if (paint != null) {
                                paint.setColor(Color.TRANSPARENT);
                            }
                        } catch (Exception e) {
                            // 忽略
                        }
                        return chain.proceed();
                    });
                log(Log.INFO, TAG, "Hooked onDraw()");
            } catch (Exception e) {
                log(Log.WARN, TAG, "onDraw() hook failed: " + e.getMessage());
            }

            // === 设置 Activity 全屏 ===

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
                WindowInsetsController ctrl = window.getInsetsController();
                if (ctrl != null) {
                    // 不要永久隐藏状态栏！
                    // 只设置透明色和 DecorFitsSystemWindows(false)
                    // 让 Opera 自己的滚动机制控制显示/隐藏
                }
            } else {
                window.getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
            }
        } catch (Exception e) {
            log(Log.ERROR, TAG, "Error: " + e.getMessage());
        }
    }
}
