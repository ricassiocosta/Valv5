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
}
