package ricassiocosta.me.valv5;

import android.app.Activity;
import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;

import ricassiocosta.me.valv5.security.SecureLog;
import ricassiocosta.me.valv5.data.Password;
import ricassiocosta.me.valv5.utils.Settings;

import java.lang.ref.WeakReference;

public class App extends Application {
    private static final String TAG = "App";

    private volatile WeakReference<Activity> currentActivityRef = new WeakReference<>(null);
    private BroadcastReceiver screenOffReceiver;

    @Override
    public void onCreate() {
        super.onCreate();

        // Track current activity to allow the receiver to delegate to the
        // visible activity when available (for NavController / finish()).
        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            @Override
            public void onActivityCreated(Activity activity, android.os.Bundle bundle) {}

            @Override
            public void onActivityStarted(Activity activity) {}

            @Override
            public void onActivityResumed(Activity activity) {
                currentActivityRef = new WeakReference<>(activity);
            }

            @Override
            public void onActivityPaused(Activity activity) {
                // Note: We keep the reference until onActivityStopped to ensure the activity
                // can still handle screen-off events during the paused state.
            }

            @Override
            public void onActivityStopped(Activity activity) {
                Activity curr = currentActivityRef.get();
                if (curr == activity) {
                    currentActivityRef = new WeakReference<>(null);
                }
            }

            @Override
            public void onActivitySaveInstanceState(Activity activity, android.os.Bundle bundle) {}

            @Override
            public void onActivityDestroyed(Activity activity) {}
        });

        // Register an application-scoped receiver to avoid missing ACTION_SCREEN_OFF
        // due to activity lifecycle ordering. The receiver uses currentActivityRef
        // to interact with the activity when present and falls back to context-only
        // behavior otherwise.
        IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        screenOffReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                    SecureLog.d(TAG, "App-level ScreenOffReceiver: ACTION_SCREEN_OFF received");
                    Activity activity = currentActivityRef.get();
                    if (activity instanceof MainActivity) {
                        try {
                            ((MainActivity) activity).handleScreenOffFromReceiver();
                        } catch (Exception e) {
                            SecureLog.w(TAG, "Error delegating screen-off to MainActivity", e);
                        }
                    } else {
                        // No activity instance available - do minimal safe cleanup
                        // Note: Password.lock is safe to call even if already locked
                        try {
                            Settings settings = Settings.getInstance(context);
                            
                            // Perform lock - safe to call even if already locked
                            Password.lock(context, false);

                            // Only attempt to open apps if returnToLastApp is enabled
                            // This fallback path is used when no MainActivity instance is available

                            if (settings.returnToLastApp()) {
                                String preferredApp = settings.getPreferredApp();
                                if (preferredApp != null && !preferredApp.isEmpty()) {
                                    if (tryOpenApp(context, preferredApp)) return;
                                }
                                String lastApp = settings.getLastAppPackage();
                                if (lastApp != null && !lastApp.isEmpty()) {
                                    if (tryOpenApp(context, lastApp)) return;
                                }
                                openHomeScreen(context);
                            } else {
                                openHomeScreen(context);
                            }
                        } catch (Exception e) {
                            SecureLog.w(TAG, "Error handling screen-off without activity", e);
                        }
                    }
                }
            }
        };

        try {
            getApplicationContext().registerReceiver(screenOffReceiver, filter);
            SecureLog.d(TAG, "Registered app-level screenOffReceiver");
        } catch (SecurityException | IllegalArgumentException e) {
            SecureLog.w(TAG, "Failed to register app-level screenOffReceiver", e);
        }
    }

    /**
     * Note: onTerminate() is called only in emulated environments and will never be
     * called on a real Android device. The broadcast receiver registered in onCreate()
     * will remain registered for the lifetime of the process in production. This is
     * acceptable behavior as the receiver is needed for the app's security model.
     */
    @Override
    public void onTerminate() {
        try {
            if (screenOffReceiver != null) {
                unregisterReceiver(screenOffReceiver);
                screenOffReceiver = null;
            }
        } catch (IllegalArgumentException e) {
            SecureLog.w(TAG, "screenOffReceiver already unregistered", e);
        }
        super.onTerminate();
    }

    private static boolean tryOpenApp(Context context, String packageName) {
        try {
            PackageManager pm = context.getPackageManager();
            Intent launchIntent = pm.getLaunchIntentForPackage(packageName);
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                context.startActivity(launchIntent);
                return true;
            }
        } catch (Exception e) {
            SecureLog.w(TAG, "Error opening app: " + packageName, e);
        }
        return false;
    }

    private static void openHomeScreen(Context context) {
        try {
            Intent homeIntent = new Intent(Intent.ACTION_MAIN);
            homeIntent.addCategory(Intent.CATEGORY_HOME);
            homeIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(homeIntent);
        } catch (Exception e) {
            SecureLog.w(TAG, "Failed to open home screen", e);
        }
    }
}
