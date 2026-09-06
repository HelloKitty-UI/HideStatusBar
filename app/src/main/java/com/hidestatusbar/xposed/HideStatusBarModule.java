package com.hidestatusbar.xposed;

import android.app.Activity;
import android.os.Build;
import android.view.View;
import android.view.Window;
import android.view.WindowInsetsController;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.callbacks.MethodHookCallback;
import io.github.libxposed.api.types.MethodHooker;
import java.lang.reflect.Method;

public class HideStatusBarModule extends XposedModule {

    @Override
    public void onModuleLoaded() {
        log("HideStatusBar: Module loaded successfully");
    }

    @Override
    public void onPackageLoaded(PackageLoadedParam param) {
        String packageName = param.getPackageName();
        if (!"com.opera.browser.beta".equals(packageName)) {
            return;
        }

        log("HideStatusBar: Detected Opera Beta, hooking BrowserActivity");

        ClassLoader classLoader = param.getDefaultClassLoader();

        try {
            Class<?> activityClass = Class.forName(
                "com.opera.android.BrowserActivity", false, classLoader);

            // Hook onResume
            Method onResume = activityClass.getMethod("onResume");
            hookMethod(onResume, new MethodHookCallback() {
                @Override
                public void before(MethodHookParam param) {
                    Object obj = param.getThisObject();
                    if (obj instanceof Activity) {
                        hideStatusBar((Activity) obj);
                    }
                }

                @Override
                public void after(MethodHookParam param) {
                    Object obj = param.getThisObject();
                    if (obj instanceof Activity) {
                        Activity activity = (Activity) obj;
                        hideStatusBar(activity);
                        activity.getWindow().getDecorView().postDelayed(
                            () -> hideStatusBar(activity), 300);
                    }
                }
            });

            // Hook onWindowFocusChanged
            Method onFocusChanged = activityClass.getMethod(
                "onWindowFocusChanged", boolean.class);
            hookMethod(onFocusChanged, new MethodHookCallback() {
                @Override
                public void after(MethodHookParam param) {
                    boolean hasFocus = (boolean) param.args[0];
                    if (hasFocus && param.getThisObject() instanceof Activity) {
                        hideStatusBar((Activity) param.getThisObject());
                    }
                }
            });

            log("HideStatusBar: All hooks installed successfully");
        } catch (Exception e) {
            log("HideStatusBar: Failed to install hooks: " + e.getMessage());
        }
    }

    private void hideStatusBar(Activity activity) {
        if (activity == null || activity.isFinishing()) {
            return;
        }
        Window window = activity.getWindow();
        if (window == null) {
            return;
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                WindowInsetsController ctrl = window.getInsetsController();
                if (ctrl != null) {
                    ctrl.hide(WindowInsetsController.Type.statusBars());
                    ctrl.setSystemBarsBehavior(
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                }
            } else {
                View decorView = window.getDecorView();
                int flags = decorView.getSystemUiVisibility();
                flags |= View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN;
                decorView.setSystemUiVisibility(flags);
            }
        } catch (Exception e) {
            log("HideStatusBar: Error hiding status bar: " + e.getMessage());
        }
    }
}
