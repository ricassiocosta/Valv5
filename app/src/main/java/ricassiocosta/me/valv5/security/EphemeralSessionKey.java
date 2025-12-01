package ricassiocosta.me.valv5.security;

import androidx.annotation.NonNull;

import java.security.SecureRandom;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Manages an ephemeral session key that is regenerated each time the app starts
 * or the user logs in.
 * 
 * <h3>Security Benefits:</h3>
 * <ul>
 *   <li><b>Cache Isolation:</b> Each session has a unique key, so cached data from 
 *       previous sessions cannot be accessed even if the attacker knows the current key.</li>
 *   <li><b>Forward Secrecy:</b> Compromising the current session key doesn't expose 
 *       historical cached data.</li>
 *   <li><b>Unpredictability:</b> Uses cryptographically secure random bytes instead 
 *       of predictable timestamps.</li>
 * </ul>
 * 
 * <h3>How it Works:</h3>
 * <p>The session key is used as a signature/salt for Glide's cache system. When the
 * key changes (on app restart or lock), all previously cached thumbnails become invalid
 * because their cache keys no longer match.</p>
 * 
 * <h3>Usage:</h3>
 * <pre>
 * // Get key for cache signature
 * long cacheKey = EphemeralSessionKey.getInstance().getSessionId();
 * 
 * // Regenerate on login
 * EphemeralSessionKey.getInstance().regenerate();
 * 
 * // Clear on lock
 * EphemeralSessionKey.getInstance().destroy();
 * </pre>
 * 
 * <h3>Trade-offs:</h3>
 * <ul>
 *   <li>Thumbnails must be regenerated after each app restart</li>
 *   <li>Slightly higher CPU usage for thumbnail generation</li>
 *   <li>Better security against forensics and memory dump attacks</li>
 * </ul>
 */
public final class EphemeralSessionKey {
    
    private static final String TAG = "EphemeralSessionKey";
    
    // Singleton instance
    private static volatile EphemeralSessionKey instance;
    
    // The ephemeral key bytes (32 bytes = 256 bits)
    private byte[] keyBytes;
    
    // A numeric session ID derived from the key for use with Glide signatures
    // Using AtomicLong for thread-safety
    private final AtomicLong sessionId;
    
    // Generation counter - increments each time the key is regenerated
    private final AtomicLong generation;
    
    // SecureRandom instance for key generation
    private final SecureRandom secureRandom;
    
    // Lock for key operations
    private final Object keyLock = new Object();
    
    /**
     * Private constructor - use getInstance().
     */
    private EphemeralSessionKey() {
        this.secureRandom = new SecureRandom();
        this.sessionId = new AtomicLong(0);
        this.generation = new AtomicLong(0);
        this.keyBytes = new byte[32];
        
        // Generate initial key
        regenerateInternal();
    }
    
    /**
     * Get the singleton instance.
     * Thread-safe with double-checked locking.
     */
    @NonNull
    public static EphemeralSessionKey getInstance() {
        if (instance == null) {
            synchronized (EphemeralSessionKey.class) {
                if (instance == null) {
                    instance = new EphemeralSessionKey();
                }
            }
        }
        return instance;
    }
    
    /**
     * Regenerate the session key.
     * Should be called on each successful login.
     * 
     * This invalidates all cached data from previous sessions.
     */
    public void regenerate() {
        synchronized (keyLock) {
            // Wipe old key first
            if (keyBytes != null) {
                Arrays.fill(keyBytes, (byte) 0);
            }
            regenerateInternal();
        }
        
        SecureLog.d(TAG, "Session key regenerated, generation=" + generation.get());
    }
    
    /**
     * Internal regeneration without locking (called from constructor and regenerate).
     */
    private void regenerateInternal() {
        // Generate new random bytes
        keyBytes = new byte[32];
        secureRandom.nextBytes(keyBytes);
        
        // Derive a long from the first 8 bytes for use as cache signature
        // This is NOT cryptographically sensitive - just needs to be unique per session
        long derived = 0;
        for (int i = 0; i < 8; i++) {
            derived = (derived << 8) | (keyBytes[i] & 0xFF);
        }
        sessionId.set(derived);
        
        // Increment generation counter
        generation.incrementAndGet();
    }
    
    /**
     * Get the current session ID for use as a cache signature.
     * This value changes each time regenerate() is called.
     * 
     * @return A unique session identifier
     */
    public long getSessionId() {
        return sessionId.get();
    }
    
    /**
     * Get the current generation number.
     * Useful for debugging and tracking session changes.
     * 
     * @return The generation counter value
     */
    public long getGeneration() {
        return generation.get();
    }
    
    /**
     * Get a copy of the raw key bytes.
     * Use with caution - the caller is responsible for wiping the returned array.
     * 
     * @return A copy of the 32-byte session key
     */
    @NonNull
    public byte[] getKeyBytes() {
        synchronized (keyLock) {
            if (keyBytes == null) {
                regenerateInternal();
            }
            return keyBytes.clone();
        }
    }
    
    /**
     * Securely destroy the session key.
     * Should be called when locking the app.
     * 
     * After calling this, the key will be regenerated on next access.
     */
    public void destroy() {
        synchronized (keyLock) {
            if (keyBytes != null) {
                // Wipe with multiple patterns for paranoid security
                Arrays.fill(keyBytes, (byte) 0xFF);
                Arrays.fill(keyBytes, (byte) 0x00);
                Arrays.fill(keyBytes, (byte) 0xAA);
                Arrays.fill(keyBytes, (byte) 0x55);
                Arrays.fill(keyBytes, (byte) 0x00);
                keyBytes = null;
            }
            // Reset session ID to 0 (will be regenerated on next access)
            sessionId.set(0);
        }
        
        SecureLog.d(TAG, "Session key destroyed");
    }
    
    /**
     * Check if the session key is currently valid (not destroyed).
     * 
     * @return true if the key exists, false if it was destroyed
     */
    public boolean isValid() {
        synchronized (keyLock) {
            return keyBytes != null;
        }
    }
    
    /**
     * Ensure the key is valid, regenerating if necessary.
     * Call this before using the key if it might have been destroyed.
     */
    public void ensureValid() {
        synchronized (keyLock) {
            if (keyBytes == null) {
                regenerateInternal();
                SecureLog.d(TAG, "Session key auto-regenerated after destroy");
            }
        }
    }
}
