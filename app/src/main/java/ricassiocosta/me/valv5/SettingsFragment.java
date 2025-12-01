package ricassiocosta.me.valv5;

import static androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.WindowManager;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;
import androidx.core.view.MenuProvider;
import androidx.fragment.app.FragmentActivity;
import androidx.navigation.NavController;
import androidx.navigation.fragment.NavHostFragment;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.SwitchPreferenceCompat;

import java.io.IOException;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertificateException;
import java.util.concurrent.Executor;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.SecretKey;

import ricassiocosta.me.valv5.data.Password;
import ricassiocosta.me.valv5.encryption.Encryption;
import ricassiocosta.me.valv5.utils.Dialogs;
import ricassiocosta.me.valv5.utils.Settings;
import ricassiocosta.me.valv5.utils.Toaster;

public class SettingsFragment extends PreferenceFragmentCompat implements MenuProvider {
    private static final String TAG = "SettingsFragment";

    private BiometricPrompt biometricPrompt;
    private BiometricPrompt.PromptInfo promptInfo;

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        setPreferencesFromResource(R.xml.root_preferences, rootKey);

        Preference iterationCount = findPreference(Settings.PREF_ENCRYPTION_ITERATION_COUNT);
        Preference editFolders = findPreference(Settings.PREF_APP_EDIT_FOLDERS);
        SwitchPreferenceCompat biometrics = findPreference(Settings.PREF_APP_BIOMETRICS);
        SwitchPreferenceCompat secure = findPreference(Settings.PREF_APP_SECURE);
        SwitchPreferenceCompat deleteByDefault = findPreference(Settings.PREF_ENCRYPTION_DELETE_BY_DEFAULT);

        SwitchPreferenceCompat exitOnLock = findPreference(Settings.PREF_APP_EXIT_ON_LOCK);
        SwitchPreferenceCompat returnToLastApp = findPreference(Settings.PREF_APP_RETURN_TO_LAST_APP);
        Preference preferredApp = findPreference(Settings.PREF_APP_PREFERRED_APP);

        FragmentActivity activity = requireActivity();
        Settings settings = Settings.getInstance(activity);

        Executor executor = ContextCompat.getMainExecutor(activity);
        biometricPrompt = new BiometricPrompt(activity, executor, new BiometricPrompt.AuthenticationCallback() {
            @Override
            public void onAuthenticationError(int errorCode, @NonNull CharSequence errString) {
                super.onAuthenticationError(errorCode, errString);
                Log.e(TAG, "onAuthenticationError: " + errorCode + ", " + errString);
                biometrics.setChecked(false);
            }

            @Override
            public void onAuthenticationSucceeded(@NonNull BiometricPrompt.AuthenticationResult result) {
                super.onAuthenticationSucceeded(result);
                Log.e(TAG, "onAuthenticationSucceeded: " + result);
                BiometricPrompt.CryptoObject cryptoObject = result.getCryptoObject();
                if (cryptoObject != null) {
                    try {
                        Cipher cipher = cryptoObject.getCipher();
                        byte[] iv = cipher.getIV();
                        byte[] encryptedInfo = cipher.doFinal(Encryption.toBytes(Password.getInstance().getPassword()));

                        settings.setBiometricsEnabled(iv, encryptedInfo);
                        Toaster.getInstance(activity).showLong(getString(R.string.settings_biometrics_enabled));
                    } catch (BadPaddingException | IllegalBlockSizeException e) {
                        e.printStackTrace();
                        Toaster.getInstance(activity).showShort(e.toString());
                        biometrics.setChecked(false);
                    }
                }
            }

            @Override
            public void onAuthenticationFailed() {
                super.onAuthenticationFailed();
                Log.e(TAG, "onAuthenticationFailed: ");
            }
        });

        promptInfo = new BiometricPrompt.PromptInfo.Builder()
                .setTitle(getString(R.string.biometrics_prompt_title))
                .setSubtitle(getString(R.string.biometrics_prompt_subtitle))
                .setNegativeButtonText(getString(R.string.cancel))
                .setAllowedAuthenticators(BIOMETRIC_STRONG)
                .build();

