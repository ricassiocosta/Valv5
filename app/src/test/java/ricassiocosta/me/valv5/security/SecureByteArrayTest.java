package ricassiocosta.me.valv5.security;

import org.junit.Test;

import static org.junit.Assert.*;

public class SecureByteArrayTest {

    @Test
    public void testCopyAndWipe() {
        byte[] src = new byte[]{1,2,3,4,5};
        SecureByteArray s = SecureByteArray.copyOf(src);
        assertEquals(5, s.length());
        byte[] dest = new byte[5];
        s.copyTo(dest, 0, 0, 5);
        assertArrayEquals(src, dest);
        s.wipe();
        assertTrue(s.isWiped());
        try {
            s.getData();
            fail("Should throw after wipe");
        } catch (IllegalStateException ignored) {}
    }

    @Test
    public void testNegativeSizeThrows() {
        try {
            new SecureByteArray(-1);
            fail("Should throw for negative size");
        } catch (IllegalArgumentException ignored) {
        }
    }

    @Test
    public void testCopyFromAfterWipeThrows() {
        byte[] src = new byte[]{1,2,3,4,5};
        SecureByteArray s = SecureByteArray.copyOf(src);
        s.wipe();
        try {
            s.copyTo(new byte[5], 0, 0, 5);
            fail("Should throw after wipe");
        } catch (IllegalStateException ignored) {}
    }

    @Test
    public void testParanoidWipeDoesNotThrow() {
        byte[] src = new byte[]{7,7,7,7};
        SecureByteArray s = SecureByteArray.copyOf(src, true);
        // Fill with non-zero
        byte[] dest = new byte[4];
        s.copyTo(dest, 0, 0, 4);
        // Wipe in paranoid mode
        s.wipe();
        for (byte b : dest) {
            // dest is a copy and not wiped, but ensure no exceptions thrown
        }
        assertTrue(s.isWiped());
    }
}
