package ricassiocosta.me.valv5;

import android.content.ComponentCallbacks2;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;

import com.bumptech.glide.Glide;

import ricassiocosta.me.valv5.security.SecureLog;


import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.documentfile.provider.DocumentFile;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.NavController;
import androidx.navigation.Navigation;
import androidx.navigation.fragment.NavHostFragment;
import androidx.navigation.ui.AppBarConfiguration;
import androidx.navigation.ui.NavigationUI;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import ricassiocosta.me.valv5.data.Password;
import ricassiocosta.me.valv5.databinding.ActivityMainBinding;
import ricassiocosta.me.valv5.security.SecureMemoryManager;
import ricassiocosta.me.valv5.utils.FileStuff;
import ricassiocosta.me.valv5.utils.Settings;
import ricassiocosta.me.valv5.viewmodel.ShareViewModel;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";

    private AppBarConfiguration appBarConfiguration;
    private ActivityMainBinding binding;
    
    
    // Background lock timer - stores timestamp when app went to background
    private long backgroundTimestamp = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        EdgeToEdge.enable(this);
        super.onCreate(savedInstanceState);
        // Screen-off receiver is managed by application-level lifecycle (App.java)
        // to avoid lifecycle ordering issues. Do not register here.
        Settings settings = Settings.getInstance(this);
        if (settings.isSecureFlag()) {
            getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE);
        }

        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        setSupportActionBar(binding.toolbar);

        NavHostFragment navHostFragment = (NavHostFragment) getSupportFragmentManager().findFragmentById(R.id.nav_host_fragment_content_main);
        assert navHostFragment != null;
        NavController navController = navHostFragment.getNavController();
        appBarConfiguration = new AppBarConfiguration.Builder(navController.getGraph()).build();
        NavigationUI.setupActionBarWithNavController(this, navController, appBarConfiguration);

        navController.addOnDestinationChangedListener((controller, destination, arguments) -> {
            if (destination.getId() == R.id.password) {
                binding.appBar.setVisibility(View.GONE);
            } else {
                binding.appBar.setVisibility(View.VISIBLE);
            }
        });

        Intent intent = getIntent();
        String action = intent.getAction();
        String type = intent.getType();

        if (type != null && Intent.ACTION_SEND.equals(action)) {
            if (type.startsWith("image/") || type.startsWith("video/")) {
                handleSendSingle(intent);
            }
        } else if (type != null && Intent.ACTION_SEND_MULTIPLE.equals(action)) {
            if (type.startsWith("image/") || type.startsWith("video/") || type.equals("*/*")) {
                handleSendMultiple(intent);
            }
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        checkBackgroundLockTimeout();
    }

    @Override
    protected void onStop() {
        super.onStop();
        startBackgroundLockTimer();
    }

    private void handleSendSingle(@NonNull Intent intent) {
        Uri uri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
        if (uri != null) {
            List<Uri> list = new ArrayList<>(1);
            list.add(uri);
            List<DocumentFile> documentFiles = FileStuff.getDocumentsFromShareIntent(this, list);
            if (!documentFiles.isEmpty()) {
                addSharedFiles(documentFiles);
            }
        }
    }

    private void handleSendMultiple(@NonNull Intent intent) {
        ArrayList<Uri> uris = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
        if (uris != null) {
            List<DocumentFile> documentFiles = FileStuff.getDocumentsFromShareIntent(this, uris);
            if (!documentFiles.isEmpty()) {
                addSharedFiles(documentFiles);
            }
        }
    }

    private void addSharedFiles(@NonNull List<DocumentFile> documentFiles) {
        SecureLog.d(TAG, "addSharedFiles: " + SecureLog.safeCount(documentFiles.size(), "files"));
        ShareViewModel shareViewModel = new ViewModelProvider(this).get(ShareViewModel.class);
        shareViewModel.clear();
        shareViewModel.getFilesReceived().addAll(documentFiles);
        shareViewModel.setHasData(true);
    }

    @Override
    public boolean onSupportNavigateUp() {
        NavController navController = Navigation.findNavController(this, R.id.nav_host_fragment_content_main);
        return NavigationUI.navigateUp(navController, appBarConfiguration) || super.onSupportNavigateUp();
    }

    @Override
    protected void onDestroy() {
        // Receiver is managed at application level (App.java). Do not unregister here.
        if (!isChangingConfigurations()) {
            // Perform full memory cleanup when app is being destroyed
            Password.lock(this, false);
            SecureMemoryManager.getInstance().performFullCleanup(this);
        }
        super.onDestroy();
    }
    
    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        
        // Note: TRIM_MEMORY_UI_HIDDEN is called when app goes to background
        // To reduce risk, wipe non-essential sensitive data here while preserving session key/password.
        // The autolock feature (checkBackgroundLockTimeout) still handles locking after timeout.
        // SECURITY WARNING: Sensitive data may remain in memory while the app is backgrounded,
        // depending on the autolock timeout. If autolock is disabled or set to a long timeout,
        // sensitive data may be exposed in memory. Users should be warned of this risk in settings.
        if (level == ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
            SecureLog.d(TAG, "App backgrounded, performing partial sensitive data cleanup");
            // This should wipe non-essential sensitive data (e.g., decrypted caches, temp buffers)
            // but preserve session key/password for quick resume.
            SecureMemoryManager.getInstance().performPartialCleanup(this); // Implement this method as needed
        }
        // Only perform full cleanup when system is critically low on memory
        if (level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE) {
            SecureLog.d(TAG, "Critical memory pressure, performing full cleanup");
            SecureMemoryManager.getInstance().performFullCleanup(this);
        } else if (level >= ComponentCallbacks2.TRIM_MEMORY_MODERATE) {
            // Moderate memory pressure - clear only non-essential caches
            SecureLog.d(TAG, "Moderate memory pressure, clearing Glide cache only");
            try {
                Glide.get(this).clearMemory();
            } catch (Exception e) {
                SecureLog.w(TAG, "Failed to clear Glide memory cache", e);
            }
        }
    }

    // Screen-off receiver lifecycle is managed by `App.java`.

    /**
     * Start the background lock timer when the app goes to background.
     * This stores the timestamp when the app went to background.
     * Does NOT affect screen off lock which is handled separately.
     */
    private void startBackgroundLockTimer() {
        Settings settings = Settings.getInstance(this);
        int timeoutSeconds = settings.getBackgroundLockTimeout();
        
        // If timeout is 0 (disabled), don't start the timer
        if (timeoutSeconds == 0) {
            SecureLog.d(TAG, "Background lock timer is disabled");
            backgroundTimestamp = 0;
            return;
        }
        
        // Check if vault is already locked (on password screen)
        NavHostFragment navHostFragment = (NavHostFragment) getSupportFragmentManager().findFragmentById(R.id.nav_host_fragment_content_main);
        if (navHostFragment != null) {
            NavController navController = navHostFragment.getNavController();
            if (navController.getCurrentDestination() != null && 
                navController.getCurrentDestination().getId() == R.id.password) {
                SecureLog.d(TAG, "Vault already locked, not starting background timer");
                backgroundTimestamp = 0;
                return;
            }
        }
        
        // Store the timestamp when app went to background
        backgroundTimestamp = System.currentTimeMillis();
        SecureLog.d(TAG, "Background lock timer started: " + timeoutSeconds + " seconds, timestamp: " + backgroundTimestamp);
    }

    /**
     * Check if the background lock timeout has expired when returning to foreground.
     * If expired, lock the vault immediately.
     */
    private void checkBackgroundLockTimeout() {
        if (backgroundTimestamp == 0) {
            SecureLog.d(TAG, "No background timestamp, skipping lock check");
            return;
        }
        
        Settings settings = Settings.getInstance(this);
        int timeoutSeconds = settings.getBackgroundLockTimeout();
        
        // If timeout is 0 (disabled), clear timestamp and return
        if (timeoutSeconds == 0) {
            backgroundTimestamp = 0;
            return;
        }
        
        long currentTime = System.currentTimeMillis();
        long elapsedMillis = currentTime - backgroundTimestamp;
        long timeoutMillis = timeoutSeconds * 1000L;
        
        SecureLog.d(TAG, "Background lock check - elapsed: " + elapsedMillis + "ms, timeout: " + timeoutMillis + "ms");
        
        // Clear the timestamp
        backgroundTimestamp = 0;
        
        if (elapsedMillis >= timeoutMillis) {
            SecureLog.d(TAG, "Background lock timeout expired, locking vault");
            performBackgroundLock();
        } else {
            SecureLog.d(TAG, "Background lock timeout not expired yet");
        }
    }

    /**
     * Perform the background lock - locks the vault and clears memory cache.
     * This is called when the background timer expires.
     */
    private void performBackgroundLock() {
        NavHostFragment navHostFragment = (NavHostFragment) getSupportFragmentManager().findFragmentById(R.id.nav_host_fragment_content_main);
        if (navHostFragment == null) {
            return;
        }
        
        NavController navController = navHostFragment.getNavController();
        if (navController.getCurrentDestination() != null && 
            navController.getCurrentDestination().getId() != R.id.password) {
            
            // Lock the vault (same as screen off lock)
            Password.lock(this, false);
            
            // Restart the activity to ensure a completely clean state
            // This avoids the ghost session issue where old navigation state persists
            Intent intent = new Intent(this, MainActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(intent);
            finish();
        }
    }

    private boolean isSystemApp(String packageName) {
        // Lista de apps do sistema que não devemos abrir
        return packageName.equals("com.android.systemui") ||
               packageName.equals("android") ||
               packageName.startsWith("com.android.") ||
               packageName.startsWith("com.google.android.") ||
               packageName.contains("launcher");
    }
    
    private void returnToLastApp() {
        Settings settings = Settings.getInstance(this);
        
        // Primeiro tenta usar o app preferido configurado pelo usuário
        String preferredApp = settings.getPreferredApp();
        if (preferredApp != null && !preferredApp.isEmpty()) {
            if (tryOpenApp(preferredApp)) {
                return;
            }
        }
        
        // Se não há app preferido ou falhou, tenta usar o último app salvo
        String lastAppPackage = settings.getLastAppPackage();
        if (lastAppPackage != null && !lastAppPackage.isEmpty()) {
            if (tryOpenApp(lastAppPackage)) {
                return;
            }
        }
        
        // Se não conseguir abrir nenhum app, volta para a home screen
        openHomeScreen();
    }
    
    private boolean tryOpenApp(String packageName) {
        try {
            Intent launchIntent = getPackageManager().getLaunchIntentForPackage(packageName);
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                startActivity(launchIntent);
                return true;
            }
        } catch (Exception e) {
            SecureLog.e(TAG, "Error opening app", e);
        }
        return false;
    }
    
    private void openHomeScreen() {
        Intent homeIntent = new Intent(Intent.ACTION_MAIN);
        homeIntent.addCategory(Intent.CATEGORY_HOME);
        homeIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(homeIntent);
    }

    /**
     * Centralized handler invoked by application-level receiver (App.java) when an
     * activity instance is available. This method is package-private to allow access
     * from App.java while avoiding public API exposure.
     * 
     * Note: This creates an intentional coupling between App and MainActivity for
     * screen-off handling. The coupling is necessary because the receiver is registered
     * at the Application level (to avoid lifecycle timing issues), but the lock behavior
     * requires access to the NavController which is owned by MainActivity.
     */
    void handleScreenOffFromReceiver() {
        NavHostFragment navHostFragment = (NavHostFragment) getSupportFragmentManager().findFragmentById(R.id.nav_host_fragment_content_main);
        if (navHostFragment == null) {
            SecureLog.w(TAG, "NavHostFragment missing when handling screen-off");
            return;
        }
        NavController navController = navHostFragment.getNavController();
        if (navController.getCurrentDestination() != null && navController.getCurrentDestination().getId() != R.id.password) {
            Settings settings = Settings.getInstance(this);
            if (settings.returnToLastApp()) {
                SecureLog.d(TAG, "Auto-lock triggered - using user configured preferred app");
            }

            // Invalidate session and wipe sensitive memory
            Password.lock(this, false);

            // Restart the activity in a clean state so the navigation will
            // land on the password screen (see performBackgroundLock).
            Intent intent = new Intent(this, MainActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(intent);

            if (settings.returnToLastApp()) {
                // Try to return to preferred/last app. Use a delayed handler to ensure
                // the new activity has time to start before we finish this one.
                // The delay also helps avoid race conditions with FLAG_ACTIVITY_CLEAR_TASK.
                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                    try {
                        returnToLastApp();
                        finish();
                    } catch (Exception e) {
                        SecureLog.w(TAG, "Error finishing activity after returnToLastApp", e);
                    }
                }, 300);
            } else {
                // Finish current activity instance (new one started will show password)
                try {
                    finish();
                } catch (Exception e) {
                    SecureLog.w(TAG, "Error finishing activity on screen off", e);
                }
            }
        }
    }

    
}