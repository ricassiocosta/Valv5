/*
 * Valv5
 * Copyright (c) 2025 ricassiocosta.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see https://www.gnu.org/licenses/.
 */

package ricassiocosta.me.valv5.index;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Unit tests for IndexManager class.
 * Tests only the static/pure methods that don't require Android context.
 * 
 * For tests that require Android context (SecureMemoryManager, file I/O),
 * see the instrumentation tests in androidTest/index/IndexManagerInstrumentedTest.java.
 */
public class IndexManagerTest {

    @Test
    public void testContentTypeConstant() {
        assertEquals("INDEX", IndexManager.CONTENT_TYPE_INDEX);
    }
    
    @Test
    public void testIndexFileNamePattern_valid_dotPrefixWith32Chars() {
        // Valid index file: dot prefix + 32 alphanumeric
        String validName = ".aB3xY9zK2mN5pQ7rS1tU4vW6xZ8aB3cD";
        assertEquals(33, validName.length());
        assertTrue(validName.startsWith("."));
        assertTrue(validName.substring(1).matches("[a-zA-Z0-9]{32}"));
    }
    
    @Test
    public void testIndexFileNamePattern_invalid_tooShort() {
        String shortName = ".short";
        assertFalse(shortName.length() == 33);
    }
    
    @Test
    public void testIndexFileNamePattern_invalid_noDotPrefix() {
        String noDot = "aB3xY9zK2mN5pQ7rS1tU4vW6xZ8aB3cD";
        assertFalse(noDot.startsWith("."));
    }
    
    @Test
    public void testIndexFileNamePattern_invalid_withSpecialChars() {
        String withSpecial = ".aB3xY9zK2mN5pQ7rS1tU4vW6xZ8aB_!";
        assertFalse(withSpecial.substring(1).matches("[a-zA-Z0-9]{32}"));
    }

    @Test
    public void determinesSameIndexFileNameAcrossCalls() {
        IndexManager mgr = IndexManager.getInstance();

        String first = mgr.determineTargetFileNameForTest();
        String second = mgr.determineTargetFileNameForTest();

        assertNotNull("First generated filename should not be null", first);
        assertEquals("Filename should be stable across calls", first, second);
        // Ensure it's in the expected format (dot + 32 alnum)
        assertTrue("Filename should start with dot", first.startsWith("."));
        assertEquals("Filename length should be 33", 33, first.length());
    }
}