        iterationCount.setSummary(getString(R.string.settings_iteration_count_summary, settings.getIterationCount()));
        iterationCount.setOnPreferenceClickListener(preference -> {
            Dialogs.showSetIterationCountDialog(activity, settings.getIterationCount() + "", text -> {
                try {
                    int ic = Integer.parseInt(text);
                    if (ic < 20000 || ic > 500000) {
                        Toaster.getInstance(activity).showLong(getString(R.string.settings_iteration_count_hint));
                        return;
                    }
                    settings.setIterationCount(ic);
                    iterationCount.setSummary(getString(R.string.settings_iteration_count_summary, ic));
                } catch (NumberFormatException e) {
                    e.printStackTrace();
                }
            });
            return true;
        });

        secure.setOnPreferenceChangeListener((preference, newValue) -> {
            if ((boolean) newValue) {
                requireActivity().getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE);
            } else {
                requireActivity().getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
            }
            settings.setSecureFlag((boolean) newValue);
            return true;
        });

        deleteByDefault.setOnPreferenceChangeListener((preference, newValue) -> {
            settings.setDeleteByDefault((boolean) newValue);
            return true;
        });



        exitOnLock.setOnPreferenceChangeListener((preference, newValue) -> {
            settings.setExitOnLock((boolean) newValue);
            return true;
        });

        returnToLastApp.setOnPreferenceChangeListener((preference, newValue) -> {
            settings.setReturnToLastApp((boolean) newValue);
            return true;
        });

        preferredApp.setOnPreferenceClickListener(preference -> {
            showAppSelectionDialog(activity, settings);
            return true;
        });
        updatePreferredAppSummary(preferredApp, settings);

        editFolders.setOnPreferenceClickListener(preference -> {
            Dialogs.showEditIncludedFolders(activity, settings, selectedToRemove -> {
                settings.removeGalleryDirectories(selectedToRemove);
                Toaster.getInstance(activity).showLong(getResources().getQuantityString(R.plurals.edit_included_removed, selectedToRemove.size(), selectedToRemove.size()));
            });
            return true;
        });

        biometrics.setOnPreferenceChangeListener((preference, newValue) -> {
            if ((boolean) newValue) {
                return enableBiometrics();
            } else {
                try {
                    Encryption.deleteBiometricSecretKey();
                } catch (NoSuchAlgorithmException | KeyStoreException | CertificateException |
                         IOException e) {
                    e.printStackTrace();
                    Toaster.getInstance(activity).showLong(e.toString());
                }
                settings.setBiometricsEnabled(null, null);
            }
            return true;
        });
    }

    private boolean enableBiometrics() {
        BiometricManager biometricManager = BiometricManager.from(requireContext());
        if (biometricManager.canAuthenticate(BIOMETRIC_STRONG) != BiometricManager.BIOMETRIC_SUCCESS) {
            Toaster.getInstance(requireContext()).showLong(getString(R.string.biometrics_not_enabled));
            return false;
        }
        try {
            Cipher cipher = Encryption.getBiometricCipher();
            SecretKey secretKey = Encryption.getOrGenerateBiometricSecretKey();
            cipher.init(Cipher.ENCRYPT_MODE, secretKey);
            biometricPrompt.authenticate(promptInfo, new BiometricPrompt.CryptoObject(cipher));

            return true;
        } catch (KeyStoreException | CertificateException | IOException |
                 NoSuchAlgorithmException | NoSuchProviderException |
                 InvalidAlgorithmParameterException | UnrecoverableKeyException |
                 InvalidKeyException | NoSuchPaddingException e) {
            e.printStackTrace();
            Toaster.getInstance(requireContext()).showShort(e.toString());
            return false;
        }
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        requireActivity().addMenuProvider(this, getViewLifecycleOwner());

        NavController navController = NavHostFragment.findNavController(this);
        OnBackPressedCallback callback = new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (!navController.popBackStack()) {
                    FragmentActivity activity = requireActivity();
                    Password.lock(activity, false);
                    activity.finish();
                }
            }
        };
        requireActivity().getOnBackPressedDispatcher().addCallback(getViewLifecycleOwner(), callback);
    }

    @Override
    public void onCreateMenu(@NonNull Menu menu, @NonNull MenuInflater menuInflater) {
        menu.clear();
    }

    @Override
    public boolean onMenuItemSelected(@NonNull MenuItem menuItem) {
        return false;
    }

    private void showAppSelectionDialog(FragmentActivity activity, Settings settings) {
        try {
            android.content.pm.PackageManager pm = activity.getPackageManager();
            
            // Obtém todos os apps que podem ser iniciados
            Intent mainIntent = new Intent(Intent.ACTION_MAIN, null);
            mainIntent.addCategory(Intent.CATEGORY_LAUNCHER);
            java.util.List<android.content.pm.ResolveInfo> pkgAppsList = pm.queryIntentActivities(mainIntent, 0);
            
            java.util.List<String> availableApps = new java.util.ArrayList<>();
            java.util.List<String> availablePackages = new java.util.ArrayList<>();
            
            // Filtra apps (exclui o próprio Valv e alguns apps do sistema)
            for (android.content.pm.ResolveInfo resolveInfo : pkgAppsList) {
                String packageName = resolveInfo.activityInfo.packageName;
                String appName = resolveInfo.loadLabel(pm).toString();
                
                // Pula o próprio Valv e alguns apps do sistema, mas permite Chrome
                if (!packageName.equals(activity.getPackageName()) &&
                    !packageName.equals("com.android.settings") &&
                    !packageName.equals("com.android.systemui") &&
                    (!packageName.startsWith("com.android.") || packageName.equals("com.android.chrome"))) {
                    
                    availableApps.add(appName);
                    availablePackages.add(packageName);
                }
            }
            
            if (availableApps.isEmpty()) {
                Toaster.getInstance(activity).showLong("No apps found");
                return;
            }
            
            // Ordena alfabeticamente usando um approach simples
            java.util.List<AppInfo> appInfoList = new java.util.ArrayList<>();
            for (int i = 0; i < availableApps.size(); i++) {
                appInfoList.add(new AppInfo(availableApps.get(i), availablePackages.get(i)));
            }
            
            java.util.Collections.sort(appInfoList, new java.util.Comparator<AppInfo>() {
                @Override
                public int compare(AppInfo a1, AppInfo a2) {
                    return a1.name.compareToIgnoreCase(a2.name);
                }
            });
            
            String[] sortedAppNames = new String[appInfoList.size()];
            String[] sortedPackages = new String[appInfoList.size()];
            for (int i = 0; i < appInfoList.size(); i++) {
                sortedAppNames[i] = appInfoList.get(i).name;
                sortedPackages[i] = appInfoList.get(i).packageName;
            }
            
            new androidx.appcompat.app.AlertDialog.Builder(activity)
                    .setTitle("Choose preferred app")
                    .setItems(sortedAppNames, (dialog, which) -> {
                        String selectedPackage = sortedPackages[which];
                        settings.setPreferredApp(selectedPackage);
                        updatePreferredAppSummary(findPreference(Settings.PREF_APP_PREFERRED_APP), settings);
                        Toaster.getInstance(activity).showShort("App selected: " + sortedAppNames[which]);
                    })
                    .setNeutralButton("Remove selection", (dialog, which) -> {
                        settings.setPreferredApp(null);
                        updatePreferredAppSummary(findPreference(Settings.PREF_APP_PREFERRED_APP), settings);
                        Toaster.getInstance(activity).showShort("Selection removed");
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
                    
        } catch (Exception e) {
            android.util.Log.e("SettingsFragment", "Error loading apps", e);
            Toaster.getInstance(activity).showLong("Error loading apps: " + e.getMessage());
        }
    }
    
    private static class AppInfo {
        final String name;
        final String packageName;
        
        AppInfo(String name, String packageName) {
            this.name = name;
            this.packageName = packageName;
        }
    }
    
    private void updatePreferredAppSummary(Preference preference, Settings settings) {
        String preferredApp = settings.getPreferredApp();
        if (preferredApp != null && !preferredApp.isEmpty()) {
            try {
                android.content.pm.PackageManager pm = requireContext().getPackageManager();
                String appName = pm.getApplicationLabel(pm.getApplicationInfo(preferredApp, 0)).toString();
                preference.setSummary("Selected: " + appName);
            } catch (Exception e) {
                preference.setSummary("App not found");
            }
        } else {
            preference.setSummary("No app selected");
        }
    }
}