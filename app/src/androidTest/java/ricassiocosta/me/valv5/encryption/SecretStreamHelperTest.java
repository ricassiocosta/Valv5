package ricassiocosta.me.valv5.encryption;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.security.SecureRandom;
import java.util.Arrays;

import ricassiocosta.me.valv5.exception.InvalidPasswordException;

import static org.junit.Assert.*;

/**
 * Instrumentation tests for SecretStreamHelper (libsodium secretstream).
 * 
 * These tests MUST run on Android device/emulator because:
 * - lazysodium-android requires native JNI libraries
 * - Native libs (libsodium) are only available in Android runtime
 * 
 * Tests verify:
 * - Round-trip encryption/decryption works correctly
 * - Tampering is detected (authentication)
 * - Truncation is detected
 * - Wrong key is rejected
 * - Empty files are handled
 * - Large files work correctly
 * - Chunk boundaries are handled properly
 */
@RunWith(AndroidJUnit4.class)
public class SecretStreamHelperTest {

    private static final int KEY_BYTES = SecretStreamHelper.KEY_BYTES; // 32
    private static final int HEADER_BYTES = SecretStreamHelper.HEADER_BYTES; // 24
    private static final int A_BYTES = SecretStreamHelper.A_BYTES; // 17

    // ==================== Helper Methods ====================

    private byte[] generateKey() {
        byte[] key = new byte[KEY_BYTES];
        new SecureRandom().nextBytes(key);
        return key;
    }

    private byte[] generateRandomData(int size) {
        byte[] data = new byte[size];
        new SecureRandom().nextBytes(data);
        return data;
    }

    private byte[] encrypt(byte[] key, byte[] plaintext) throws Exception {
        ByteArrayInputStream input = new ByteArrayInputStream(plaintext);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        SecretStreamHelper.encrypt(key, input, output);
        return output.toByteArray();
    }

    private byte[] decrypt(byte[] key, byte[] ciphertext) throws Exception {
        ByteArrayInputStream input = new ByteArrayInputStream(ciphertext);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        SecretStreamHelper.decrypt(key, input, output);
        return output.toByteArray();
    }

    private void assertDecryptionFails(byte[] key, byte[] ciphertext, String message) {
        try {
            decrypt(key, ciphertext);
            fail(message);
        } catch (InvalidPasswordException e) {
            // Expected
            assertNotNull(e.getMessage());
        } catch (Exception e) {
            // Also acceptable for corrupted data
            assertNotNull(e);
        }
    }

    // ==================== Round-trip Tests ====================

    @Test
    public void testRoundTripSmallData() throws Exception {
        byte[] key = generateKey();
        byte[] plaintext = "Hello, SecretStream!".getBytes();

        byte[] ciphertext = encrypt(key, plaintext);
        byte[] decrypted = decrypt(key, ciphertext);

        assertArrayEquals("Decrypted should match original", plaintext, decrypted);
    }

    @Test
    public void testRoundTripEmptyData() throws Exception {
        byte[] key = generateKey();
        byte[] plaintext = new byte[0];

        byte[] ciphertext = encrypt(key, plaintext);
        byte[] decrypted = decrypt(key, ciphertext);

        assertArrayEquals("Empty data should round-trip", plaintext, decrypted);
        assertEquals("Empty ciphertext should be header + one A_BYTES chunk", 
                     HEADER_BYTES + A_BYTES, ciphertext.length);
    }

    @Test
    public void testRoundTripSingleByte() throws Exception {
        byte[] key = generateKey();
        byte[] plaintext = new byte[]{0x42};

        byte[] ciphertext = encrypt(key, plaintext);
        byte[] decrypted = decrypt(key, ciphertext);

        assertArrayEquals(plaintext, decrypted);
    }

    @Test
    public void testRoundTripExactlyOneChunk() throws Exception {
        byte[] key = generateKey();
        int chunkSize = SecretStreamHelper.DEFAULT_CHUNK_SIZE; // 64KB
        byte[] plaintext = generateRandomData(chunkSize);

        byte[] ciphertext = encrypt(key, plaintext);
        byte[] decrypted = decrypt(key, ciphertext);

        assertArrayEquals(plaintext, decrypted);
    }

