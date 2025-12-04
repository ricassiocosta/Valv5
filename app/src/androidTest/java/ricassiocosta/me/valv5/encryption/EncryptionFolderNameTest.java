package ricassiocosta.me.valv5.encryption;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.*;

/**
 * Instrumentation tests for folder name encryption/decryption.
 * 
 * These tests MUST run on Android device/emulator because:
 * - Folder name encryption uses Argon2id which requires native libs
 * - The encryption pipeline depends on lazysodium-android
 * 
 * Tests verify:
 * - Round-trip encryption/decryption works
 * - Different passwords produce different encrypted names
 * - Same input produces different outputs (randomized)
 * - Various folder name formats work
 * - Unicode folder names work
 * - Wrong password fails gracefully
 * 
 * Constraints from Encryption.java:
 * - MAX_FOLDER_NAME_LENGTH = 30 characters
 * - Empty names throw IllegalArgumentException
 * - looksLikeEncryptedFolder requires @NonNull (no null check)
 */
@RunWith(AndroidJUnit4.class)
public class EncryptionFolderNameTest {

    // ==================== Round-trip Tests ====================

    @Test
    public void testFolderNameRoundtrip() {
        char[] password = "unit-test-password".toCharArray();
        String original = "My Secrets";

        String encrypted = Encryption.createEncryptedFolderName(original, password);
        assertNotNull(encrypted);
        assertTrue(Encryption.looksLikeEncryptedFolder(encrypted));

        String decrypted = Encryption.decryptFolderName(encrypted, password);
        assertEquals(original, decrypted);
    }

