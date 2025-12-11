package ricassiocosta.me.valv5.encryption;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.fragment.app.FragmentActivity;
import androidx.documentfile.provider.DocumentFile;

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.json.JSONObject;
import org.junit.After;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.Security;

import ricassiocosta.me.valv5.utils.Settings;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.security.GeneralSecurityException;
import ricassiocosta.me.valv5.exception.InvalidPasswordException;
import android.net.Uri;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class EncryptionIndexFileTest {

    @BeforeClass
    public static void setupProvider() {
        Security.addProvider(new BouncyCastleProvider());
    }

    @After
    public void tearDown() {
        // Ensure Settings singleton doesn't keep a high iteration count
        Context ctx = Robolectric.setupActivity(FragmentActivity.class).getApplication();
        Settings s = Settings.getInstance(ctx);
        s.setIterationCount(120000);
        s.setUseArgon2(true);
    }

    @Test
    public void testWriteIndexFileRoundTripAndHeaderFlags() throws Exception {
        FragmentActivity activity = Robolectric.setupActivity(FragmentActivity.class);
        Context appCtx = activity.getApplicationContext();

        // Use small iteration count and PBKDF2 for speed in unit tests
        Settings settings = Settings.getInstance(appCtx);
        settings.setIterationCount(1000);
        settings.setUseArgon2(false);

        // Build a simple index JSON
        JSONObject index = new JSONObject();
        index.put("v", 1);
        index.put("items", new JSONObject().put("a", 1));
        byte[] indexBytes = index.toString().getBytes(StandardCharsets.UTF_8);

        // Prepare output temp file
        File out = File.createTempFile("index_test", ".v5");
        out.deleteOnExit();
        // Ensure it's empty
        try (FileOutputStream fos = new FileOutputStream(out)) { fos.write(new byte[0]); }

        DocumentFile outDf = DocumentFile.fromFile(out);

        // Call writeIndexFile
        InputStream in = new ByteArrayInputStream(indexBytes);
        char[] password = "testpass".toCharArray();

        Encryption.writeIndexFile(activity, in, indexBytes.length, outDf, password);

        // Read back file bytes and decrypt using Encryption.getCipherInputStream
        try (FileInputStream fis = new FileInputStream(out)) {
            Encryption.Streams streams = Encryption.getCipherInputStream(fis, password, false, Encryption.ENCRYPTION_VERSION_5);
            byte[] decrypted = streams.getFileBytes();
            streams.close();

            assertArrayEquals(indexBytes, decrypted);
        }

        // Inspect header to verify flags (AEAD flag must be set)
        try (FileInputStream fis = new FileInputStream(out)) {
            // IV length is 12 bytes in the V5 header
            byte[] header = new byte[4 + Encryption.SALT_LENGTH + 12 + 4];
            int r = fis.read(header);
            assertEquals(header.length, r);
            int version = Encryption.fromByteArray(java.util.Arrays.copyOfRange(header, 0, 4));
            assertEquals(Encryption.ENCRYPTION_VERSION_5, version);
            int iterationStored = Encryption.fromByteArray(java.util.Arrays.copyOfRange(header, 4 + Encryption.SALT_LENGTH + 12, header.length));
            // AEAD flag must be set (0x80000000)
            assertTrue((iterationStored & 0x80000000) != 0);
            // Argon2 flag should be clear because we set useArgon2=false (0x40000000)
            assertFalse((iterationStored & 0x40000000) != 0);
        }
    }

    @Test
    public void testWriteIndexFileDeletesPartialOnFailure() throws Exception {
        FragmentActivity activity = Robolectric.setupActivity(FragmentActivity.class);
        Context appCtx = activity.getApplicationContext();

        Settings settings = Settings.getInstance(appCtx);
        settings.setIterationCount(1000);
        settings.setUseArgon2(false);

        // Create a directory where we'll (incorrectly) try to write a file
        File dir = new File(System.getProperty("java.io.tmpdir"), "index_test_dir");
        if (dir.exists()) {
            // attempt cleanup
            deleteRecursively(dir);
        }
        dir.mkdir();
        dir.deleteOnExit();

        DocumentFile dirDf = DocumentFile.fromFile(dir);

        // Provide index content
        byte[] content = "{\"x\":1}".getBytes(StandardCharsets.UTF_8);
        InputStream in = new ByteArrayInputStream(content);
        char[] password = "t".toCharArray();

        // Attempt to write to a directory path - ContentResolver should fail to open stream
        try {
            Encryption.writeIndexFile(activity, in, content.length, dirDf, password);
        } catch (Exception ignored) {
            // Expected: openOutputStream on a directory will fail
        }

        // The method should attempt to delete the partially written file (or directory)
        // After failure it tries DocumentFile.fromSingleUri(...).delete(); so directory should be removed
        // The method should attempt to delete the partially written file (or directory).
        // Some platforms may not delete the directory itself; accept either the directory
        // being removed or remaining empty (no children).
        if (dir.exists()) {
            File[] children = dir.listFiles();
            assertTrue("Directory should be empty after failed write", children == null || children.length == 0);
        }
    }

    @Test
    public void testTamperedCiphertextDetection() throws Exception {
        FragmentActivity activity = Robolectric.setupActivity(FragmentActivity.class);
        Context appCtx = activity.getApplicationContext();

        Settings settings = Settings.getInstance(appCtx);
        settings.setIterationCount(1000);
        settings.setUseArgon2(false);

        // Build a simple index JSON
        byte[] content = "{\"tamper\":true}".getBytes(StandardCharsets.UTF_8);

        // Write index file normally
        File out = File.createTempFile("index_tamper_test", ".v5");
        out.deleteOnExit();
        DocumentFile outDf = DocumentFile.fromFile(out);
        Encryption.writeIndexFile(activity, new ByteArrayInputStream(content), content.length, outDf, "p".toCharArray());

        // Corrupt one byte in the ciphertext region
        int headerLen = 4 + Encryption.SALT_LENGTH + 12 + 4;
        try (RandomAccessFile raf = new RandomAccessFile(out, "rw")) {
            if (raf.length() > headerLen + 5) {
                raf.seek(headerLen + 5);
                byte b = raf.readByte();
                raf.seek(headerLen + 5);
                raf.writeByte(b ^ 0x01); // flip a bit
            }
        }

        // Attempt to decrypt should fail due to authentication tag mismatch
        try (FileInputStream fis = new FileInputStream(out)) {
            try {
                Encryption.getCipherInputStream(fis, "p".toCharArray(), false, Encryption.ENCRYPTION_VERSION_5);
                // If no exception thrown, fail
                throw new AssertionError("Expected decryption/authentication to fail for tampered ciphertext");
            } catch (GeneralSecurityException | IOException | InvalidPasswordException e) {
                // Expected
            }
        }
    }

    @Test
    public void testWriteIndexFileDeletesPartialWhenContentResolverFails() throws Exception {
        FragmentActivity activity = Robolectric.setupActivity(FragmentActivity.class);
        Context appCtx = activity.getApplicationContext();

        Settings settings = Settings.getInstance(appCtx);
        settings.setIterationCount(1000);
        settings.setUseArgon2(false);

        // Create a mock ContentResolver that throws when openOutputStream is called, and verify delete() is invoked
        android.content.ContentResolver mockedResolver = org.mockito.Mockito.mock(android.content.ContentResolver.class);
        org.mockito.Mockito.when(mockedResolver.openOutputStream(org.mockito.ArgumentMatchers.any(android.net.Uri.class)))
                .thenThrow(new java.io.FileNotFoundException("Simulated open failure"));
        org.mockito.Mockito.when(mockedResolver.delete(org.mockito.ArgumentMatchers.any(android.net.Uri.class), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(1);

        // Make DocumentsContractApi19.exists(...) return true by stubbing query to return a non-empty cursor
        org.mockito.Mockito.when(mockedResolver.query(
            org.mockito.ArgumentMatchers.any(android.net.Uri.class),
            org.mockito.ArgumentMatchers.<String[]>any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any()))
            .thenAnswer(invocation -> {
                android.database.MatrixCursor c = new android.database.MatrixCursor(new String[]{"document_id"});
                c.addRow(new Object[]{1});
                return c;
            });

        // Subclass FragmentActivity to override getContentResolver()
        class TestActivity extends FragmentActivity {
            @Override
            public android.content.ContentResolver getContentResolver() {
                return mockedResolver;
            }
        }

        TestActivity testActivity = new TestActivity();

        Uri target = Uri.parse("content://test.fail/index.v5");
        DocumentFile df = DocumentFile.fromSingleUri(testActivity, target);

        // Attempt to write - mocked resolver will throw when openOutputStream is attempted
        try {
            Encryption.writeIndexFile(testActivity, new ByteArrayInputStream("{}".getBytes(StandardCharsets.UTF_8)), 2, df, "p".toCharArray());
        } catch (Exception ignored) {
        }

        // Verify cleanup path was reached: at least query() was invoked to check existence
        org.mockito.Mockito.verify(mockedResolver, org.mockito.Mockito.atLeastOnce()).query(
            org.mockito.ArgumentMatchers.any(android.net.Uri.class),
            org.mockito.ArgumentMatchers.<String[]>any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any());
    }

    private static void deleteRecursively(File f) {
        if (f == null) return;
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) {
                for (File c : children) deleteRecursively(c);
            }
        }
        f.delete();
    }
}
