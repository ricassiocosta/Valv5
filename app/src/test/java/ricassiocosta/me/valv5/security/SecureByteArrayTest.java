package ricassiocosta.me.valv5.security;

import org.junit.Test;

import static org.junit.Assert.*;

public class SecureByteArrayTest {

    // ==================== Basic functionality ====================

    @Test
    public void testCopyOfCreatesIndependentCopy() {
        byte[] src = new byte[]{1, 2, 3, 4, 5};
        SecureByteArray s = SecureByteArray.copyOf(src);

        assertEquals(5, s.length());

        // Verify content matches
        byte[] dest = new byte[5];
        s.copyTo(dest, 0, 0, 5);
        assertArrayEquals(src, dest);

        // Verify it's a copy, not the same reference
        src[0] = 99;
        byte[] check = new byte[5];
        s.copyTo(check, 0, 0, 5);
        assertEquals(1, check[0]); // Should still be 1, not 99

        s.wipe();
    }

    @Test
    public void testWrapConstructorUsesOriginalArray() {
        byte[] src = new byte[]{10, 20, 30};
        SecureByteArray s = new SecureByteArray(src);

        // Modifying src should affect getData()
        src[0] = 99;
        assertEquals(99, s.getData()[0]);

        s.wipe();
        // Original array should be wiped
        assertEquals(0, src[0]);
        assertEquals(0, src[1]);
        assertEquals(0, src[2]);
    }

    @Test
    public void testSizeConstructorCreatesZeroFilledArray() {
        SecureByteArray s = new SecureByteArray(10);
        assertEquals(10, s.length());

        byte[] data = s.getData();
        for (byte b : data) {
            assertEquals(0, b);
        }
        s.wipe();
    }

    // ==================== Wipe behavior ====================

    @Test
    public void testWipeActuallyZerosData() {
        byte[] src = new byte[]{(byte) 0xFF, (byte) 0xAA, (byte) 0x55, (byte) 0x12};
        SecureByteArray s = new SecureByteArray(src); // wrap, not copy

        // Verify non-zero before wipe
        boolean hasNonZero = false;
        for (byte b : src) {
            if (b != 0) hasNonZero = true;
        }
        assertTrue("Source should have non-zero bytes", hasNonZero);

        s.wipe();

        // Verify ALL bytes are zero after wipe
        for (int i = 0; i < src.length; i++) {
            assertEquals("Byte at index " + i + " should be zero", 0, src[i]);
        }
        assertTrue(s.isWiped());
    }

    @Test
    public void testParanoidWipeZerosData() {
        byte[] src = new byte[]{(byte) 0xFF, (byte) 0xAA, (byte) 0x55, (byte) 0x12};
        SecureByteArray s = new SecureByteArray(src, true); // paranoid mode, wrap

        s.wipe();

        // Verify data is zeroed after paranoid wipe
        for (int i = 0; i < src.length; i++) {
            assertEquals("Byte at index " + i + " should be zero after paranoid wipe", 0, src[i]);
        }
        assertTrue(s.isWiped());
    }

    @Test
    public void testDoubleWipeIsSafe() {
        byte[] src = new byte[]{1, 2, 3};
        SecureByteArray s = SecureByteArray.copyOf(src);

        s.wipe();
        assertTrue(s.isWiped());

        // Second wipe should not throw
        s.wipe();
        assertTrue(s.isWiped());
    }

    // ==================== Error cases ====================

    @Test
    public void testNegativeSizeThrows() {
        try {
            new SecureByteArray(-1);
            fail("Should throw IllegalArgumentException for negative size");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("negative"));
        }
    }

    @Test
    public void testGetDataAfterWipeThrows() {
        SecureByteArray s = SecureByteArray.copyOf(new byte[]{1, 2, 3});
        s.wipe();

        try {
            s.getData();
            fail("Should throw IllegalStateException after wipe");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage().contains("wiped"));
        }
    }

    @Test
    public void testCopyToAfterWipeThrows() {
        SecureByteArray s = SecureByteArray.copyOf(new byte[]{1, 2, 3});
        s.wipe();

        try {
            s.copyTo(new byte[3], 0, 0, 3);
            fail("Should throw IllegalStateException after wipe");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage().contains("wiped"));
        }
    }

    @Test
    public void testCopyFromAfterWipeThrows() {
        SecureByteArray s = new SecureByteArray(5);
        s.wipe();

        try {
            s.copyFrom(new byte[]{1, 2, 3}, 0, 0, 3);
            fail("Should throw IllegalStateException after wipe");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage().contains("wiped"));
        }
    }

    // ==================== Copy operations ====================

    @Test
    public void testCopyFromWorks() {
        SecureByteArray s = new SecureByteArray(5);
        byte[] src = new byte[]{10, 20, 30};

        s.copyFrom(src, 0, 1, 3);

        byte[] result = new byte[5];
        s.copyTo(result, 0, 0, 5);

        assertEquals(0, result[0]);
        assertEquals(10, result[1]);
        assertEquals(20, result[2]);
        assertEquals(30, result[3]);
        assertEquals(0, result[4]);

        s.wipe();
    }

    @Test
    public void testCopyToWithOffsets() {
        SecureByteArray s = SecureByteArray.copyOf(new byte[]{1, 2, 3, 4, 5});
        byte[] dest = new byte[10];

        s.copyTo(dest, 1, 3, 3); // copy bytes[1..3] to dest[3..5]

        assertEquals(0, dest[0]);
        assertEquals(0, dest[1]);
        assertEquals(0, dest[2]);
        assertEquals(2, dest[3]);
        assertEquals(3, dest[4]);
        assertEquals(4, dest[5]);
        assertEquals(0, dest[6]);

        s.wipe();
    }

    // ==================== AutoCloseable ====================

    @Test
    public void testAutoCloseableWipes() {
        byte[] src = new byte[]{1, 2, 3};

        try (SecureByteArray s = new SecureByteArray(src)) {
            assertEquals(3, s.length());
        }

        // After try-with-resources, should be wiped
        for (byte b : src) {
            assertEquals(0, b);
        }
    }

    // ==================== Zero-length edge case ====================

    @Test
    public void testZeroLengthArray() {
        SecureByteArray s = new SecureByteArray(0);
        assertEquals(0, s.length());
        assertFalse(s.isWiped());

        s.wipe();
        assertTrue(s.isWiped());
    }
}
