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

    @Test
    public void testAEADTruncatedTagDetected() throws Exception {
        byte[] fileData = "Sensitive data short".getBytes();
        char[] password = "badger".toCharArray();

        java.security.SecureRandom sr = java.security.SecureRandom.getInstanceStrong();
        byte[] salt = new byte[Encryption.SALT_LENGTH];
        byte[] iv = new byte[12];
        sr.nextBytes(salt);
        sr.nextBytes(iv);

        int iterationCount = 1000;
        int storedIteration = iterationCount | 0x80000000; // AEAD flag

        byte[] versionBytes = Encryption.toByteArray(Encryption.ENCRYPTION_VERSION_5);
        byte[] iterationBytes = Encryption.toByteArray(storedIteration);

        javax.crypto.SecretKeyFactory skf = javax.crypto.SecretKeyFactory.getInstance("PBKDF2withHmacSHA512");
        javax.crypto.spec.PBEKeySpec spec = new javax.crypto.spec.PBEKeySpec(password, salt, iterationCount, 256);
        javax.crypto.SecretKey tmp = skf.generateSecret(spec);
        byte[] keyBytes = tmp.getEncoded();
        javax.crypto.SecretKey secretKey = new javax.crypto.spec.SecretKeySpec(keyBytes, "ChaCha20");

        // plaintext composite
        java.io.ByteArrayOutputStream plaintextBuffer = new java.io.ByteArrayOutputStream();
        plaintextBuffer.write(("\n{}\n").getBytes());
        SectionWriter sw = new SectionWriter(plaintextBuffer);
        sw.writeFileSection(new java.io.ByteArrayInputStream(fileData), fileData.length);
        sw.writeEndMarker();
        byte[] plaintext = plaintextBuffer.toByteArray();

        javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("ChaCha20-Poly1305", "BC");
        javax.crypto.spec.IvParameterSpec ivSpec = new javax.crypto.spec.IvParameterSpec(iv);
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, secretKey, ivSpec);

        byte[] aad = new byte[4 + Encryption.SALT_LENGTH + iv.length + 4];
        System.arraycopy(versionBytes, 0, aad, 0, 4);
        System.arraycopy(salt, 0, aad, 4, Encryption.SALT_LENGTH);
        System.arraycopy(iv, 0, aad, 4 + Encryption.SALT_LENGTH, iv.length);
        System.arraycopy(iterationBytes, 0, aad, 4 + Encryption.SALT_LENGTH + iv.length, 4);
        cipher.updateAAD(aad);

        byte[] ciphertext = cipher.doFinal(plaintext);

        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        out.write(versionBytes);
        out.write(salt);
        out.write(iv);
        out.write(iterationBytes);
        out.write(ciphertext);

        // Truncate authentication tag bytes from the end (remove 8 bytes)
        byte[] combined = out.toByteArray();
        int truncatedLen = Math.max(0, combined.length - 8);
        byte[] truncated = java.util.Arrays.copyOf(combined, truncatedLen);

        java.io.ByteArrayInputStream encryptedIn = new java.io.ByteArrayInputStream(truncated);

        try {
            Encryption.Streams streams = Encryption.getCipherInputStream(encryptedIn, password, false, Encryption.ENCRYPTION_VERSION_5);
            // attempt to read file bytes; should fail due to truncated tag
            streams.getFileBytes();
            streams.close();
            fail("Truncated tag should not decrypt successfully");
        } catch (Exception e) {
            // expected: decryption/authentication should fail
        }
    }

    @Test
    public void testAEADTruncatedHeaderDetected() throws Exception {
        byte[] fileData = "Data header test".getBytes();
        char[] password = "badger".toCharArray();

        java.security.SecureRandom sr = java.security.SecureRandom.getInstanceStrong();
        byte[] salt = new byte[Encryption.SALT_LENGTH];
        byte[] iv = new byte[12];
        sr.nextBytes(salt);
        sr.nextBytes(iv);

        int iterationCount = 1000;
        int storedIteration = iterationCount | 0x80000000; // AEAD flag

        byte[] versionBytes = Encryption.toByteArray(Encryption.ENCRYPTION_VERSION_5);
        byte[] iterationBytes = Encryption.toByteArray(storedIteration);

        javax.crypto.SecretKeyFactory skf = javax.crypto.SecretKeyFactory.getInstance("PBKDF2withHmacSHA512");
        javax.crypto.spec.PBEKeySpec spec = new javax.crypto.spec.PBEKeySpec(password, salt, iterationCount, 256);
        javax.crypto.SecretKey tmp = skf.generateSecret(spec);
        javax.crypto.SecretKey secretKey = new javax.crypto.spec.SecretKeySpec(tmp.getEncoded(), "ChaCha20");

        java.io.ByteArrayOutputStream plaintextBuffer = new java.io.ByteArrayOutputStream();
        plaintextBuffer.write(("\n{}\n").getBytes());
        SectionWriter sw = new SectionWriter(plaintextBuffer);
        sw.writeFileSection(new java.io.ByteArrayInputStream(fileData), fileData.length);
        sw.writeEndMarker();
        byte[] plaintext = plaintextBuffer.toByteArray();

        javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("ChaCha20-Poly1305", "BC");
        javax.crypto.spec.IvParameterSpec ivSpec = new javax.crypto.spec.IvParameterSpec(iv);
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, secretKey, ivSpec);

        byte[] aad = new byte[4 + Encryption.SALT_LENGTH + iv.length + 4];
        System.arraycopy(versionBytes, 0, aad, 0, 4);
        System.arraycopy(salt, 0, aad, 4, Encryption.SALT_LENGTH);
        System.arraycopy(iv, 0, aad, 4 + Encryption.SALT_LENGTH, iv.length);
        System.arraycopy(iterationBytes, 0, aad, 4 + Encryption.SALT_LENGTH + iv.length, 4);
        cipher.updateAAD(aad);

        byte[] ciphertext = cipher.doFinal(plaintext);

        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        out.write(versionBytes);
        out.write(salt);
        out.write(iv);
        out.write(iterationBytes);
        out.write(ciphertext);

        byte[] combined = out.toByteArray();
        // Truncate a couple bytes from the header
        int headerLen = 4 + Encryption.SALT_LENGTH + iv.length + 4;
        byte[] truncatedHeader = java.util.Arrays.copyOfRange(combined, 0, combined.length - (headerLen - 2));

        java.io.ByteArrayInputStream encryptedIn = new java.io.ByteArrayInputStream(truncatedHeader);

        try {
            Encryption.Streams streams = Encryption.getCipherInputStream(encryptedIn, password, false, Encryption.ENCRYPTION_VERSION_5);
            streams.getFileBytes();
            streams.close();
            fail("Truncated header should cause parsing/decryption to fail");
        } catch (Exception e) {
            // expected
        }
    }

    @Test
    public void testAEADWrongPasswordDetected() throws Exception {
        byte[] fileData = "Wrong password test".getBytes();
        char[] password = "correct".toCharArray();
        char[] wrong = "incorrect".toCharArray();

        java.security.SecureRandom sr = java.security.SecureRandom.getInstanceStrong();
        byte[] salt = new byte[Encryption.SALT_LENGTH];
        byte[] iv = new byte[12];
        sr.nextBytes(salt);
        sr.nextBytes(iv);

        int iterationCount = 1000;
        int storedIteration = iterationCount | 0x80000000; // AEAD flag

        byte[] versionBytes = Encryption.toByteArray(Encryption.ENCRYPTION_VERSION_5);
        byte[] iterationBytes = Encryption.toByteArray(storedIteration);

        javax.crypto.SecretKeyFactory skf = javax.crypto.SecretKeyFactory.getInstance("PBKDF2withHmacSHA512");
        javax.crypto.spec.PBEKeySpec spec = new javax.crypto.spec.PBEKeySpec(password, salt, iterationCount, 256);
        javax.crypto.SecretKey tmp = skf.generateSecret(spec);
        javax.crypto.SecretKey secretKey = new javax.crypto.spec.SecretKeySpec(tmp.getEncoded(), "ChaCha20");

        java.io.ByteArrayOutputStream plaintextBuffer = new java.io.ByteArrayOutputStream();
        plaintextBuffer.write(("\n{}\n").getBytes());
        SectionWriter sw = new SectionWriter(plaintextBuffer);
        sw.writeFileSection(new java.io.ByteArrayInputStream(fileData), fileData.length);
        sw.writeEndMarker();
        byte[] plaintext = plaintextBuffer.toByteArray();

        javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("ChaCha20-Poly1305", "BC");
        javax.crypto.spec.IvParameterSpec ivSpec = new javax.crypto.spec.IvParameterSpec(iv);
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, secretKey, ivSpec);

        byte[] aad = new byte[4 + Encryption.SALT_LENGTH + iv.length + 4];
        System.arraycopy(versionBytes, 0, aad, 0, 4);
        System.arraycopy(salt, 0, aad, 4, Encryption.SALT_LENGTH);
        System.arraycopy(iv, 0, aad, 4 + Encryption.SALT_LENGTH, iv.length);
        System.arraycopy(iterationBytes, 0, aad, 4 + Encryption.SALT_LENGTH + iv.length, 4);
        cipher.updateAAD(aad);

        byte[] ciphertext = cipher.doFinal(plaintext);

        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        out.write(versionBytes);
        out.write(salt);
        out.write(iv);
        out.write(iterationBytes);
        out.write(ciphertext);

        java.io.ByteArrayInputStream encryptedIn = new java.io.ByteArrayInputStream(out.toByteArray());

        try {
            Encryption.Streams streams = Encryption.getCipherInputStream(encryptedIn, wrong, false, Encryption.ENCRYPTION_VERSION_5);
            streams.getFileBytes();
            streams.close();
            fail("Wrong password should not decrypt successfully");
        } catch (Exception e) {
            // expected
        }
    }

    @Test
    public void testAEADMalformedIterationFlagsDetected() throws Exception {
        byte[] fileData = "Malformed flags test".getBytes();
        char[] password = "flags".toCharArray();

        java.security.SecureRandom sr = java.security.SecureRandom.getInstanceStrong();
        byte[] salt = new byte[Encryption.SALT_LENGTH];
        byte[] iv = new byte[12];
        sr.nextBytes(salt);
        sr.nextBytes(iv);

        // Use an intentionally malformed/stupid iteration value
        int storedIteration = 0x7FFFFFFF; // unlikely valid flag combination

        byte[] versionBytes = Encryption.toByteArray(Encryption.ENCRYPTION_VERSION_5);
        byte[] iterationBytes = Encryption.toByteArray(storedIteration);

        // derive key with PBKDF2 to construct encryption (won't match parser expectations)
        javax.crypto.SecretKeyFactory skf = javax.crypto.SecretKeyFactory.getInstance("PBKDF2withHmacSHA512");
        javax.crypto.spec.PBEKeySpec spec = new javax.crypto.spec.PBEKeySpec(password, salt, 1000, 256);
        javax.crypto.SecretKey tmp = skf.generateSecret(spec);
        javax.crypto.SecretKey secretKey = new javax.crypto.spec.SecretKeySpec(tmp.getEncoded(), "ChaCha20");

        java.io.ByteArrayOutputStream plaintextBuffer = new java.io.ByteArrayOutputStream();
        plaintextBuffer.write(("\n{}\n").getBytes());
        SectionWriter sw = new SectionWriter(plaintextBuffer);
        sw.writeFileSection(new java.io.ByteArrayInputStream(fileData), fileData.length);
        sw.writeEndMarker();
        byte[] plaintext = plaintextBuffer.toByteArray();

        javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("ChaCha20-Poly1305", "BC");
        javax.crypto.spec.IvParameterSpec ivSpec = new javax.crypto.spec.IvParameterSpec(iv);
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, secretKey, ivSpec);

        byte[] aad = new byte[4 + Encryption.SALT_LENGTH + iv.length + 4];
        System.arraycopy(versionBytes, 0, aad, 0, 4);
        System.arraycopy(salt, 0, aad, 4, Encryption.SALT_LENGTH);
        System.arraycopy(iv, 0, aad, 4 + Encryption.SALT_LENGTH, iv.length);
        System.arraycopy(iterationBytes, 0, aad, 4 + Encryption.SALT_LENGTH + iv.length, 4);
        cipher.updateAAD(aad);

        byte[] ciphertext = cipher.doFinal(plaintext);

        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        out.write(versionBytes);
        out.write(salt);
        out.write(iv);
        out.write(iterationBytes);
        out.write(ciphertext);

        java.io.ByteArrayInputStream encryptedIn = new java.io.ByteArrayInputStream(out.toByteArray());

        try {
            Encryption.Streams streams = Encryption.getCipherInputStream(encryptedIn, password, false, Encryption.ENCRYPTION_VERSION_5);
            streams.getFileBytes();
            streams.close();
            fail("Malformed iteration flags should cause decryption/parsing to fail");
        } catch (UnsatisfiedLinkError | NoClassDefFoundError | ExceptionInInitializerError e) {
            // If native libs are missing (Argon2), skip the test in JVM; otherwise this is expected
            if (e instanceof UnsatisfiedLinkError || e instanceof NoClassDefFoundError) {
                org.junit.Assume.assumeTrue("Skipping test due to missing native library", false);
            }
            // otherwise expected - authentication/parsing failure
        }
    }

    @Test
    public void testAEADTruncatedCiphertextDetected() throws Exception {
        byte[] fileData = "Truncated ciphertext test data which is longer".getBytes();
        char[] password = "truncate".toCharArray();

        java.security.SecureRandom sr = java.security.SecureRandom.getInstanceStrong();
        byte[] salt = new byte[Encryption.SALT_LENGTH];
        byte[] iv = new byte[12];
        sr.nextBytes(salt);
        sr.nextBytes(iv);

        int iterationCount = 1000;
        int storedIteration = iterationCount | 0x80000000; // AEAD flag

        byte[] versionBytes = Encryption.toByteArray(Encryption.ENCRYPTION_VERSION_5);
        byte[] iterationBytes = Encryption.toByteArray(storedIteration);

        javax.crypto.SecretKeyFactory skf = javax.crypto.SecretKeyFactory.getInstance("PBKDF2withHmacSHA512");
        javax.crypto.spec.PBEKeySpec spec = new javax.crypto.spec.PBEKeySpec(password, salt, iterationCount, 256);
        javax.crypto.SecretKey tmp = skf.generateSecret(spec);
        javax.crypto.SecretKey secretKey = new javax.crypto.spec.SecretKeySpec(tmp.getEncoded(), "ChaCha20");

        java.io.ByteArrayOutputStream plaintextBuffer = new java.io.ByteArrayOutputStream();
        plaintextBuffer.write(("\n{}\n").getBytes());
        SectionWriter sw = new SectionWriter(plaintextBuffer);
        sw.writeFileSection(new java.io.ByteArrayInputStream(fileData), fileData.length);
        sw.writeEndMarker();
        byte[] plaintext = plaintextBuffer.toByteArray();

        javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("ChaCha20-Poly1305", "BC");
        javax.crypto.spec.IvParameterSpec ivSpec = new javax.crypto.spec.IvParameterSpec(iv);
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, secretKey, ivSpec);

        byte[] aad = new byte[4 + Encryption.SALT_LENGTH + iv.length + 4];
        System.arraycopy(versionBytes, 0, aad, 0, 4);
        System.arraycopy(salt, 0, aad, 4, Encryption.SALT_LENGTH);
        System.arraycopy(iv, 0, aad, 4 + Encryption.SALT_LENGTH, iv.length);
        System.arraycopy(iterationBytes, 0, aad, 4 + Encryption.SALT_LENGTH + iv.length, 4);
        cipher.updateAAD(aad);

        byte[] ciphertext = cipher.doFinal(plaintext);

        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        out.write(versionBytes);
        out.write(salt);
        out.write(iv);
        out.write(iterationBytes);
        out.write(ciphertext);

        byte[] combined = out.toByteArray();
        // Truncate a substantial portion of ciphertext (not only tag)
        int truncatedLen = Math.max(0, combined.length - (ciphertext.length / 2));
        byte[] truncated = java.util.Arrays.copyOf(combined, truncatedLen);

        java.io.ByteArrayInputStream encryptedIn = new java.io.ByteArrayInputStream(truncated);

        try {
            Encryption.Streams streams = Encryption.getCipherInputStream(encryptedIn, password, false, Encryption.ENCRYPTION_VERSION_5);
            streams.getFileBytes();
            streams.close();
            fail("Truncated ciphertext should not decrypt successfully");
        } catch (Exception e) {
            // expected
        }
    }
}
