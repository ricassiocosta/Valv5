package ricassiocosta.me.valv5.utils;

import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.*;

public class StringStuffTest {

    // ==================== getRandomFileName ====================

    @Test
    public void testGetRandomFileNameDefaultLength() {
        String name = StringStuff.getRandomFileName();

        assertNotNull(name);
        assertEquals(32, name.length());
        assertTrue("Should only contain alphanumeric chars", name.matches("[a-zA-Z0-9]{32}"));
    }

    @Test
    public void testGetRandomFileNameCustomLength() {
        String name10 = StringStuff.getRandomFileName(10);
        String name50 = StringStuff.getRandomFileName(50);
        String name1 = StringStuff.getRandomFileName(1);

        assertEquals(10, name10.length());
        assertEquals(50, name50.length());
        assertEquals(1, name1.length());

        assertTrue(name10.matches("[a-zA-Z0-9]{10}"));
        assertTrue(name50.matches("[a-zA-Z0-9]{50}"));
        assertTrue(name1.matches("[a-zA-Z0-9]{1}"));
    }

    @Test
    public void testGetRandomFileNameZeroLength() {
        String name = StringStuff.getRandomFileName(0);
        assertNotNull(name);
        assertEquals(0, name.length());
    }

    @Test
    public void testGetRandomFileNameUniqueness() {
        Set<String> generated = new HashSet<>();
        int count = 100;

        for (int i = 0; i < count; i++) {
            String name = StringStuff.getRandomFileName();
            generated.add(name);
        }

        // All generated names should be unique (collision extremely unlikely)
        assertEquals("Generated names should be unique", count, generated.size());
    }

    @Test
    public void testGetRandomFileNameDistribution() {
        // Verify that output contains both letters and digits
        boolean hasLower = false;
        boolean hasUpper = false;
        boolean hasDigit = false;

        // Generate several to increase chance of seeing all character types
        for (int i = 0; i < 10; i++) {
            String name = StringStuff.getRandomFileName();
            for (char c : name.toCharArray()) {
                if (c >= 'a' && c <= 'z') hasLower = true;
                if (c >= 'A' && c <= 'Z') hasUpper = true;
                if (c >= '0' && c <= '9') hasDigit = true;
            }
        }

        assertTrue("Should generate lowercase letters", hasLower);
        assertTrue("Should generate uppercase letters", hasUpper);
        assertTrue("Should generate digits", hasDigit);
    }

    // ==================== bytesToReadableString ====================

    @Test
    public void testBytesToReadableStringBytes() {
        assertEquals("0.00 B", StringStuff.bytesToReadableString(0));
        assertEquals("1.00 B", StringStuff.bytesToReadableString(1));
        assertEquals("500.00 B", StringStuff.bytesToReadableString(500));
        assertEquals("999.00 B", StringStuff.bytesToReadableString(999));
    }

    @Test
    public void testBytesToReadableStringKilobytes() {
        assertEquals("1.00 kB", StringStuff.bytesToReadableString(1000));
        assertEquals("1.50 kB", StringStuff.bytesToReadableString(1500));
        assertEquals("10.00 kB", StringStuff.bytesToReadableString(10000));
        assertEquals("999.00 kB", StringStuff.bytesToReadableString(999000));
        // 999990 / 1000 = 999.99
        assertEquals("999.99 kB", StringStuff.bytesToReadableString(999990));
    }

    @Test
    public void testBytesToReadableStringMegabytes() {
        assertEquals("1.00 MB", StringStuff.bytesToReadableString(1_000_000));
        assertEquals("1.50 MB", StringStuff.bytesToReadableString(1_500_000));
        assertEquals("10.00 MB", StringStuff.bytesToReadableString(10_000_000));
        assertEquals("100.00 MB", StringStuff.bytesToReadableString(100_000_000));
        assertEquals("999.99 MB", StringStuff.bytesToReadableString(999_990_000));
    }

    @Test
    public void testBytesToReadableStringLargeValues() {
        // Gigabytes represented as MB
        assertEquals("1000.00 MB", StringStuff.bytesToReadableString(1_000_000_000L));
        assertEquals("10000.00 MB", StringStuff.bytesToReadableString(10_000_000_000L));
    }

    @Test
    public void testBytesToReadableStringBoundaries() {
        // Exactly at boundaries
        assertEquals("999.00 B", StringStuff.bytesToReadableString(999));
        assertEquals("1.00 kB", StringStuff.bytesToReadableString(1000));
        // 999999 / 1000 = 999.999 which rounds to 1000.00 kB with DecimalFormat
        assertEquals("1000.00 kB", StringStuff.bytesToReadableString(999999));
        assertEquals("1.00 MB", StringStuff.bytesToReadableString(1000000));
    }

    @Test
    public void testBytesToReadableStringPrecision() {
        // Verify decimal precision
        assertEquals("1.23 kB", StringStuff.bytesToReadableString(1234));
        assertEquals("1.24 kB", StringStuff.bytesToReadableString(1235)); // rounding
        assertEquals("5.68 MB", StringStuff.bytesToReadableString(5_678_901));
    }
}
