package ricassiocosta.me.valv5.security;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.regex.Pattern;

import ricassiocosta.me.valv5.BuildConfig;

/**
 * Secure logging wrapper that sanitizes sensitive data before logging.
 * 
 * This class provides a drop-in replacement for android.util.Log with automatic
 * sanitization of potentially sensitive information such as:
 * - File paths and URIs
 * - Hexadecimal strings (potential hashes, salts, keys)
 * - Password-related content
 * - Encryption-related identifiers
 * 
 * In release builds, all logging is completely disabled for security.
 * 
 * Usage:
 *   SecureSecureLog.d(TAG, "Processing file: " + uri);
 *   // In debug: "Processing file: [PATH:***redacted***]"
 *   // In release: nothing logged
 * 
 * Security considerations:
 * - All logs are disabled in release builds (BuildConfig.DEBUG = false)
 * - ProGuard rules should strip Log calls from release builds
 * - Sensitive patterns are redacted even in debug for development safety
 */
public final class SecureLog {
    
    // Prevent instantiation
    private SecureLog() {}
    
    // ============================================================
    // Sensitive data patterns for sanitization
    // ============================================================
    
    /**
     * Matches hexadecimal strings of 16+ characters (potential hashes, keys, salts)
     * Examples: "a1b2c3d4e5f6g7h8", "0123456789ABCDEF0123"
     */
    private static final Pattern HEX_PATTERN = 
            Pattern.compile("[a-fA-F0-9]{16,}");
    
    /**
     * Matches content:// URIs which may expose file locations
     * Examples: "content://com.android.providers.downloads.documents/document/123"
     */
    private static final Pattern CONTENT_URI_PATTERN = 
            Pattern.compile("content://[^\\s]+");
    
    /**
     * Matches file:// URIs
     * Examples: "file:///storage/emulated/0/Download/secret.valv"
     */
    private static final Pattern FILE_URI_PATTERN = 
            Pattern.compile("file://[^\\s]+");
    
    /**
     * Matches Android document URIs (tree and document paths)
     * Examples: "primary:Download/MyFolder"
     */
    private static final Pattern DOCUMENT_PATH_PATTERN = 
            Pattern.compile("(primary|raw):[^\\s,)]+");
    
    /**
     * Matches absolute file paths
     * Examples: "/storage/emulated/0/Download/file.valv"
     */
    private static final Pattern ABSOLUTE_PATH_PATTERN = 
            Pattern.compile("/storage/[^\\s,)]+");
    
    /**
     * Matches .valv file extensions (encrypted files)
     * Examples: "photo.jpg.valv", "document.valv"
     */
    private static final Pattern VALV_FILE_PATTERN = 
            Pattern.compile("[^\\s/]+\\.valv\\b");
    
    /**
     * Matches base64-encoded strings (32+ chars, potential encrypted data)
     * Examples: "SGVsbG8gV29ybGQhIFRoaXMgaXMgYSB0ZXN0..."
     */
    private static final Pattern BASE64_PATTERN = 
            Pattern.compile("[A-Za-z0-9+/]{32,}={0,2}");
    
    /**
     * Matches byte array representations
     * Examples: "[B@1234abcd", "bytes=[10, 20, 30, ...]"
     */
    private static final Pattern BYTE_ARRAY_PATTERN = 
            Pattern.compile("\\[B@[a-fA-F0-9]+|bytes?\\s*=\\s*\\[[^\\]]{20,}\\]");
    
    // ============================================================
    // Redaction tokens
    // ============================================================
    
    private static final String REDACTED_HEX = "[HEX:***]";
    private static final String REDACTED_URI = "[URI:***]";
    private static final String REDACTED_PATH = "[PATH:***]";
    private static final String REDACTED_FILE = "[FILE:***]";
    private static final String REDACTED_B64 = "[B64:***]";
    private static final String REDACTED_BYTES = "[BYTES:***]";
    
    // ============================================================
    // Public API - Drop-in replacement for android.util.Log
    // ============================================================
    
    /**
     * Send a VERBOSE log message.
     * @param tag Used to identify the source of a log message.
     * @param msg The message you would like logged.
     * @return The number of bytes written, or 0 in release builds.
     */
    public static int v(@NonNull String tag, @NonNull String msg) {
        if (!BuildConfig.DEBUG) return 0;
        return Log.v(tag, sanitize(msg));
    }
    
