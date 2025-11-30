package se.arctosoft.vault;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;


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

import se.arctosoft.vault.data.Password;
import se.arctosoft.vault.databinding.ActivityMainBinding;
import se.arctosoft.vault.utils.FileStuff;
import se.arctosoft.vault.utils.Settings;
import se.arctosoft.vault.viewmodel.ShareViewModel;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";
    public static long GLIDE_KEY = System.currentTimeMillis();

    private AppBarConfiguration appBarConfiguration;
    private ActivityMainBinding binding;
    private BroadcastReceiver screenOffReceiver;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        EdgeToEdge.enable(this);
        super.onCreate(savedInstanceState);
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
        installScreenOffReceiver();
    }

    @Override
    protected void onStop() {
        super.onStop();
        uninstallScreenOffReceiver();
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
        Log.e(TAG, "addSharedFiles: " + documentFiles.size());
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
        Log.d(TAG, "onDestroy: " + isChangingConfigurations());
        if (!isChangingConfigurations()) {
            Password.lock(this, false);
        }
        super.onDestroy();
    }

    private void installScreenOffReceiver() {
        if (screenOffReceiver == null) {
            screenOffReceiver = new ScreenOffReceiver();
            IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_SCREEN_OFF);
            registerReceiver(screenOffReceiver, filter);
        }
    }

    private void uninstallScreenOffReceiver() {
        if (screenOffReceiver != null) {
            unregisterReceiver(screenOffReceiver);
            screenOffReceiver = null;
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
                Log.d(TAG, "Opened app: " + packageName);
                return true;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error opening app: " + packageName, e);
        }
        return false;
    }
    
    private void openHomeScreen() {
        Intent homeIntent = new Intent(Intent.ACTION_MAIN);
        homeIntent.addCategory(Intent.CATEGORY_HOME);
        homeIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(homeIntent);
        Log.d(TAG, "Opened home screen");
    }

    public class ScreenOffReceiver extends BroadcastReceiver {
        private static final String TAG = "ScreenOffReceiver";

        @Override
        public void onReceive(Context context, Intent intent) {
            if (Objects.equals(intent.getAction(), Intent.ACTION_SCREEN_OFF)) {
                Log.d(TAG, "onReceive: ACTION_SCREEN_OFF");
                NavHostFragment navHostFragment = (NavHostFragment) getSupportFragmentManager().findFragmentById(R.id.nav_host_fragment_content_main);
                assert navHostFragment != null;
                NavController navController = navHostFragment.getNavController();
                if (navController.getCurrentDestination() != null && navController.getCurrentDestination().getId() != R.id.password) {
                    Settings settings = Settings.getInstance(MainActivity.this);
                    
                    // Se a funcionalidade de retornar ao último app estiver habilitada, salva o app atual
                    if (settings.returnToLastApp()) {
                        saveLastOpenedAppBeforeLock();
                    }
                    
                    Password.lock(MainActivity.this, false);
                    
                    // Se a funcionalidade de retornar ao último app estiver habilitada
                    if (settings.returnToLastApp()) {
                        // Executa o retorno ao último app após um pequeno delay para garantir que o lock seja processado
                        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                            returnToLastApp();
                        }, 200);
                    }
                    
                    finish();
                }
            }
        }
        
        private void saveLastOpenedAppBeforeLock() {
            // Como agora o usuário configura manualmente o app preferido,
            // este método pode ser simplificado ou removido.
            // Mantemos apenas para compatibilidade futura.
            Log.d(TAG, "Auto-lock triggered - using user configured preferred app");
        }
        
        private boolean isValidApp(String packageName) {
            try {
                PackageManager pm = getPackageManager();
                Intent launchIntent = pm.getLaunchIntentForPackage(packageName);
                return launchIntent != null;
            } catch (Exception e) {
                return false;
            }
        }
    }
}