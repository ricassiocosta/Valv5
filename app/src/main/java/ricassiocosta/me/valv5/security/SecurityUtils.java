/*
 * Valv5
 * Copyright (c) 2025 ricassiocosta.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see https://www.gnu.org/licenses/.
 */

package ricassiocosta.me.valv5.security;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.security.MessageDigest;

/**
 * Security utilities for detecting rooted devices, emulators, and tampering.
 * These checks help protect sensitive data from compromised environments.
 */
public final class SecurityUtils {

    private SecurityUtils() {
        // Utility class
    }

    // Common paths where su binary might be located
    private static final String[] SU_PATHS = {
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/system/su",
            "/system/bin/.ext/.su",
            "/system/usr/we-need-root/su-backup",
            "/system/xbin/mu",
            "/data/local/xbin/su",
            "/data/local/bin/su",
            "/data/local/su",
            "/su/bin/su",
            "/su/bin",
            "/magisk/.core/bin/su"
    };

    // Paths that indicate root management apps
    private static final String[] ROOT_MANAGEMENT_APPS = {
            "/system/app/Superuser.apk",
            "/system/app/SuperSU.apk",
            "/system/app/SuperSU",
            "/data/data/com.noshufou.android.su",
            "/data/data/eu.chainfire.supersu",
            "/data/data/com.koushikdutta.superuser",
            "/data/data/com.thirdparty.superuser",
            "/data/data/com.yellowes.su",
            "/data/data/com.topjohnwu.magisk"
    };

    // Magisk-specific paths
    private static final String[] MAGISK_PATHS = {
            "/sbin/.magisk",
            "/cache/.disable_magisk",
            "/dev/.magisk.unblock",
            "/data/adb/magisk",
            "/data/adb/magisk.img",
            "/data/adb/magisk.db",
            "/data/data/com.topjohnwu.magisk",
            "/data/user/0/com.topjohnwu.magisk",
            "/data/user_de/0/com.topjohnwu.magisk"
    };

    /**
     * Comprehensive root detection check.
     * @return true if device appears to be rooted
     */
    public static boolean isRooted() {
        return checkSuBinary() || 
               checkRootManagementApps() || 
               checkRootBuildTags() ||
               checkSuCommand();
    }