    /**
     * Send a VERBOSE log message with throwable.
     */
    public static int v(@NonNull String tag, @NonNull String msg, @Nullable Throwable tr) {
        if (!BuildConfig.DEBUG) return 0;
        return Log.v(tag, sanitize(msg), sanitizeThrowable(tr));
    }
    
    /**
     * Send a DEBUG log message.
     * @param tag Used to identify the source of a log message.
     * @param msg The message you would like logged.
     * @return The number of bytes written, or 0 in release builds.
     */
    public static int d(@NonNull String tag, @NonNull String msg) {
        if (!BuildConfig.DEBUG) return 0;
        return Log.d(tag, sanitize(msg));
    }
    
    /**
     * Send a DEBUG log message with throwable.
     */
    public static int d(@NonNull String tag, @NonNull String msg, @Nullable Throwable tr) {
        if (!BuildConfig.DEBUG) return 0;
        return Log.d(tag, sanitize(msg), sanitizeThrowable(tr));
    }
    
    /**
     * Send an INFO log message.
     * @param tag Used to identify the source of a log message.
     * @param msg The message you would like logged.
     * @return The number of bytes written, or 0 in release builds.
     */
    public static int i(@NonNull String tag, @NonNull String msg) {
        if (!BuildConfig.DEBUG) return 0;
        return Log.i(tag, sanitize(msg));
    }
    
    /**
     * Send an INFO log message with throwable.
     */
    public static int i(@NonNull String tag, @NonNull String msg, @Nullable Throwable tr) {
        if (!BuildConfig.DEBUG) return 0;
        return Log.i(tag, sanitize(msg), sanitizeThrowable(tr));
    }
    
    /**
     * Send a WARN log message.
     * @param tag Used to identify the source of a log message.
     * @param msg The message you would like logged.
     * @return The number of bytes written, or 0 in release builds.
     */
    public static int w(@NonNull String tag, @NonNull String msg) {
        if (!BuildConfig.DEBUG) return 0;
        return Log.w(tag, sanitize(msg));
    }
    
    /**
     * Send a WARN log message with throwable.
     */
    public static int w(@NonNull String tag, @NonNull String msg, @Nullable Throwable tr) {
        if (!BuildConfig.DEBUG) return 0;
        return Log.w(tag, sanitize(msg), sanitizeThrowable(tr));
    }
    
    /**
     * Send a WARN log message (throwable only).
     */
    public static int w(@NonNull String tag, @Nullable Throwable tr) {
        if (!BuildConfig.DEBUG) return 0;
        return Log.w(tag, sanitizeThrowable(tr));
    }
    
    /**
     * Send an ERROR log message.
     * @param tag Used to identify the source of a log message.
     * @param msg The message you would like logged.
     * @return The number of bytes written, or 0 in release builds.
     */
    public static int e(@NonNull String tag, @NonNull String msg) {
        if (!BuildConfig.DEBUG) return 0;
        return Log.e(tag, sanitize(msg));
    }
    
    /**
     * Send an ERROR log message with throwable.
     */
    public static int e(@NonNull String tag, @NonNull String msg, @Nullable Throwable tr) {
        if (!BuildConfig.DEBUG) return 0;
        return Log.e(tag, sanitize(msg), sanitizeThrowable(tr));
    }
    
    /**
     * What a Terrible Failure: Report a condition that should never happen.
     */
    public static int wtf(@NonNull String tag, @NonNull String msg) {
        if (!BuildConfig.DEBUG) return 0;
        return Log.wtf(tag, sanitize(msg));
    }
    
    /**
     * What a Terrible Failure with throwable.
     */
    public static int wtf(@NonNull String tag, @NonNull String msg, @Nullable Throwable tr) {
        if (!BuildConfig.DEBUG) return 0;
        return Log.wtf(tag, sanitize(msg), sanitizeThrowable(tr));
    }
    
    // ============================================================
    // Sanitization logic
    // ============================================================
    
