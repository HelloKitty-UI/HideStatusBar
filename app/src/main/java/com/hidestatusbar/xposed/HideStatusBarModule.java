package com.hidestatusbar.xposed;

import android.app.Activity;
import android.os.Build;
import android.util.Log;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import io.github.libxposed.api.XposedModule;

public class HideStatusBarModule extends XposedModule {

    private static final String TAG = "HideStatusBar";

    public HideStatusBarModule() {
        super();
    }

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        log(Log.INFO, TAG, "Module loaded successfully");
    }

    @Override
    public void onPackageLoaded(PackageLoadedParam param) {
        String packageName = param.getPackageName();
        if (!"com.opera.browser.beta".equals(packageName)) {
            return;
        }

        log(Log.INFO, TAG, "Detected Opera Beta, hooking BrowserActivity");

        ClassLoader classLoader = param.getDefaultClassLoader();

        try {
            Class<?> activityClass = Class.forName(
                "com.opera.android.BrowserActivity", false, classLoader);

            java.lang.reflect.Method onResume = activityClass.getMethod("onResume");
            hook(onResume).intercept(chain -> {
                Object obj = chain.getThisObject();
                if (obj instanceof Activity) {
                    hideStatusBar((Activity) obj);
                }
                Object result = chain.proceed();
                if (obj instanceof Activity) {
                    Activity activity = (Activity) obj;
                    hideStatusBar(activity);
                    activity.getWindow().getDecorView().postDelayed(
                        () -> hideStatusBar(activity), 300);
                }
                return result;
            });

            java.lang.reflect.Method onFocusChanged = activityClass.getMethod(
                "onWindowFocusChanged", boolean.class);
            hook(onFocusChanged).intercept(chain -> {
                boolean hasFocus = (boolean) chain.getArg(0);
                Object result = chain.proceed();
                if (hasFocus && chain.getThisObject() instanceof Activity) {
                    hideStatusBar((Activity) chain.getThisObject());
                }
                return result;
            });

            log(Log.INFO, TAG, "All hooks installed successfully");
        } catch (Exception e) {
            log(Log.ERROR, TAG, "Failed to install hooks: " + e.getMessage());
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
                    ctrl.hide(WindowInsets.Type.statusBars());
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
            log(Log.ERROR, TAG, "Error hiding status bar: " + e.getMessage());
        }
    }
}
