package ricassiocosta.me.valv5.encryption;

import org.junit.Test;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import static org.junit.Assert.*;

public class EncryptionUnitTest {

    @Test
    public void testToByteArrayAndFromByteArray() {
        int value = 0x12345678;
        byte[] bytes = Encryption.toByteArray(value);
        int round = Encryption.fromByteArray(bytes);
        assertEquals(value, round);
    }

    @Test
    public void testToBytesAndToCharsRoundtrip() {
        char[] original = "s3cr3tP@ss".toCharArray();
        byte[] bytes = Encryption.toBytes(original);
        char[] round = Encryption.toChars(bytes);
        assertArrayEquals(original, round);
    }

    @Test
    public void testGenerateSecureSalt() {
        byte[] salt = Encryption.generateSecureSalt(16);
        assertNotNull(salt);
        assertEquals(16, salt.length);
        boolean allZero = true;
        for (byte b : salt) {
            if (b != 0) { allZero = false; break; }
        }
        assertFalse("Salt should not be all zero", allZero);
    }

    @Test
    public void testCalculateFileHashLength() throws Exception {
        byte[] keyBytes = new byte[32];
        for (int i = 0; i < keyBytes.length; i++) keyBytes[i] = (byte) i;
        SecretKey key = new SecretKeySpec(keyBytes, "ChaCha20");
        String hash = Encryption.calculateFileHash("somefile.name", key);
        assertNotNull(hash);
        assertEquals(64, hash.length()); // SHA-256 hex length
        // hex chars
        assertTrue(hash.matches("[0-9a-fA-F]{64}"));
    }

    @Test
    public void testGetFileTypeFromMime() {
        assertEquals(ricassiocosta.me.valv5.data.FileType.TYPE_IMAGE, Encryption.getFileTypeFromMime(null));
        assertEquals(ricassiocosta.me.valv5.data.FileType.TYPE_GIF, Encryption.getFileTypeFromMime("image/gif"));
        assertEquals(ricassiocosta.me.valv5.data.FileType.TYPE_IMAGE, Encryption.getFileTypeFromMime("image/png"));
        assertEquals(ricassiocosta.me.valv5.data.FileType.TYPE_TEXT, Encryption.getFileTypeFromMime("text/plain"));
        assertEquals(ricassiocosta.me.valv5.data.FileType.TYPE_VIDEO, Encryption.getFileTypeFromMime("video/mp4"));
    }

    @Test
    public void testLooksLikeEncryptedFolder() {
        assertFalse(Encryption.looksLikeEncryptedFolder("shortname"));
        // Build a base64url-like string of length 60 with allowed chars
        StringBuilder sb = new StringBuilder();
        String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
        while (sb.length() < 60) sb.append(chars);
        String candidate = sb.substring(0, 60);
        assertTrue(Encryption.looksLikeEncryptedFolder(candidate));
    }

    @Test
    public void testIsAnimatedWebpFalse() throws Exception {
        // Provide non-webp data
        byte[] data = new byte[]{0x00, 0x01, 0x02, 0x03};
        java.io.InputStream is = new java.io.ByteArrayInputStream(data);
        assertFalse(Encryption.isAnimatedWebp(is));
    }

    // ==================== Index-related tests ====================

    @Test
    public void testEncryptionVersion5Constant() {
        // Verify the encryption version constant used for index files
        assertEquals(5, Encryption.ENCRYPTION_VERSION_5);
    }

    @Test
    public void testContentTypeEnumValues() {
        // Verify all ContentType enum values exist
        Encryption.ContentType[] values = Encryption.ContentType.values();
        assertNotNull(values);
        assertEquals(3, values.length);
        
        // Check expected values
        assertNotNull(Encryption.ContentType.FILE);
        assertNotNull(Encryption.ContentType.THUMBNAIL);
        assertNotNull(Encryption.ContentType.NOTE);
    }

    @Test
    public void testContentTypeValueOf() {
        assertEquals(Encryption.ContentType.FILE, Encryption.ContentType.valueOf("FILE"));
        assertEquals(Encryption.ContentType.THUMBNAIL, Encryption.ContentType.valueOf("THUMBNAIL"));
        assertEquals(Encryption.ContentType.NOTE, Encryption.ContentType.valueOf("NOTE"));
    }

    @Test
    public void testContentTypeIntValues() {
        assertEquals(0, Encryption.ContentType.FILE.value);
        assertEquals(1, Encryption.ContentType.THUMBNAIL.value);
        assertEquals(2, Encryption.ContentType.NOTE.value);
    }

    @Test
    public void testSaltLengthConstant() {
        // Salt length used in encryption
        assertEquals(16, Encryption.SALT_LENGTH);
    }

    @Test
    public void testDirHashLengthConstant() {
        // Directory hash length constant
        assertEquals(8, Encryption.DIR_HASH_LENGTH);
    }

    @Test
    public void testBiometricsAliasConstant() {
        // Biometrics key alias constant
        assertEquals("vault_key", Encryption.BIOMETRICS_ALIAS);
    }
}
