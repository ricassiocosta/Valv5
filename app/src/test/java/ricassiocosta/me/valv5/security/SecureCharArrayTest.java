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
}
