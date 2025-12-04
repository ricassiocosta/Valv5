package ricassiocosta.me.valv5.encryption;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.*;

public class SectionIOTest {

    @Test
    public void testSectionWriteRead() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        SectionWriter writer = new SectionWriter(out);

        byte[] fileData = "file-content".getBytes(StandardCharsets.UTF_8);
        byte[] thumbData = "thumb".getBytes(StandardCharsets.UTF_8);
        byte[] noteData = "note text".getBytes(StandardCharsets.UTF_8);

        writer.writeFileSection(new ByteArrayInputStream(fileData), fileData.length);
        writer.writeThumbnailSection(new ByteArrayInputStream(thumbData), thumbData.length);
        writer.writeNoteSection(noteData);
        writer.writeEndMarker();

        byte[] combined = out.toByteArray();

        SectionReader reader = new SectionReader(new ByteArrayInputStream(combined));

        SectionReader.SectionInfo info1 = reader.readNextSection();
        assertNotNull(info1);
        assertTrue(info1.isFileSection());
        assertEquals(fileData.length, info1.size);
        byte[] fileRead = reader.readSectionContent(info1.size);
        assertArrayEquals(fileData, fileRead);

        SectionReader.SectionInfo info2 = reader.readNextSection();
        assertNotNull(info2);
        assertTrue(info2.isThumbnailSection());
        assertEquals(thumbData.length, info2.size);
        byte[] thumbRead = reader.readSectionContent(info2.size);
        assertArrayEquals(thumbData, thumbRead);

        SectionReader.SectionInfo info3 = reader.readNextSection();
        assertNotNull(info3);
        assertTrue(info3.isNoteSection());
        assertEquals(noteData.length, info3.size);
        byte[] noteRead = reader.readSectionContent(info3.size);
        assertArrayEquals(noteData, noteRead);

        // Next should be end marker -> null
        SectionReader.SectionInfo end = reader.readNextSection();
        assertNull(end);
    }
}
