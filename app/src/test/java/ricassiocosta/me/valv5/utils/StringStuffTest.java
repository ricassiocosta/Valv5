package ricassiocosta.me.valv5.utils;

import org.junit.Test;

import static org.junit.Assert.*;

public class StringStuffTest {

    @Test
    public void testGetRandomFileName() {
        String name = StringStuff.getRandomFileName();
        assertNotNull(name);
        assertEquals(32, name.length());
        assertTrue(name.matches("[a-zA-Z0-9]{32}"));
    }

    @Test
    public void testBytesToReadableString() {
        assertEquals("500.00 B", StringStuff.bytesToReadableString(500));
        assertEquals("1.00 MB", StringStuff.bytesToReadableString(1_000_000));
    }
}
