package ricassiocosta.me.valv5.data;

import org.junit.Test;

import static org.junit.Assert.*;

public class DirHashTest {

    @Test
    public void testClear() {
        byte[] salt = new byte[]{1,2,3,4};
        byte[] hash = new byte[]{9,8,7,6};
        DirHash dh = new DirHash(salt, hash);
        dh.clear();
        for (byte b : salt) assertEquals(0, b);
        for (byte b : hash) assertEquals(0, b);
    }
}