    @Test
    public void testFolderNameRoundtripEmptyNameThrows() {
        char[] password = "test-password".toCharArray();
        String original = "";

        try {
            Encryption.createEncryptedFolderName(original, password);
            fail("Empty name should throw IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("empty"));
        }
    }

    @Test
    public void testFolderNameRoundtripNullNameThrows() {
        char[] password = "test-password".toCharArray();

        try {
            Encryption.createEncryptedFolderName(null, password);
            fail("Null name should throw IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("empty"));
        }
    }

    @Test
    public void testFolderNameRoundtripMaxLength() {
        char[] password = "test-password".toCharArray();
        // Exactly 30 characters (MAX_FOLDER_NAME_LENGTH)
        String original = "123456789012345678901234567890";
        assertEquals(30, original.length());

        String encrypted = Encryption.createEncryptedFolderName(original, password);
        assertNotNull(encrypted);

        String decrypted = Encryption.decryptFolderName(encrypted, password);
        assertEquals(original, decrypted);
    }

    @Test
    public void testFolderNameRoundtripExceedsMaxLengthThrows() {
        char[] password = "test-password".toCharArray();
        // 31 characters - exceeds MAX_FOLDER_NAME_LENGTH
        String original = "1234567890123456789012345678901";
        assertEquals(31, original.length());

        try {
            Encryption.createEncryptedFolderName(original, password);
            fail("Name exceeding max length should throw IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("exceeds maximum length"));
        }
    }

    @Test
    public void testFolderNameRoundtripUnicode() {
        char[] password = "test-password".toCharArray();
        // Unicode name within 30 char limit
        String original = "密码文件夹🔐";
        assertTrue(original.length() <= 30);

        String encrypted = Encryption.createEncryptedFolderName(original, password);
        assertNotNull(encrypted);

        String decrypted = Encryption.decryptFolderName(encrypted, password);
        assertEquals(original, decrypted);
    }

    @Test
    public void testFolderNameRoundtripSpecialCharacters() {
        char[] password = "test-password".toCharArray();
        // Special characters within 30 char limit
        String original = "Folder & symbols! @#$%";
        assertTrue(original.length() <= 30);

        String encrypted = Encryption.createEncryptedFolderName(original, password);
        assertNotNull(encrypted);

        String decrypted = Encryption.decryptFolderName(encrypted, password);
        assertEquals(original, decrypted);
    }

    @Test
    public void testFolderNameRoundtripWithSpaces() {
        char[] password = "test-password".toCharArray();
        String original = "  Trimmed Name  ";

        String encrypted = Encryption.createEncryptedFolderName(original, password);
        assertNotNull(encrypted);

        // Note: implementation trims whitespace
        String decrypted = Encryption.decryptFolderName(encrypted, password);
        assertEquals("Trimmed Name", decrypted);
    }

    // ==================== Encryption Properties Tests ====================

    @Test
    public void testEncryptedFolderNameIsBase64UrlSafe() {
        char[] password = "test".toCharArray();
        String original = "Test Folder";

        String encrypted = Encryption.createEncryptedFolderName(original, password);

        // Should only contain base64url-safe characters
        assertTrue("Encrypted name should match base64url pattern",
                   encrypted.matches("[A-Za-z0-9_-]+"));

        // Should not contain characters problematic for filenames
        assertFalse(encrypted.contains("/"));
        assertFalse(encrypted.contains("+"));
        assertFalse(encrypted.contains("="));
    }

    @Test
    public void testSameFolderNameProducesDifferentCiphertexts() {
        char[] password = "test-password".toCharArray();
        String original = "Same Name";

        Set<String> encryptedNames = new HashSet<>();
        for (int i = 0; i < 5; i++) {
            String encrypted = Encryption.createEncryptedFolderName(original, password);
            encryptedNames.add(encrypted);
        }

        // All encrypted names should be different (random salt)
        assertEquals("Same name should produce different ciphertexts", 5, encryptedNames.size());
    }

    @Test
    public void testDifferentPasswordsProduceDifferentCiphertexts() {
        String original = "Test Folder";
        char[] password1 = "password-one".toCharArray();
        char[] password2 = "password-two".toCharArray();

        String encrypted1 = Encryption.createEncryptedFolderName(original, password1);
        String encrypted2 = Encryption.createEncryptedFolderName(original, password2);

        assertNotEquals("Different passwords should produce different ciphertexts",
                        encrypted1, encrypted2);
    }

    // ==================== looksLikeEncryptedFolder Tests ====================

    @Test
    public void testLooksLikeEncryptedFolderPositive() {
        char[] password = "test".toCharArray();
        String encrypted = Encryption.createEncryptedFolderName("Test", password);

        assertTrue("Created encrypted name should be recognized",
                   Encryption.looksLikeEncryptedFolder(encrypted));
    }

    @Test
    public void testLooksLikeEncryptedFolderNegativeShortStrings() {
        // Method requires @NonNull, so we don't test null here
        assertFalse(Encryption.looksLikeEncryptedFolder("My Photos"));
        assertFalse(Encryption.looksLikeEncryptedFolder("Documents"));
        assertFalse(Encryption.looksLikeEncryptedFolder("Short"));
        assertFalse(Encryption.looksLikeEncryptedFolder("")); // Too short (< 60 chars)
    }

    @Test
    public void testLooksLikeEncryptedFolderNegativeInvalidChars() {
        // 60+ chars but with invalid characters
        String longWithSpaces = "This is a very long folder name with spaces that exceeds sixty chars";
        assertTrue(longWithSpaces.length() >= 60);
        assertFalse(Encryption.looksLikeEncryptedFolder(longWithSpaces));

        // 60+ chars with slashes
        String longWithSlash = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ01234567/9";
        assertTrue(longWithSlash.length() >= 60);
        assertFalse(Encryption.looksLikeEncryptedFolder(longWithSlash));
    }

    // ==================== Wrong Password Tests ====================

    @Test
    public void testWrongPasswordReturnsNull() {
        char[] correctPassword = "correct-password".toCharArray();
        char[] wrongPassword = "wrong-password".toCharArray();
        String original = "Secret Folder";

        String encrypted = Encryption.createEncryptedFolderName(original, correctPassword);

        String decrypted = Encryption.decryptFolderName(encrypted, wrongPassword);

        // Wrong password should return null (decryption fails silently)
        assertNull("Wrong password should return null", decrypted);
    }

    @Test
    public void testUnicodePasswordWorks() {
        char[] unicodePassword = "密码пароль🔑".toCharArray();
        String original = "My Folder";

        String encrypted = Encryption.createEncryptedFolderName(original, unicodePassword);
        assertNotNull(encrypted);

        String decrypted = Encryption.decryptFolderName(encrypted, unicodePassword);
        assertEquals(original, decrypted);
    }

    @Test
    public void testLongPasswordWorks() {
        // Very long password
        char[] longPassword = "This is a very long password that exceeds typical limits 1234567890!@#$%".toCharArray();
        String original = "My Folder";

        String encrypted = Encryption.createEncryptedFolderName(original, longPassword);
        assertNotNull(encrypted);

        String decrypted = Encryption.decryptFolderName(encrypted, longPassword);
        assertEquals(original, decrypted);
    }

    // ==================== Edge Cases ====================

    @Test
    public void testDecryptInvalidStringReturnsNull() {
        char[] password = "test".toCharArray();

        // Not a valid encrypted folder name (too short)
        String invalid = "not-encrypted-folder-name";
        String result = Encryption.decryptFolderName(invalid, password);

        assertNull("Invalid input should return null", result);
    }

    @Test
    public void testDecryptTruncatedStringReturnsNull() {
        char[] password = "test".toCharArray();
        String original = "Test Folder";

        String encrypted = Encryption.createEncryptedFolderName(original, password);

        // Truncate the encrypted name
        String truncated = encrypted.substring(0, encrypted.length() / 2);

        String result = Encryption.decryptFolderName(truncated, password);
        assertNull("Truncated input should return null", result);
    }

    @Test
    public void testDecryptCorruptedStringReturnsNull() {
        char[] password = "test".toCharArray();
        String original = "Test Folder";

        String encrypted = Encryption.createEncryptedFolderName(original, password);

        // Corrupt a character in the middle
        char[] chars = encrypted.toCharArray();
        chars[encrypted.length() / 2] = 'X';
        String corrupted = new String(chars);

        String result = Encryption.decryptFolderName(corrupted, password);
        assertNull("Corrupted input should return null", result);
    }

    @Test
    public void testSingleCharacterFolderName() {
        char[] password = "test".toCharArray();
        String original = "X";

        String encrypted = Encryption.createEncryptedFolderName(original, password);
        assertNotNull(encrypted);

        String decrypted = Encryption.decryptFolderName(encrypted, password);
        assertEquals(original, decrypted);
    }
}
