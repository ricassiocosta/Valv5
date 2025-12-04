package ricassiocosta.me.valv5.encryption;

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.security.Security;
import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

import static org.junit.Assert.fail;

public class AEADTamperTest {

    @BeforeClass
    public static void setup() {
        Security.addProvider(new BouncyCastleProvider());
    }

    @Test
    public void testAEADTamperDetected() throws Exception {
        byte[] fileData = "Sensitive data to protect".getBytes();
        char[] password = "badger".toCharArray();

        SecureRandom sr = SecureRandom.getInstanceStrong();
        byte[] salt = new byte[Encryption.SALT_LENGTH];
        byte[] iv = new byte[12];
        sr.nextBytes(salt);
        sr.nextBytes(iv);

        int iterationCount = 1000;
        int storedIteration = iterationCount | 0x80000000; // AEAD flag

        byte[] versionBytes = Encryption.toByteArray(Encryption.ENCRYPTION_VERSION_5);
        byte[] iterationBytes = Encryption.toByteArray(storedIteration);

        // Derive key using PBKDF2 (mirrors Encryption fallback behavior)
        SecretKeyFactory skf = SecretKeyFactory.getInstance("PBKDF2withHmacSHA512");
        PBEKeySpec spec = new PBEKeySpec(password, salt, iterationCount, 256);
        SecretKey tmp = skf.generateSecret(spec);
        byte[] keyBytes = tmp.getEncoded();
        SecretKey secretKey = new SecretKeySpec(keyBytes, "ChaCha20");

        // Build plaintext composite with simple section writer
        ByteArrayOutputStream plaintextBuffer = new ByteArrayOutputStream();
        // minimal metadata header
        plaintextBuffer.write(("\n{}\n").getBytes());
        SectionWriter sw = new SectionWriter(plaintextBuffer);
        sw.writeFileSection(new ByteArrayInputStream(fileData), fileData.length);
        sw.writeEndMarker();
        byte[] plaintext = plaintextBuffer.toByteArray();

        // Encrypt with ChaCha20-Poly1305
        Cipher cipher = Cipher.getInstance("ChaCha20-Poly1305", "BC");
        IvParameterSpec ivSpec = new IvParameterSpec(iv);
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, ivSpec);

        byte[] aad = new byte[4 + Encryption.SALT_LENGTH + iv.length + 4];
        System.arraycopy(versionBytes, 0, aad, 0, 4);
        System.arraycopy(salt, 0, aad, 4, Encryption.SALT_LENGTH);
        System.arraycopy(iv, 0, aad, 4 + Encryption.SALT_LENGTH, iv.length);
        System.arraycopy(iterationBytes, 0, aad, 4 + Encryption.SALT_LENGTH + iv.length, 4);
        cipher.updateAAD(aad);

        byte[] ciphertext = cipher.doFinal(plaintext);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(versionBytes);
        out.write(salt);
        out.write(iv);
        out.write(iterationBytes);
        out.write(ciphertext);

        // Tamper a byte in ciphertext payload
        byte[] combined = out.toByteArray();
        // flip a bit in the ciphertext area (after header)
        int headerLen = 4 + Encryption.SALT_LENGTH + iv.length + 4;
        if (combined.length > headerLen + 5) {
            combined[headerLen + 5] ^= 0x01;
        }

        ByteArrayInputStream encryptedIn = new ByteArrayInputStream(combined);

        try {
            Encryption.Streams streams = Encryption.getCipherInputStream(encryptedIn, password, false, Encryption.ENCRYPTION_VERSION_5);
            // attempt to read file bytes; should fail authentication
            streams.getFileBytes();
            streams.close();
            fail("Tampered ciphertext should not decrypt successfully");
        } catch (Exception e) {
            // expected: decryption/authentication should fail
        }
    }
}