    @Test
    public void testRoundTripMultipleChunks() throws Exception {
        byte[] key = generateKey();
        int chunkSize = SecretStreamHelper.DEFAULT_CHUNK_SIZE;
        byte[] plaintext = generateRandomData(chunkSize * 3 + 1234); // 3+ chunks

        byte[] ciphertext = encrypt(key, plaintext);
        byte[] decrypted = decrypt(key, ciphertext);

        assertArrayEquals(plaintext, decrypted);
    }

    @Test
    public void testRoundTripLargeData() throws Exception {
        byte[] key = generateKey();
        // 1 MB of data
        byte[] plaintext = generateRandomData(1024 * 1024);

        byte[] ciphertext = encrypt(key, plaintext);
        byte[] decrypted = decrypt(key, ciphertext);

        assertArrayEquals(plaintext, decrypted);
    }

    // ==================== Ciphertext Properties Tests ====================

    @Test
    public void testCiphertextIsLargerThanPlaintext() throws Exception {
        byte[] key = generateKey();
        byte[] plaintext = generateRandomData(1000);

        byte[] ciphertext = encrypt(key, plaintext);

        assertTrue("Ciphertext should be larger than plaintext", 
                   ciphertext.length > plaintext.length);
        // At minimum: HEADER_BYTES + A_BYTES overhead
        assertTrue("Should have at least header + tag", 
                   ciphertext.length >= HEADER_BYTES + A_BYTES);
    }

    @Test
    public void testSameInputProducesDifferentCiphertext() throws Exception {
        byte[] key = generateKey();
        byte[] plaintext = "Repeat me".getBytes();

        byte[] ciphertext1 = encrypt(key, plaintext);
        byte[] ciphertext2 = encrypt(key, plaintext);

        // SecretStream uses random header (nonce state)
        assertFalse("Same plaintext should produce different ciphertext", 
                    Arrays.equals(ciphertext1, ciphertext2));
    }

    @Test
    public void testCiphertextSizeCalculation() throws Exception {
        byte[] key = generateKey();
        int plainSize = 100000;
        int chunkSize = SecretStreamHelper.DEFAULT_CHUNK_SIZE;
        byte[] plaintext = generateRandomData(plainSize);

        long expectedSize = SecretStreamHelper.calculateCiphertextSize(plainSize, chunkSize);
        byte[] ciphertext = encrypt(key, plaintext);

        assertEquals("Ciphertext size should match calculation", expectedSize, ciphertext.length);
    }

    // ==================== Authentication Tests ====================

    @Test
    public void testWrongKeyFails() throws Exception {
        byte[] correctKey = generateKey();
        byte[] wrongKey = generateKey();
        byte[] plaintext = "Secret data".getBytes();

        byte[] ciphertext = encrypt(correctKey, plaintext);

        assertDecryptionFails(wrongKey, ciphertext, "Wrong key should fail decryption");
    }

    @Test
    public void testTamperedCiphertextFails() throws Exception {
        byte[] key = generateKey();
        byte[] plaintext = "Tamper-proof data".getBytes();

        byte[] ciphertext = encrypt(key, plaintext);

        // Flip a bit in the ciphertext (after header)
        int tamperIndex = HEADER_BYTES + 5;
        if (ciphertext.length > tamperIndex) {
            ciphertext[tamperIndex] ^= 0x01;
        }

        assertDecryptionFails(key, ciphertext, "Tampered ciphertext should fail");
    }

    @Test
    public void testTamperedHeaderFails() throws Exception {
        byte[] key = generateKey();
        byte[] plaintext = "Header integrity test".getBytes();

        byte[] ciphertext = encrypt(key, plaintext);

        // Flip a bit in the header
        ciphertext[5] ^= 0x01;

        assertDecryptionFails(key, ciphertext, "Tampered header should fail");
    }

