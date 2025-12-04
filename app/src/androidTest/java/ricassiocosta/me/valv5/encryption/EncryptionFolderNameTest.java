package ricassiocosta.me.valv5.encryption;

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.junit.BeforeClass;
import org.junit.Test;

import java.security.Security;

import static org.junit.Assert.*;

public class EncryptionFolderNameTest {

    @BeforeClass
    public static void setup() {
        Security.addProvider(new BouncyCastleProvider());
    }

    @Test
    public void testFolderNameRoundtrip() {
        char[] password = "unit-test-password".toCharArray();
        String original = "My Secrets";
        String encrypted = Encryption.createEncryptedFolderName(original, password);
        assertNotNull(encrypted);
        assertTrue(Encryption.looksLikeEncryptedFolder(encrypted));

        String decrypted = Encryption.decryptFolderName(encrypted, password);
        assertEquals(original, decrypted);
    }
}
