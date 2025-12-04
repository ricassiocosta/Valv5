package ricassiocosta.me.valv5.encryption;

import org.bouncycastle.jce.provider.BouncyCastleProvider;
// avoid android org.json in JVM unit tests; build JSON string manually
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.security.Security;
import java.security.SecureRandom;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.*;

public class CompositeIntegrationTest {

    @BeforeClass
    public static void setup() {
        Security.addProvider(new BouncyCastleProvider());
    }

    @Test
    public void testWriteAndReadCompositeInMemory() throws Exception {
        byte[] fileData = "Integration file content".getBytes(StandardCharsets.UTF_8);

        // Build JSON metadata and sections using public APIs
        ByteArrayOutputStream compositeOut = new ByteArrayOutputStream();
        // Build minimal JSON string without using org.json to avoid Android mocks
        String json = String.format("{\"originalName\":\"%s\",\"fileType\":%d,\"contentType\":%d,\"sections\":{\"FILE\":true,\"THUMBNAIL\":false,\"NOTE\":false}}",
            "hello.txt", 3, Encryption.ContentType.FILE.value);
        compositeOut.write(("\n" + json + "\n").getBytes(StandardCharsets.UTF_8));

        SectionWriter writer = new SectionWriter(compositeOut);
        writer.writeFileSection(new ByteArrayInputStream(fileData), fileData.length);
        writer.writeEndMarker();

        byte[] combined = compositeOut.toByteArray();

        // The composite format starts with a metadata header: "\n<json>\n".
        // SectionReader expects to start at the first section marker, so skip the header.
        int startIndex = 0;
        int newlines = 0;
        for (int i = 0; i < combined.length; i++) {
            if (combined[i] == '\n') {
                newlines++;
                if (newlines == 2) {
                    startIndex = i + 1;
                    break;
                }
            }
        }
        byte[] sectionsOnly = java.util.Arrays.copyOfRange(combined, startIndex, combined.length);

        // Read back using SectionReader starting at the sections part
        SectionReader reader = new SectionReader(new ByteArrayInputStream(sectionsOnly));
        SectionReader.SectionInfo info = reader.readNextSection();
        assertNotNull(info);
        assertTrue(info.isFileSection());
        byte[] read = reader.readSectionContent(info.size);
        assertArrayEquals(fileData, read);

        // next should be end marker
        SectionReader.SectionInfo end = reader.readNextSection();
        assertNull(end);
    }
}
