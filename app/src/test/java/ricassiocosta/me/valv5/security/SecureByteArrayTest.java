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
}
