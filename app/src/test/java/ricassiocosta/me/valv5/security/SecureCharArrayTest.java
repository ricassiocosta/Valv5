package ricassiocosta.me.valv5.security;

import org.junit.Test;

import static org.junit.Assert.*;

public class SecureCharArrayTest {

    @Test
    public void testCopyOfAndToBytes() {
        char[] src = "password".toCharArray();
        SecureCharArray s = SecureCharArray.copyOf(src);
        assertEquals(src.length, s.length());
        SecureByteArray bytes = s.toBytes();
        assertEquals(src.length, bytes.length());
        s.wipe();
        assertTrue(s.isWiped());
        try {
            s.getData();
            fail("Should throw after wipe");
        } catch (IllegalStateException ignored) {}
        bytes.wipe();
    }

    @Test
    public void testToBytesContentMatchesUtf8() {
        char[] src = "åßç漢字".toCharArray();
        SecureCharArray s = SecureCharArray.copyOf(src);
        SecureByteArray bytes = s.toBytes();
        byte[] out = new byte[bytes.length()];
        bytes.copyTo(out, 0, 0, out.length);
        // Compare with standard UTF-8 encoding
        byte[] expected = new String(src).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertArrayEquals(expected, out);
        bytes.wipe();
        s.wipe();
    }

    @Test
    public void testGetDataAfterWipeThrows() {
        char[] src = "abc".toCharArray();
        SecureCharArray s = new SecureCharArray(src);
        s.wipe();
        try {
            s.getData();
            fail("Should throw after wipe");
        } catch (IllegalStateException ignored) {}
    }
}
