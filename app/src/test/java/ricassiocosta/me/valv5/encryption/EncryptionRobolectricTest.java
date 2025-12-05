package ricassiocosta.me.valv5.encryption;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import android.content.Context;

import androidx.fragment.app.FragmentActivity;

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.json.JSONObject;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.Security;
import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class EncryptionRobolectricTest {

    @BeforeClass
    public static void setupProvider() {
        Security.addProvider(new BouncyCastleProvider());
    }

    @Test
    public void testAEADRoundTrip() throws Exception {
        // Prepare test data
        byte[] fileData = "Hello AEAD RoundTrip".getBytes(StandardCharsets.UTF_8);
        char[] password = "testpassword".toCharArray();

        SecureRandom sr = SecureRandom.getInstanceStrong();
        byte[] salt = new byte[Encryption.SALT_LENGTH];
        byte[] iv = new byte[12];
        sr.nextBytes(salt);
        sr.nextBytes(iv);

        int iterationCount = 1000;
        int storedIteration = iterationCount | 0x80000000; // AEAD flag

        byte[] versionBytes = Encryption.toByteArray(Encryption.ENCRYPTION_VERSION_5);
        byte[] iterationBytes = Encryption.toByteArray(storedIteration);

        // Derive key using PBKDF2 (same as Encryption when ARGON2_FLAG not set)
        SecretKeyFactory skf = SecretKeyFactory.getInstance("PBKDF2withHmacSHA512");
        PBEKeySpec spec = new PBEKeySpec(password, salt, iterationCount, 256);
        SecretKey tmp = skf.generateSecret(spec);
        byte[] keyBytes = tmp.getEncoded();
        SecretKey secretKey = new SecretKeySpec(keyBytes, "ChaCha20");

        // Build plaintext composite using SectionWriter
        ByteArrayOutputStream plaintextBuffer = new ByteArrayOutputStream();
        JSONObject json = new JSONObject();
        json.put("originalName", "orig.txt");
        json.put("fileType", 3);
        json.put("contentType", Encryption.ContentType.FILE.value);
        JSONObject sections = new JSONObject();
        sections.put("FILE", true);
        sections.put("THUMBNAIL", false);
        sections.put("NOTE", false);
        json.put("sections", sections);

        plaintextBuffer.write(("\n" + json + "\n").getBytes(StandardCharsets.UTF_8));
        SectionWriter sw = new SectionWriter(plaintextBuffer);
        sw.writeFileSection(new ByteArrayInputStream(fileData), fileData.length);
        sw.writeEndMarker();

        byte[] plaintext = plaintextBuffer.toByteArray();

        // Encrypt with ChaCha20-Poly1305 (BC provider)
        Cipher cipher = Cipher.getInstance("ChaCha20-Poly1305", "BC");
        IvParameterSpec ivSpec = new IvParameterSpec(iv);
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, ivSpec);

        // Build AAD header
        byte[] aad = new byte[4 + Encryption.SALT_LENGTH + iv.length + 4];
        System.arraycopy(versionBytes, 0, aad, 0, 4);
        System.arraycopy(salt, 0, aad, 4, Encryption.SALT_LENGTH);
        System.arraycopy(iv, 0, aad, 4 + Encryption.SALT_LENGTH, iv.length);
        System.arraycopy(iterationBytes, 0, aad, 4 + Encryption.SALT_LENGTH + iv.length, 4);
        cipher.updateAAD(aad);

        byte[] ciphertext = cipher.doFinal(plaintext);

        // Combine header + ciphertext
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(versionBytes);
        out.write(salt);
        out.write(iv);
        out.write(iterationBytes);
        out.write(ciphertext);

        ByteArrayInputStream encryptedIn = new ByteArrayInputStream(out.toByteArray());

        // Decrypt using Encryption.getCipherInputStream
        Encryption.Streams streams = Encryption.getCipherInputStream(encryptedIn, password, false, Encryption.ENCRYPTION_VERSION_5);
        assertNotNull(streams);
        byte[] read = streams.getFileBytes();
        assertArrayEquals(fileData, read);
        streams.close();
    }
}
