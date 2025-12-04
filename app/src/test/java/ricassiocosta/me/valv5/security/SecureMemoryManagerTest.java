package ricassiocosta.me.valv5.security;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.nio.ByteBuffer;

import static org.junit.Assert.*;

/**
 * Tests for SecureMemoryManager.
 * 
 * Note: SecureMemoryManager is a singleton. Each test must clean up
 * registered buffers to ensure test isolation.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class SecureMemoryManagerTest {

    private SecureMemoryManager mgr;

    @Before
    public void setUp() {
        mgr = SecureMemoryManager.getInstance();
        mgr.setParanoidMode(false);
        // Clean any leftover state from previous tests
        mgr.wipeAll();
    }

    @After
    public void tearDown() {
        // Ensure clean state for next test
        mgr.setParanoidMode(false);
        mgr.wipeAll();
    }

    // ==================== Registration and wipe ====================

    @Test
    public void testRegisterAndWipeNowByteArray() {
        byte[] b = new byte[]{1, 2, 3, 4, 5};
        mgr.register(b);

        mgr.wipeNow(b);

        for (int i = 0; i < b.length; i++) {
            assertEquals("Byte at index " + i + " should be zero", 0, b[i]);
        }
    }

    @Test
    public void testRegisterAndWipeNowCharArray() {
        char[] c = new char[]{'a', 'b', 'c'};
        mgr.register(c);

        mgr.wipeNow(c);

        for (int i = 0; i < c.length; i++) {
            assertEquals("Char at index " + i + " should be null", '\0', c[i]);
        }
    }

    @Test
    public void testRegisterAndWipeNowByteBuffer() {
        ByteBuffer buf = ByteBuffer.allocate(8);
        buf.put(new byte[]{1, 2, 3, 4, 5, 6, 7, 8});
        mgr.register(buf);

        mgr.wipeNow(buf);

        buf.position(0);
        while (buf.hasRemaining()) {
            assertEquals("ByteBuffer should be zeroed", 0, buf.get());
        }
    }

    // ==================== Bulk wipe ====================

    @Test
    public void testWipeSensitiveBuffersWipesAllRegistered() {
        byte[] b1 = new byte[]{1, 2, 3};
        byte[] b2 = new byte[]{4, 5, 6};
        char[] c = new char[]{'x', 'y', 'z'};

        mgr.register(b1);
        mgr.register(b2);
        mgr.register(c);

        mgr.wipeSensitiveBuffers();

        for (byte bt : b1) assertEquals(0, bt);
        for (byte bt : b2) assertEquals(0, bt);
        for (char ch : c) assertEquals('\0', ch);
    }

    @Test
    public void testWipeAllClearsEverything() {
        byte[] b = new byte[]{9, 9, 9};
        char[] c = new char[]{'a', 'b'};
        ByteBuffer buf = ByteBuffer.allocate(4);
        buf.put(new byte[]{1, 2, 3, 4});

        mgr.register(b);
        mgr.register(c);
        mgr.register(buf);

        mgr.wipeAll();

        for (byte bt : b) assertEquals(0, bt);
        for (char ch : c) assertEquals('\0', ch);
    }

    // ==================== Null and empty inputs ====================

    @Test
    public void testWipeNowNullByteArrayDoesNotThrow() {
        mgr.wipeNow((byte[]) null); // Should not throw
    }

    @Test
    public void testWipeNowEmptyByteArrayDoesNotThrow() {
        mgr.wipeNow(new byte[0]); // Should not throw
    }

    @Test
    public void testWipeNowNullCharArrayDoesNotThrow() {
        mgr.wipeNow((char[]) null); // Should not throw
    }

    @Test
    public void testWipeNowEmptyCharArrayDoesNotThrow() {
        mgr.wipeNow(new char[0]); // Should not throw
    }

    @Test
    public void testWipeNowNullByteBufferDoesNotThrow() {
        mgr.wipeNow((ByteBuffer) null); // Should not throw
    }

    @Test
    public void testRegisterNullDoesNotThrow() {
        mgr.register((byte[]) null);
        mgr.register((char[]) null);
        mgr.register((ByteBuffer) null);
        // Should not throw
    }

    // ==================== Paranoid mode ====================

    @Test
    public void testParanoidModeWipesWithPatterns() {
        mgr.setParanoidMode(true);
        assertTrue(mgr.isParanoidMode());

        byte[] b = new byte[]{(byte) 0xFF, (byte) 0xAA, (byte) 0x55};
        mgr.wipeNow(b);

        // Final result should still be zeros
        for (int i = 0; i < b.length; i++) {
            assertEquals("Byte at index " + i + " should be zero after paranoid wipe", 0, b[i]);
        }
    }

    @Test
    public void testParanoidModeCanBeToggled() {
        assertFalse(mgr.isParanoidMode());

        mgr.setParanoidMode(true);
        assertTrue(mgr.isParanoidMode());

        mgr.setParanoidMode(false);
        assertFalse(mgr.isParanoidMode());
    }

    // ==================== Stats and cleanup ====================

    @Test
    public void testGetStatsReturnsNonNullString() {
        String stats = mgr.getStats();
        assertNotNull(stats);
        assertTrue(stats.contains("Registered:"));
    }

    @Test
    public void testCleanupStaleReferencesDoesNotThrow() {
        // Register some items
        mgr.register(new byte[]{1, 2, 3});
        mgr.register(new char[]{'a', 'b'});

        // This should not throw
        mgr.cleanupStaleReferences();
    }

    // ==================== Singleton behavior ====================

    @Test
    public void testGetInstanceReturnsSameInstance() {
        SecureMemoryManager mgr1 = SecureMemoryManager.getInstance();
        SecureMemoryManager mgr2 = SecureMemoryManager.getInstance();
        assertSame(mgr1, mgr2);
    }

    // ==================== Direct ByteBuffer ====================

    @Test
    public void testWipeDirectByteBuffer() {
        ByteBuffer directBuf = ByteBuffer.allocateDirect(8);
        directBuf.put(new byte[]{1, 2, 3, 4, 5, 6, 7, 8});
        mgr.register(directBuf);

        mgr.wipeNow(directBuf);

        directBuf.position(0);
        while (directBuf.hasRemaining()) {
            assertEquals("Direct ByteBuffer should be zeroed", 0, directBuf.get());
        }
    }
}
