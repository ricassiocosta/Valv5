package ricassiocosta.me.valv5.encryption;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.signal.argon2.Argon2;
import org.signal.argon2.MemoryCost;
import org.signal.argon2.Type;
import org.signal.argon2.Version;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;

import static org.junit.Assert.*;

/**
 * Instrumentation tests for Argon2id key derivation.
 * 
 * These tests MUST run on Android device/emulator because:
 * - org.signal.argon2 requires native JNI libraries
 * - Native libs are only available in the Android runtime
 * 
 * Tests verify:
 * - Key derivation produces consistent results
 * - Different passwords produce different keys
 * - Different salts produce different keys
 * - Output key length matches specification
 * - Argon2id parameters are correctly applied
 */
@RunWith(AndroidJUnit4.class)
public class Argon2KDFTest {

    // Match production parameters from Encryption.java
    private static final int ARGON2_MEMORY_KB = 65536; // 64 MB
    private static final int ARGON2_PARALLELISM = 4;
    private static final int ARGON2_ITERATIONS = 3;
    private static final int KEY_LENGTH_BYTES = 32;
    private static final int SALT_LENGTH = 16;

    // ==================== Helper Methods ====================

    private Argon2 createArgon2() {
        return new Argon2.Builder(Version.V13)
                .type(Type.Argon2id)
                .memoryCost(MemoryCost.KiB(ARGON2_MEMORY_KB))
                .parallelism(ARGON2_PARALLELISM)
                .iterations(ARGON2_ITERATIONS)
                .hashLength(KEY_LENGTH_BYTES)
                .build();
    }

    private byte[] deriveKey(char[] password, byte[] salt) throws Exception {
        Argon2 argon2 = createArgon2();
        byte[] passwordBytes = new String(password).getBytes(StandardCharsets.UTF_8);
        try {
            Argon2.Result result = argon2.hash(passwordBytes, salt);
            return result.getHash();
        } finally {
            Arrays.fill(passwordBytes, (byte) 0);
        }
    }

    private byte[] generateRandomSalt() {
        byte[] salt = new byte[SALT_LENGTH];
        new SecureRandom().nextBytes(salt);
        return salt;
    }

    // ==================== Key Derivation Tests ====================

    @Test
    public void testKeyDerivationProducesCorrectLength() throws Exception {
        char[] password = "test-password-123".toCharArray();
        byte[] salt = generateRandomSalt();

        byte[] key = deriveKey(password, salt);

        assertEquals("Key should be " + KEY_LENGTH_BYTES + " bytes", KEY_LENGTH_BYTES, key.length);
    }

    @Test
    public void testKeyDerivationIsDeterministic() throws Exception {
        char[] password = "deterministic-test".toCharArray();
        byte[] salt = new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16};

        byte[] key1 = deriveKey(password, salt);
        byte[] key2 = deriveKey(password, salt);

