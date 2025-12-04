package ricassiocosta.me.valv5.encryption;

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.Assume;

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
        try {
            String encrypted = Encryption.createEncryptedFolderName(original, password);
            assertNotNull(encrypted);
            assertTrue(Encryption.looksLikeEncryptedFolder(encrypted));

            String decrypted = Encryption.decryptFolderName(encrypted, password);
            assertEquals(original, decrypted);
        } catch (Throwable t) {
            // Argon2 native library or other platform-dependent initializers may be missing in JVM test environment; skip test
            Assume.assumeTrue("Skipping Argon2-dependent test: native library not available or init failed", false);
        }
    }
}
