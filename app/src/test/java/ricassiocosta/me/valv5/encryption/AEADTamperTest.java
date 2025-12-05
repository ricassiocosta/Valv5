package ricassiocosta.me.valv5.encryption;

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.security.Security;
import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

import static org.junit.Assert.*;

/**
 * Tests that AEAD encryption properly detects tampering and authentication failures.
 * 
 * These tests verify that:
 * - Tampered ciphertext is rejected
 * - Truncated authentication tags are rejected
 * - Truncated headers are rejected
 * - Wrong passwords fail authentication
 * - Malformed iteration flags are handled
 * - Truncated ciphertext is rejected
 */
public class AEADTamperTest {

    private static final int IV_LENGTH = 12;
    private static final int DEFAULT_ITERATIONS = 1000;

    @BeforeClass
    public static void setup() {
        Security.addProvider(new BouncyCastleProvider());
    }

    // ==================== Helper Methods ====================

    /**
     * Encrypts data using AEAD (ChaCha20-Poly1305) and returns the complete encrypted blob.
     */
    private static byte[] encryptWithAEAD(byte[] fileData, char[] password, byte[] salt, byte[] iv, int iterationCount) throws Exception {
        int storedIteration = iterationCount | 0x80000000; // AEAD flag

        byte[] versionBytes = Encryption.toByteArray(Encryption.ENCRYPTION_VERSION_5);
        byte[] iterationBytes = Encryption.toByteArray(storedIteration);

        // Derive key using PBKDF2
        SecretKeyFactory skf = SecretKeyFactory.getInstance("PBKDF2withHmacSHA512");
        PBEKeySpec spec = new PBEKeySpec(password, salt, iterationCount, 256);
        SecretKey tmp = skf.generateSecret(spec);
        SecretKey secretKey = new SecretKeySpec(tmp.getEncoded(), "ChaCha20");

        // Build plaintext composite
        ByteArrayOutputStream plaintextBuffer = new ByteArrayOutputStream();
        plaintextBuffer.write(("\n{}\n").getBytes());
        SectionWriter sw = new SectionWriter(plaintextBuffer);
        sw.writeFileSection(new ByteArrayInputStream(fileData), fileData.length);
        sw.writeEndMarker();
        byte[] plaintext = plaintextBuffer.toByteArray();

        // Encrypt
        Cipher cipher = Cipher.getInstance("ChaCha20-Poly1305", "BC");
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, new IvParameterSpec(iv));

        // Build and apply AAD
        byte[] aad = buildAAD(versionBytes, salt, iv, iterationBytes);
        cipher.updateAAD(aad);

        byte[] ciphertext = cipher.doFinal(plaintext);

        // Combine header + ciphertext
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(versionBytes);
        out.write(salt);
        out.write(iv);
        out.write(iterationBytes);
        out.write(ciphertext);

