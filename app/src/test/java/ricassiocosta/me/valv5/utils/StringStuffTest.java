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

    @org.junit.Test
    public void testBytesToReadableStringBoundaries() {
        // just below 1 kB
        assertEquals("999.00 B", StringStuff.bytesToReadableString(999));
        // exactly 1 kB
        assertEquals("1.00 kB", StringStuff.bytesToReadableString(1000));
        // exactly 1 MB
        assertEquals("1.00 MB", StringStuff.bytesToReadableString(1_000_000));
    }
}
