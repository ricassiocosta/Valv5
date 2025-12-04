package ricassiocosta.me.valv5.security;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.nio.ByteBuffer;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class SecureMemoryManagerTest {

    @Test
    public void testRegisterAndWipeNow() {
        SecureMemoryManager mgr = SecureMemoryManager.getInstance();
        mgr.setParanoidMode(false);

        byte[] b = new byte[]{1,2,3};
        char[] c = new char[]{'a','b'};
        ByteBuffer buf = ByteBuffer.allocate(8);

        mgr.register(b);
        mgr.register(c);
        mgr.register(buf);

        mgr.wipeNow(b);
        mgr.wipeNow(c);
        mgr.wipeNow(buf);

        for (byte bt : b) assertEquals(0, bt);
        for (char ch : c) assertEquals('\0', ch);
    }

    @Test
    public void testWipeSensitiveBuffers() {
        SecureMemoryManager mgr = SecureMemoryManager.getInstance();
        byte[] b = new byte[]{9,9,9};
        mgr.register(b);
        mgr.wipeSensitiveBuffers();
        for (byte bt : b) assertEquals(0, bt);
    }

    @Test
    public void testWipeNowNullOrEmpty() {
        SecureMemoryManager mgr = SecureMemoryManager.getInstance();
        mgr.setParanoidMode(true);
        // Should not throw
        mgr.wipeNow((byte[]) null);
        mgr.wipeNow(new byte[0]);
        mgr.wipeNow((char[]) null);
        mgr.wipeNow(new char[0]);
        // Restore
        mgr.setParanoidMode(false);
    }

    @Test
    public void testParanoidWipeNow() {
        SecureMemoryManager mgr = SecureMemoryManager.getInstance();
        mgr.setParanoidMode(true);
        byte[] b = new byte[]{5,5,5,5};
        mgr.wipeNow(b);
        for (byte bt : b) assertEquals(0, bt);
        mgr.setParanoidMode(false);
    }
}
