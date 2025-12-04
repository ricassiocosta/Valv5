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
    public void testFolderNameRoundtripEmptyName() {
        char[] password = "test-password".toCharArray();
        String original = "";

        String encrypted = Encryption.createEncryptedFolderName(original, password);
        assertNotNull(encrypted);

        String decrypted = Encryption.decryptFolderName(encrypted, password);
        assertEquals(original, decrypted);
    }

    @Test
    public void testFolderNameRoundtripLongName() {
        char[] password = "test-password".toCharArray();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            sb.append("Long Folder Name ");
        }
        String original = sb.toString();

        String encrypted = Encryption.createEncryptedFolderName(original, password);
        assertNotNull(encrypted);

        String decrypted = Encryption.decryptFolderName(encrypted, password);
        assertEquals(original, decrypted);
    }

    @Test
    public void testFolderNameRoundtripUnicode() {
        char[] password = "test-password".toCharArray();
        String original = "Pasta Secreta 密码文件夹 🔐📁";

        String encrypted = Encryption.createEncryptedFolderName(original, password);
        assertNotNull(encrypted);

        String decrypted = Encryption.decryptFolderName(encrypted, password);
        assertEquals(original, decrypted);
    }

    @Test
    public void testFolderNameRoundtripSpecialCharacters() {
        char[] password = "test-password".toCharArray();
        String original = "Folder with spaces & symbols! @#$%^*()";

        String encrypted = Encryption.createEncryptedFolderName(original, password);
        assertNotNull(encrypted);

        String decrypted = Encryption.decryptFolderName(encrypted, password);
        assertEquals(original, decrypted);
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
    public void testLooksLikeEncryptedFolderNegative() {
        assertFalse(Encryption.looksLikeEncryptedFolder("My Photos"));
        assertFalse(Encryption.looksLikeEncryptedFolder("Documents"));
        assertFalse(Encryption.looksLikeEncryptedFolder("Short"));
        assertFalse(Encryption.looksLikeEncryptedFolder("")); // Too short
        assertFalse(Encryption.looksLikeEncryptedFolder(null));
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
    public void testEmptyPasswordWorks() {
        char[] emptyPassword = new char[0];
        String original = "Folder Name";

        String encrypted = Encryption.createEncryptedFolderName(original, emptyPassword);
        assertNotNull(encrypted);

        String decrypted = Encryption.decryptFolderName(encrypted, emptyPassword);
        assertEquals(original, decrypted);
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

    // ==================== Edge Cases ====================

    @Test
    public void testDecryptInvalidStringReturnsNull() {
        char[] password = "test".toCharArray();

        // Not a valid encrypted folder name
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
}
