package ricassiocosta.me.valv5.data;

import org.junit.Test;

import static org.junit.Assert.*;

public class DirHashTest {

    // ==================== Basic functionality ====================

    @Test
    public void testRecordGetters() {
        byte[] salt = new byte[]{1, 2, 3, 4};
        byte[] hash = new byte[]{9, 8, 7, 6, 5};

        DirHash dh = new DirHash(salt, hash);

        assertSame(salt, dh.salt());
        assertSame(hash, dh.hash());
    }

    @Test
    public void testClearZerosArrays() {
        byte[] salt = new byte[]{1, 2, 3, 4};
        byte[] hash = new byte[]{9, 8, 7, 6};

        DirHash dh = new DirHash(salt, hash);
        dh.clear();

        for (int i = 0; i < salt.length; i++) {
            assertEquals("Salt byte at index " + i + " should be zero", 0, salt[i]);
        }
        for (int i = 0; i < hash.length; i++) {
            assertEquals("Hash byte at index " + i + " should be zero", 0, hash[i]);
        }
    }

    @Test
    public void testClearWithDifferentSizes() {
        byte[] salt = new byte[16]; // typical salt size
        byte[] hash = new byte[32]; // typical hash size

        // Fill with non-zero
        for (int i = 0; i < salt.length; i++) salt[i] = (byte) (i + 1);
        for (int i = 0; i < hash.length; i++) hash[i] = (byte) (i + 100);

        DirHash dh = new DirHash(salt, hash);
        dh.clear();

        for (byte b : salt) assertEquals(0, b);
        for (byte b : hash) assertEquals(0, b);
    }

    // ==================== Null handling ====================

    @Test
    public void testClearWithNullSaltDoesNotThrow() {
        byte[] hash = new byte[]{1, 2, 3};
        DirHash dh = new DirHash(null, hash);

        dh.clear(); // Should not throw NullPointerException

        for (byte b : hash) assertEquals(0, b);
    }

    @Test
    public void testClearWithNullHashDoesNotThrow() {
        byte[] salt = new byte[]{1, 2, 3};
        DirHash dh = new DirHash(salt, null);

        dh.clear(); // Should not throw NullPointerException

        for (byte b : salt) assertEquals(0, b);
    }

    @Test
    public void testClearWithBothNullDoesNotThrow() {
        DirHash dh = new DirHash(null, null);
        dh.clear(); // Should not throw
    }

    // ==================== Empty arrays ====================

    @Test
    public void testClearWithEmptyArrays() {
        byte[] salt = new byte[0];
        byte[] hash = new byte[0];

        DirHash dh = new DirHash(salt, hash);
        dh.clear(); // Should not throw

        assertEquals(0, salt.length);
        assertEquals(0, hash.length);
    }

    // ==================== Record equality ====================

    @Test
    public void testRecordEquality() {
        byte[] salt1 = new byte[]{1, 2, 3};
        byte[] hash1 = new byte[]{4, 5, 6};
        byte[] salt2 = new byte[]{1, 2, 3};
        byte[] hash2 = new byte[]{4, 5, 6};

        DirHash dh1 = new DirHash(salt1, hash1);
        DirHash dh2 = new DirHash(salt2, hash2);

        // Records with arrays compare by reference, not content
        assertNotEquals(dh1, dh2); // Different array instances
        assertEquals(dh1, dh1);    // Same instance
    }

    // ==================== Multiple clears ====================

    @Test
    public void testDoubleClearIsSafe() {
        byte[] salt = new byte[]{1, 2, 3};
        byte[] hash = new byte[]{4, 5, 6};

        DirHash dh = new DirHash(salt, hash);
        dh.clear();
        dh.clear(); // Should not throw

        for (byte b : salt) assertEquals(0, b);
        for (byte b : hash) assertEquals(0, b);
    }
}