    @Test
    public void testTruncatedCiphertextFails() throws Exception {
        byte[] key = generateKey();
        byte[] plaintext = generateRandomData(10000);

        byte[] ciphertext = encrypt(key, plaintext);

        // Truncate half of the ciphertext
        byte[] truncated = Arrays.copyOf(ciphertext, ciphertext.length / 2);

        assertDecryptionFails(key, truncated, "Truncated ciphertext should fail");
    }

    @Test
    public void testTruncatedHeaderFails() throws Exception {
        byte[] key = generateKey();
        byte[] plaintext = "Short".getBytes();

        byte[] ciphertext = encrypt(key, plaintext);

        // Only keep partial header
        byte[] truncated = Arrays.copyOf(ciphertext, HEADER_BYTES - 5);

        try {
            decrypt(key, truncated);
            fail("Truncated header should fail");
        } catch (Exception e) {
            // Expected
        }
    }

    @Test
    public void testAppendedDataFails() throws Exception {
        byte[] key = generateKey();
        byte[] plaintext = "Original data".getBytes();

        byte[] ciphertext = encrypt(key, plaintext);

        // Append extra bytes
        byte[] extended = new byte[ciphertext.length + 100];
        System.arraycopy(ciphertext, 0, extended, 0, ciphertext.length);

        // This might succeed (TAG_FINAL already seen) or fail - depends on implementation
        // Either way, the decrypted data should match original if it succeeds
        try {
            byte[] decrypted = decrypt(key, extended);
            assertArrayEquals("If decryption succeeds, data should match", plaintext, decrypted);
        } catch (Exception e) {
            // Also acceptable
        }
    }

    // ==================== Edge Cases ====================

    @Test
    public void testBinaryDataWithAllByteValues() throws Exception {
        byte[] key = generateKey();
        byte[] plaintext = new byte[256];
        for (int i = 0; i < 256; i++) {
            plaintext[i] = (byte) i;
        }

        byte[] ciphertext = encrypt(key, plaintext);
        byte[] decrypted = decrypt(key, ciphertext);

        assertArrayEquals("All byte values should round-trip", plaintext, decrypted);
    }

    @Test
    public void testChunkBoundaryData() throws Exception {
        byte[] key = generateKey();
        int chunkSize = SecretStreamHelper.DEFAULT_CHUNK_SIZE;

        // Test at exact chunk boundaries
        for (int offset : new int[]{-1, 0, 1}) {
            byte[] plaintext = generateRandomData(chunkSize + offset);
            byte[] ciphertext = encrypt(key, plaintext);
            byte[] decrypted = decrypt(key, ciphertext);

            assertArrayEquals("Chunk boundary " + offset + " should work", plaintext, decrypted);
        }
    }

    // ==================== Output Stream Wrapper Tests ====================

    @Test
    public void testOutputStreamRoundTrip() throws Exception {
        byte[] key = generateKey();
        byte[] plaintext = "Stream wrapper test".getBytes();

        ByteArrayOutputStream cipherOutput = new ByteArrayOutputStream();
        try (SecretStreamHelper.SecretStreamOutputStream sos = 
                new SecretStreamHelper.SecretStreamOutputStream(key, cipherOutput)) {
            sos.write(plaintext);
            sos.finish();
        }

        byte[] decrypted = decrypt(key, cipherOutput.toByteArray());
        assertArrayEquals(plaintext, decrypted);
    }

    @Test
    public void testOutputStreamMultipleWrites() throws Exception {
        byte[] key = generateKey();
        byte[] part1 = "Part 1".getBytes();
        byte[] part2 = " and Part 2".getBytes();
        byte[] part3 = " and Part 3".getBytes();

        ByteArrayOutputStream cipherOutput = new ByteArrayOutputStream();
        try (SecretStreamHelper.SecretStreamOutputStream sos = 
                new SecretStreamHelper.SecretStreamOutputStream(key, cipherOutput)) {
            sos.write(part1);
            sos.write(part2);
            sos.write(part3);
            sos.finish();
        }

        byte[] decrypted = decrypt(key, cipherOutput.toByteArray());

        ByteArrayOutputStream expected = new ByteArrayOutputStream();
        expected.write(part1);
        expected.write(part2);
        expected.write(part3);

        assertArrayEquals(expected.toByteArray(), decrypted);
    }
}