        return out.toByteArray();
    }

    private static byte[] buildAAD(byte[] version, byte[] salt, byte[] iv, byte[] iteration) {
        byte[] aad = new byte[4 + salt.length + iv.length + 4];
        System.arraycopy(version, 0, aad, 0, 4);
        System.arraycopy(salt, 0, aad, 4, salt.length);
        System.arraycopy(iv, 0, aad, 4 + salt.length, iv.length);
        System.arraycopy(iteration, 0, aad, 4 + salt.length + iv.length, 4);
        return aad;
    }

    private static byte[] generateRandomBytes(int length) throws Exception {
        byte[] bytes = new byte[length];
        SecureRandom.getInstanceStrong().nextBytes(bytes);
        return bytes;
    }

    private static int getHeaderLength() {
        return 4 + Encryption.SALT_LENGTH + IV_LENGTH + 4;
    }

    /**
     * Attempts to decrypt and verifies that it fails.
     */
    private static void assertDecryptionFails(byte[] encryptedData, char[] password, String failureMessage) {
        try {
            Encryption.Streams streams = Encryption.getCipherInputStream(
                new ByteArrayInputStream(encryptedData), 
                password, 
                false, 
                Encryption.ENCRYPTION_VERSION_5
            );
            streams.getFileBytes();
            streams.close();
            fail(failureMessage);
        } catch (Exception e) {
            // Expected: decryption/authentication should fail
            assertNotNull("Exception should have a message or cause", e);
        }
    }

    // ==================== Tamper Tests ====================

    @Test
    public void testAEADTamperDetected() throws Exception {
        byte[] fileData = "Sensitive data to protect".getBytes();
        char[] password = "badger".toCharArray();
        byte[] salt = generateRandomBytes(Encryption.SALT_LENGTH);
        byte[] iv = generateRandomBytes(IV_LENGTH);

        byte[] encrypted = encryptWithAEAD(fileData, password, salt, iv, DEFAULT_ITERATIONS);

        // Tamper a byte in ciphertext payload
        int headerLen = getHeaderLength();
        if (encrypted.length > headerLen + 5) {
            encrypted[headerLen + 5] ^= 0x01;
        }

        assertDecryptionFails(encrypted, password, "Tampered ciphertext should not decrypt successfully");
    }

    @Test
    public void testAEADTruncatedTagDetected() throws Exception {
        byte[] fileData = "Sensitive data short".getBytes();
        char[] password = "badger".toCharArray();
        byte[] salt = generateRandomBytes(Encryption.SALT_LENGTH);
        byte[] iv = generateRandomBytes(IV_LENGTH);

        byte[] encrypted = encryptWithAEAD(fileData, password, salt, iv, DEFAULT_ITERATIONS);

        // Truncate authentication tag (last 8 bytes)
        byte[] truncated = java.util.Arrays.copyOf(encrypted, Math.max(0, encrypted.length - 8));

        assertDecryptionFails(truncated, password, "Truncated tag should not decrypt successfully");
    }

    @Test
    public void testAEADTruncatedHeaderDetected() throws Exception {
        byte[] fileData = "Data header test".getBytes();
        char[] password = "badger".toCharArray();
        byte[] salt = generateRandomBytes(Encryption.SALT_LENGTH);
        byte[] iv = generateRandomBytes(IV_LENGTH);

        byte[] encrypted = encryptWithAEAD(fileData, password, salt, iv, DEFAULT_ITERATIONS);

        // Truncate header (remove last 10 bytes from beginning area)
        int truncateAt = getHeaderLength() - 10;
        byte[] truncated = java.util.Arrays.copyOf(encrypted, Math.max(0, truncateAt));

        assertDecryptionFails(truncated, password, "Truncated header should cause parsing to fail");
    }

    @Test
    public void testAEADWrongPasswordDetected() throws Exception {
        byte[] fileData = "Wrong password test".getBytes();
        char[] correctPassword = "correct".toCharArray();
        char[] wrongPassword = "incorrect".toCharArray();
        byte[] salt = generateRandomBytes(Encryption.SALT_LENGTH);
        byte[] iv = generateRandomBytes(IV_LENGTH);

        byte[] encrypted = encryptWithAEAD(fileData, correctPassword, salt, iv, DEFAULT_ITERATIONS);

        assertDecryptionFails(encrypted, wrongPassword, "Wrong password should not decrypt successfully");
    }

    @Test
    public void testAEADTruncatedCiphertextDetected() throws Exception {
        byte[] fileData = "Truncated ciphertext test data which is longer".getBytes();
        char[] password = "truncate".toCharArray();
        byte[] salt = generateRandomBytes(Encryption.SALT_LENGTH);
        byte[] iv = generateRandomBytes(IV_LENGTH);

        byte[] encrypted = encryptWithAEAD(fileData, password, salt, iv, DEFAULT_ITERATIONS);

        // Truncate half of the ciphertext
        int headerLen = getHeaderLength();
        int ciphertextLen = encrypted.length - headerLen;
        int truncatedLen = headerLen + (ciphertextLen / 2);
        byte[] truncated = java.util.Arrays.copyOf(encrypted, truncatedLen);

        assertDecryptionFails(truncated, password, "Truncated ciphertext should not decrypt successfully");
    }

    @Test
    public void testAEADEmptyPasswordDetected() throws Exception {
        byte[] fileData = "Empty password test".getBytes();
        char[] correctPassword = "notempty".toCharArray();
        char[] emptyPassword = new char[0];
        byte[] salt = generateRandomBytes(Encryption.SALT_LENGTH);
        byte[] iv = generateRandomBytes(IV_LENGTH);

        byte[] encrypted = encryptWithAEAD(fileData, correctPassword, salt, iv, DEFAULT_ITERATIONS);

        assertDecryptionFails(encrypted, emptyPassword, "Empty password should not decrypt data encrypted with non-empty password");
    }

    @Test
    public void testAEADFlippedBitInHeaderDetected() throws Exception {
        byte[] fileData = "Header bit flip test".getBytes();
        char[] password = "headertest".toCharArray();
        byte[] salt = generateRandomBytes(Encryption.SALT_LENGTH);
        byte[] iv = generateRandomBytes(IV_LENGTH);

        byte[] encrypted = encryptWithAEAD(fileData, password, salt, iv, DEFAULT_ITERATIONS);

        // Flip a bit in the salt (part of AAD)
        encrypted[5] ^= 0x01;

        assertDecryptionFails(encrypted, password, "Flipped bit in header (AAD) should fail authentication");
    }

    @Test
    public void testAEADMalformedIterationFlagsDetected() throws Exception {
        byte[] fileData = "Malformed flags test".getBytes();
        char[] password = "flags".toCharArray();
        byte[] salt = generateRandomBytes(Encryption.SALT_LENGTH);
        byte[] iv = generateRandomBytes(IV_LENGTH);

        // Use an intentionally malformed iteration value
        int storedIteration = 0x7FFFFFFF; // unlikely valid flag combination

        byte[] versionBytes = Encryption.toByteArray(Encryption.ENCRYPTION_VERSION_5);
        byte[] iterationBytes = Encryption.toByteArray(storedIteration);

        // Derive key with PBKDF2
        SecretKeyFactory skf = SecretKeyFactory.getInstance("PBKDF2withHmacSHA512");
        PBEKeySpec spec = new PBEKeySpec(password, salt, 1000, 256);
        SecretKey tmp = skf.generateSecret(spec);
        SecretKey secretKey = new SecretKeySpec(tmp.getEncoded(), "ChaCha20");

        ByteArrayOutputStream plaintextBuffer = new ByteArrayOutputStream();
        plaintextBuffer.write(("\n{}\n").getBytes());
        SectionWriter sw = new SectionWriter(plaintextBuffer);
        sw.writeFileSection(new ByteArrayInputStream(fileData), fileData.length);
        sw.writeEndMarker();
        byte[] plaintext = plaintextBuffer.toByteArray();

        Cipher cipher = Cipher.getInstance("ChaCha20-Poly1305", "BC");
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, new IvParameterSpec(iv));

        byte[] aad = buildAAD(versionBytes, salt, iv, iterationBytes);
        cipher.updateAAD(aad);

        byte[] ciphertext = cipher.doFinal(plaintext);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(versionBytes);
        out.write(salt);
        out.write(iv);
        out.write(iterationBytes);
        out.write(ciphertext);

        try {
            Encryption.Streams streams = Encryption.getCipherInputStream(
                new ByteArrayInputStream(out.toByteArray()), 
                password, 
                false, 
                Encryption.ENCRYPTION_VERSION_5
            );
            streams.getFileBytes();
            streams.close();
            fail("Malformed iteration flags should cause decryption/parsing to fail");
        } catch (UnsatisfiedLinkError | NoClassDefFoundError | ExceptionInInitializerError e) {
            // Skip if native libs missing (Argon2)
            org.junit.Assume.assumeTrue("Skipping: missing native library", false);
        } catch (Exception e) {
            // Expected: authentication/parsing failure
            assertNotNull(e);
        }
    }
}