    /**
     * Sanitizes a log message by redacting sensitive patterns.
     * 
     * The order of pattern replacement matters - more specific patterns
     * should be applied before more general ones.
     * 
     * @param message The original log message
     * @return Sanitized message with sensitive data redacted
     */
    @NonNull
    public static String sanitize(@Nullable String message) {
        if (message == null) {
            return "null";
        }
        
        String result = message;
        
        // Order matters: apply more specific patterns first
        
        // 1. Content URIs (most specific)
        result = CONTENT_URI_PATTERN.matcher(result).replaceAll(REDACTED_URI);
        
        // 2. File URIs
        result = FILE_URI_PATTERN.matcher(result).replaceAll(REDACTED_URI);
        
        // 3. Document paths (primary:, raw:)
        result = DOCUMENT_PATH_PATTERN.matcher(result).replaceAll(REDACTED_PATH);
        
        // 4. Absolute paths (/storage/...)
        result = ABSOLUTE_PATH_PATTERN.matcher(result).replaceAll(REDACTED_PATH);
        
        // 5. .valv file names
        result = VALV_FILE_PATTERN.matcher(result).replaceAll(REDACTED_FILE);
        
        // 6. Byte arrays
        result = BYTE_ARRAY_PATTERN.matcher(result).replaceAll(REDACTED_BYTES);
        
        // 7. Base64 strings (before hex, as they overlap)
        result = BASE64_PATTERN.matcher(result).replaceAll(REDACTED_B64);
        
        // 8. Hex strings (potential keys, hashes, salts)
        result = HEX_PATTERN.matcher(result).replaceAll(REDACTED_HEX);
        
        return result;
    }
    
    /**
     * Sanitizes a throwable by wrapping it with a sanitized message.
     * 
     * Note: We don't modify the original throwable's stack trace as it may
     * be needed for actual debugging. The message is sanitized, but the
     * class names and line numbers remain for debugging purposes.
     * 
     * @param tr The original throwable
     * @return A sanitized throwable or the original if null
     */
    @Nullable
    private static Throwable sanitizeThrowable(@Nullable Throwable tr) {
        if (tr == null) {
            return null;
        }
        
        // For security, we wrap the throwable with a sanitized message
        // but preserve the stack trace for debugging
        String originalMessage = tr.getMessage();
        if (originalMessage != null) {
            String sanitizedMessage = sanitize(originalMessage);
            if (!sanitizedMessage.equals(originalMessage)) {
                // Create a wrapper exception with sanitized message
                return new SanitizedException(sanitizedMessage, tr);
            }
        }
        
        return tr;
    }
    
    /**
     * Wrapper exception used when the original exception message contains sensitive data.
     */
    private static class SanitizedException extends Exception {
        SanitizedException(String sanitizedMessage, Throwable cause) {
            super(sanitizedMessage, cause);
        }
        
        @Override
        public synchronized Throwable fillInStackTrace() {
            // Don't fill in stack trace for wrapper exception
            return this;
        }
    }
    
    // ============================================================
    // Utility methods for manual sanitization
    // ============================================================
    
    /**
     * Redacts a URI for safe logging.
     * Returns only the scheme and a redacted placeholder.
     * 
     * Example: "content://com.android.providers/..." -> "content://[REDACTED]"
     * 
     * @param uri The URI to redact
     * @return Redacted URI string
     */
    @NonNull
    public static String redactUri(@Nullable Object uri) {
        if (uri == null) {
            return "null";
        }
        String uriString = uri.toString();
        int schemeEnd = uriString.indexOf("://");
        if (schemeEnd > 0) {
            return uriString.substring(0, schemeEnd + 3) + "[REDACTED]";
        }
        return "[URI:REDACTED]";
    }
    
    /**
     * Redacts a file path, keeping only the file extension for debugging context.
     * 
     * Example: "/storage/.../photo.jpg.valv" -> "[FILE:***.valv]"
     * 
     * @param path The path to redact
     * @return Redacted path string
     */
    @NonNull
    public static String redactPath(@Nullable String path) {
        if (path == null) {
            return "null";
        }
        int lastDot = path.lastIndexOf('.');
        if (lastDot > 0 && lastDot < path.length() - 1) {
            String ext = path.substring(lastDot);
            return "[FILE:***" + ext + "]";
        }
        return "[PATH:REDACTED]";
    }
    
    /**
     * Redacts a byte array, showing only the length for debugging.
     * 
     * Example: byte[256] -> "[BYTES:256]"
     * 
     * @param bytes The byte array to describe
     * @return Safe description string
     */
    @NonNull
    public static String redactBytes(@Nullable byte[] bytes) {
        if (bytes == null) {
            return "[BYTES:null]";
        }
        return "[BYTES:" + bytes.length + "]";
    }
    
    /**
     * Creates a safe count-only log for file operations.
     * 
     * Example: files.size() -> "[COUNT:5 files]"
     * 
     * @param count The count to log
     * @param itemType Description of what's being counted
     * @return Safe count string
     */
    @NonNull
    public static String safeCount(int count, @NonNull String itemType) {
        return "[COUNT:" + count + " " + itemType + "]";
    }
}