    /**
     * Check for presence of su binary in common locations.
     */
    private static boolean checkSuBinary() {
        for (String path : SU_PATHS) {
            if (new File(path).exists()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Check for root management applications.
     */
    private static boolean checkRootManagementApps() {
        for (String path : ROOT_MANAGEMENT_APPS) {
            if (new File(path).exists()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Check build tags for test-keys (indicates non-release build).
     */
    private static boolean checkRootBuildTags() {
        String buildTags = Build.TAGS;
        return buildTags != null && buildTags.contains("test-keys");
    }

    /**
     * Try to execute su command to detect root.
     */
    private static boolean checkSuCommand() {
        Process process = null;
        try {
            process = Runtime.getRuntime().exec(new String[]{"which", "su"});
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line = reader.readLine();
                return line != null && !line.isEmpty();
            }
        } catch (Exception e) {
            return false;
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
    }

    /**
     * Check if Magisk is installed (even if hidden).
     * @return true if Magisk appears to be present
     */
    public static boolean hasMagisk() {
        // Check known Magisk paths
        for (String path : MAGISK_PATHS) {
            if (new File(path).exists()) {
                return true;
            }
        }

        // Check for Magisk Manager or renamed packages
        Process process = null;
        try {
            process = Runtime.getRuntime().exec("pm list packages");
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    // Check for Magisk Manager (may be renamed)
                    if (line.contains("com.topjohnwu.magisk")) {
                        return true;
                    }
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (process != null) {
                process.destroy();
            }
        }

        return false;
    }

    /**
     * Check if running on an emulator.
     * @return true if device appears to be an emulator
     */
    public static boolean isEmulator() {
        return checkEmulatorBuild() || checkEmulatorHardware() || checkEmulatorFiles();
    }

    /**
     * Check Build properties for emulator signatures.
     */
    private static boolean checkEmulatorBuild() {
        return Build.FINGERPRINT.startsWith("generic")
                || Build.FINGERPRINT.startsWith("unknown")
                || Build.MODEL.contains("google_sdk")
                || Build.MODEL.contains("Emulator")
                || Build.MODEL.contains("Android SDK built for x86")
                || Build.MANUFACTURER.contains("Genymotion")
                || Build.BRAND.startsWith("generic")
                || Build.DEVICE.startsWith("generic")
                || "google_sdk".equals(Build.PRODUCT)
                || Build.PRODUCT.contains("sdk")
                || Build.PRODUCT.contains("sdk_gphone")
                || Build.PRODUCT.contains("vbox86p")
                || Build.PRODUCT.contains("emulator")
                || Build.PRODUCT.contains("simulator")
                || Build.HARDWARE.contains("goldfish")
                || Build.HARDWARE.contains("ranchu");
    }

    /**
     * Check hardware properties for emulator signatures.
     */
    private static boolean checkEmulatorHardware() {
        String hardware = Build.HARDWARE;
        return hardware.equals("goldfish")
                || hardware.equals("ranchu")
                || hardware.contains("nox")
                || hardware.contains("vbox");
    }

    /**
     * Check for emulator-specific files.
     */
    private static boolean checkEmulatorFiles() {
        String[] emulatorFiles = {
                "/dev/socket/qemud",
                "/dev/qemu_pipe",
                "/system/lib/libc_malloc_debug_qemu.so",
                "/sys/qemu_trace",
                "/system/bin/qemu-props"
        };
        
        for (String path : emulatorFiles) {
            if (new File(path).exists()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Verify the app's signature hasn't been tampered with.
     * @param context Application context
     * @param expectedSignatureHash SHA-256 hash of the expected signature
     * @return true if signature is valid
     */
    public static boolean verifyAppSignature(Context context, String expectedSignatureHash) {
        if (expectedSignatureHash == null || expectedSignatureHash.isEmpty()) {
            // No expected signature provided, skip check
            return true;
        }

        try {
            PackageInfo packageInfo = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), PackageManager.GET_SIGNATURES);
            
            Signature[] signatures = packageInfo.signatures;
            if (signatures == null || signatures.length == 0) {
                return false;
            }

            // Get the first signature (primary signing key)
            byte[] signatureBytes = signatures[0].toByteArray();
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(signatureBytes);
            
            String currentHash = bytesToHex(digest);
            return currentHash.equalsIgnoreCase(expectedSignatureHash);
            
        } catch (Exception e) {
            // If we can't verify, assume tampered for safety
            return false;
        }
    }

    /**
     * Get the current app's signature hash for initial setup.
     * @param context Application context
     * @return SHA-256 hash of the app signature, or null on error
     */
    public static String getAppSignatureHash(Context context) {
        try {
            PackageInfo packageInfo = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), PackageManager.GET_SIGNATURES);
            
            Signature[] signatures = packageInfo.signatures;
            if (signatures == null || signatures.length == 0) {
                return null;
            }

            byte[] signatureBytes = signatures[0].toByteArray();
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(signatureBytes);
            
            return bytesToHex(digest);
            
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Perform all security checks and return a result object.
     * @param context Application context (optional, for signature verification)
     * @param expectedSignatureHash Expected signature hash (optional)
     * @return SecurityCheckResult with all check results
     */
    public static SecurityCheckResult performSecurityChecks(Context context, String expectedSignatureHash) {
        boolean rooted = isRooted();
        boolean magisk = hasMagisk();
        boolean emulator = isEmulator();
        boolean signatureValid = context != null && verifyAppSignature(context, expectedSignatureHash);

        return new SecurityCheckResult(rooted, magisk, emulator, signatureValid);
    }

    /**
     * Convert byte array to hex string.
     */
    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    /**
     * Result class for security checks.
     */
    public static class SecurityCheckResult {
        public final boolean isRooted;
        public final boolean hasMagisk;
        public final boolean isEmulator;
        public final boolean isSignatureValid;

        public SecurityCheckResult(boolean isRooted, boolean hasMagisk, 
                                   boolean isEmulator, boolean isSignatureValid) {
            this.isRooted = isRooted;
            this.hasMagisk = hasMagisk;
            this.isEmulator = isEmulator;
            this.isSignatureValid = isSignatureValid;
        }

        /**
         * Check if the device environment is considered safe.
         * By default, only rooted devices and Magisk are considered unsafe.
         * Emulators are allowed for development purposes.
         */
        public boolean isSafe() {
            return !isRooted && !hasMagisk;
        }

        /**
         * Check if the device environment is safe, with custom policy.
         * @param allowEmulator Allow running on emulators
         * @param requireValidSignature Require valid app signature
         */
        public boolean isSafe(boolean allowEmulator, boolean requireValidSignature) {
            boolean safe = !isRooted && !hasMagisk;
            
            if (!allowEmulator && isEmulator) {
                safe = false;
            }
            
            if (requireValidSignature && !isSignatureValid) {
                safe = false;
            }
            
            return safe;
        }

        @Override
        public String toString() {
            return "SecurityCheckResult{" +
                    "isRooted=" + isRooted +
                    ", hasMagisk=" + hasMagisk +
                    ", isEmulator=" + isEmulator +
                    ", isSignatureValid=" + isSignatureValid +
                    ", isSafe=" + isSafe() +
                    '}';
        }
    }
}
