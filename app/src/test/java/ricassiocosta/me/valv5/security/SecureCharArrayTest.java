package ricassiocosta.me.valv5.security;

import org.junit.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.Assert.*;

public class SecureCharArrayTest {

    // ==================== Basic functionality ====================

    @Test
    public void testCopyOfCreatesIndependentCopy() {
        char[] src = "password".toCharArray();
        SecureCharArray s = SecureCharArray.copyOf(src);

        assertEquals(src.length, s.length());

        // Verify it's a copy
        src[0] = 'X';
        assertEquals('p', s.getData()[0]); // Should still be 'p'

        s.wipe();
    }

    @Test
    public void testWrapConstructorUsesOriginalArray() {
        char[] src = "secret".toCharArray();
        SecureCharArray s = new SecureCharArray(src);

        // Modifying src should affect getData()
        src[0] = 'Z';
        assertEquals('Z', s.getData()[0]);

        s.wipe();
        // Original array should be wiped
        for (char c : src) {
            assertEquals('\0', c);
        }
    }

    @Test
    public void testSizeConstructorCreatesNullFilledArray() {
        SecureCharArray s = new SecureCharArray(10);
        assertEquals(10, s.length());

        char[] data = s.getData();
        for (char c : data) {
            assertEquals('\0', c);
        }
        s.wipe();
    }

    // ==================== toBytes conversion ====================

    @Test
    public void testToBytesContentMatchesUtf8() {
        char[] src = "åßç漢字".toCharArray();
        SecureCharArray s = SecureCharArray.copyOf(src);
        SecureByteArray bytes = s.toBytes();

        byte[] out = new byte[bytes.length()];
        bytes.copyTo(out, 0, 0, out.length);

        // Compare with standard UTF-8 encoding
        byte[] expected = new String(src).getBytes(StandardCharsets.UTF_8);
        assertArrayEquals(expected, out);

        bytes.wipe();
        s.wipe();
    }

    @Test
    public void testToBytesWithAscii() {
        char[] src = "hello123".toCharArray();
        SecureCharArray s = SecureCharArray.copyOf(src);
        SecureByteArray bytes = s.toBytes();

        assertEquals(8, bytes.length()); // ASCII chars = 1 byte each

        byte[] out = new byte[8];
        bytes.copyTo(out, 0, 0, 8);
        assertArrayEquals("hello123".getBytes(StandardCharsets.UTF_8), out);

        bytes.wipe();
        s.wipe();
    }

    @Test
    public void testToBytesAfterWipeThrows() {
        SecureCharArray s = SecureCharArray.copyOf("test".toCharArray());
        s.wipe();

        try {
            s.toBytes();
            fail("Should throw IllegalStateException after wipe");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage().contains("wiped"));
        }
    }

    // ==================== Wipe behavior ====================

    @Test
    public void testWipeActuallyZerosData() {
        char[] src = new char[]{'a', 'b', 'c', 'd'};
        SecureCharArray s = new SecureCharArray(src); // wrap

        s.wipe();

        // Verify ALL chars are null after wipe
        for (int i = 0; i < src.length; i++) {
            assertEquals("Char at index " + i + " should be null", '\0', src[i]);
        }
        assertTrue(s.isWiped());
    }

    @Test
    public void testParanoidWipeZerosData() {
        char[] src = new char[]{'X', 'Y', 'Z'};
        SecureCharArray s = new SecureCharArray(src, true); // paranoid mode

        s.wipe();

        for (int i = 0; i < src.length; i++) {
            assertEquals("Char at index " + i + " should be null after paranoid wipe", '\0', src[i]);
        }
        assertTrue(s.isWiped());
    }

    @Test
    public void testDoubleWipeIsSafe() {
        SecureCharArray s = SecureCharArray.copyOf("test".toCharArray());
        s.wipe();
        s.wipe(); // Should not throw
        assertTrue(s.isWiped());
    }

    // ==================== Error cases ====================

    @Test
    public void testNegativeSizeThrows() {
        try {
            new SecureCharArray(-1);
            fail("Should throw IllegalArgumentException for negative size");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("negative"));
        }
    }

    @Test
    public void testGetDataAfterWipeThrows() {
        SecureCharArray s = SecureCharArray.copyOf("abc".toCharArray());
        s.wipe();

        try {
            s.getData();
            fail("Should throw IllegalStateException after wipe");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage().contains("wiped"));
        }
    }

    // ==================== AutoCloseable ====================

    @Test
    public void testAutoCloseableWipes() {
        char[] src = "secret".toCharArray();

        try (SecureCharArray s = new SecureCharArray(src)) {
            assertEquals(6, s.length());
        }

        // After try-with-resources, should be wiped
        for (char c : src) {
            assertEquals('\0', c);
        }
    }

    // ==================== Edge cases ====================

    @Test
    public void testZeroLengthArray() {
        SecureCharArray s = new SecureCharArray(0);
        assertEquals(0, s.length());
        assertFalse(s.isWiped());

        s.wipe();
        assertTrue(s.isWiped());
    }

    @Test
    public void testEmptyStringToBytes() {
        SecureCharArray s = SecureCharArray.copyOf(new char[0]);
        SecureByteArray bytes = s.toBytes();

        assertEquals(0, bytes.length());

        bytes.wipe();
        s.wipe();
    }
}