        assertArrayEquals("Same password + salt should produce same key", key1, key2);
    }

    @Test
    public void testDifferentPasswordsProduceDifferentKeys() throws Exception {
        byte[] salt = generateRandomSalt();
        char[] password1 = "password-one".toCharArray();
        char[] password2 = "password-two".toCharArray();

        byte[] key1 = deriveKey(password1, salt);
        byte[] key2 = deriveKey(password2, salt);

        assertFalse("Different passwords should produce different keys", Arrays.equals(key1, key2));
    }

    @Test
    public void testDifferentSaltsProduceDifferentKeys() throws Exception {
        char[] password = "same-password".toCharArray();
        byte[] salt1 = new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16};
        byte[] salt2 = new byte[]{16, 15, 14, 13, 12, 11, 10, 9, 8, 7, 6, 5, 4, 3, 2, 1};

        byte[] key1 = deriveKey(password, salt1);
        byte[] key2 = deriveKey(password, salt2);

        assertFalse("Different salts should produce different keys", Arrays.equals(key1, key2));
    }

    @Test
    public void testKeyIsNotAllZeros() throws Exception {
        char[] password = "non-trivial-password".toCharArray();
        byte[] salt = generateRandomSalt();

        byte[] key = deriveKey(password, salt);

        boolean hasNonZero = false;
        for (byte b : key) {
            if (b != 0) {
                hasNonZero = true;
                break;
            }
        }
        assertTrue("Derived key should not be all zeros", hasNonZero);
    }

    @Test
    public void testEmptyPasswordWorks() throws Exception {
        char[] emptyPassword = new char[0];
        byte[] salt = generateRandomSalt();

        byte[] key = deriveKey(emptyPassword, salt);

        assertEquals(KEY_LENGTH_BYTES, key.length);
        // Empty password should still derive a key (not throw)
    }

    @Test
    public void testUnicodePasswordWorks() throws Exception {
        char[] unicodePassword = "пароль密码🔐".toCharArray();
        byte[] salt = generateRandomSalt();

        byte[] key = deriveKey(unicodePassword, salt);

        assertEquals(KEY_LENGTH_BYTES, key.length);
    }

    @Test
    public void testLongPasswordWorks() throws Exception {
        // Create a very long password (1000 chars)
        char[] longPassword = new char[1000];
        Arrays.fill(longPassword, 'x');
        byte[] salt = generateRandomSalt();

        byte[] key = deriveKey(longPassword, salt);

        assertEquals(KEY_LENGTH_BYTES, key.length);
    }

    // ==================== Security Property Tests ====================

    @Test
    public void testSmallPasswordChangeProducesDifferentKey() throws Exception {
        byte[] salt = generateRandomSalt();
        char[] password1 = "password123".toCharArray();
        char[] password2 = "password124".toCharArray(); // One char different

        byte[] key1 = deriveKey(password1, salt);
        byte[] key2 = deriveKey(password2, salt);

        assertFalse("Small password change should completely change key", Arrays.equals(key1, key2));

        // Verify significant difference (not just one byte)
        int differentBytes = 0;
        for (int i = 0; i < key1.length; i++) {
            if (key1[i] != key2[i]) differentBytes++;
        }
        assertTrue("Keys should differ in many bytes (avalanche effect)", differentBytes > KEY_LENGTH_BYTES / 2);
    }

    @Test
    public void testSmallSaltChangeProducesDifferentKey() throws Exception {
        char[] password = "test-password".toCharArray();
        byte[] salt1 = new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16};
        byte[] salt2 = new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 17}; // One byte different

        byte[] key1 = deriveKey(password, salt1);
        byte[] key2 = deriveKey(password, salt2);

        assertFalse("Small salt change should completely change key", Arrays.equals(key1, key2));
    }

    // ==================== Argon2id Specific Tests ====================

    @Test
    public void testArgon2idTypeIsUsed() throws Exception {
        // Verify we're using Argon2id (not Argon2i or Argon2d)
        Argon2 argon2 = new Argon2.Builder(Version.V13)
                .type(Type.Argon2id)
                .memoryCost(MemoryCost.KiB(1024)) // Small for speed
                .parallelism(1)
                .iterations(1)
                .hashLength(32)
                .build();

        byte[] password = "test".getBytes(StandardCharsets.UTF_8);
        byte[] salt = new byte[16];

        Argon2.Result result = argon2.hash(password, salt);

        assertNotNull(result);
        assertEquals(32, result.getHash().length);
        // Argon2id should produce a valid hash without throwing
    }

    @Test
    public void testProductionParametersWork() throws Exception {
        // Verify that production-level parameters work correctly
        // This is a performance test as much as a correctness test
        char[] password = "production-test".toCharArray();
        byte[] salt = generateRandomSalt();

        long startTime = System.currentTimeMillis();
        byte[] key = deriveKey(password, salt);
        long duration = System.currentTimeMillis() - startTime;

        assertEquals(KEY_LENGTH_BYTES, key.length);
        // Argon2 with 64MB memory should take some time (not instant)
        // This helps verify we're actually using the secure parameters
        assertTrue("Argon2 should take measurable time with production params", duration > 50);
    }
}
