This is the encryption docs for V5 - the latest and only supported encryption format.

# File encryption
Files are encrypted using the `ChaCha20/NONE/NoPadding` cipher in Android. See [Android Ciphers](https://developer.android.com/reference/javax/crypto/Cipher) for details.

The key algorithm is `PBKDF2withHmacSHA512` with 120000 iterations (default, can be changed) and a 256-bit key length.

The salt is 16 bytes and the IV is 12 bytes. An additional 12 bytes is used to check if the supplied password can decrypt the file, see details below.

## V5 Encrypted file structure (Composite Atomic Format)

V5 uses a **single atomic file** with internal sections to eliminate metadata leakage:
- **32-character alphanumeric random filename** (no extension, no prefixes, no suffixes)
- **Internal sections** for file, thumbnail, and note data
- All metadata (original filename, file type, content type) stored encrypted inside the file
- Thumbnail and note are NOT separate files - they're embedded inside the main file

## File types/names

**V5 naming convention:**
- Only one file per encrypted item: a 32-character random alphanumeric name (e.g., `aLFshh71iywWo7HXtEcOtZNVJe-Ot7iQ`)
- No extension
- No suffix (no `-i.valv`, `-t.valv`, etc.)
- File type is determined from encrypted metadata, not from filename

**Legacy formats (NO LONGER SUPPORTED):**
- V1/V2: Used prefix/suffix based naming (e.g., `-i.valv`, `-g.valv`, `-t.valv`)
- V3/V4: Used correlatable suffixes with version numbers
- These are completely removed from the codebase

All text and strings are encoded as UTF-8.

## Encrypting
The app creates V5 encrypted files in the following way:
1. Generate a random 16 byte salt, a 12 byte IV and 12 check bytes.
2. Create an unencrypted output stream.
3. Write the encrypted file structure version (4 bytes, integer = 5)
4. Write the salt.
5. Write the IV.
6. Write the iteration count used for key generation (4 bytes, integer)
7. Write the check bytes.
8. Pass the output stream into a cipher (encrypted) output stream. Everything below is encrypted.
9. Write the check bytes.
10. Write a newline character followed by a JSON object as a string containing:
    - `originalName`: The original filename
    - `fileType`: Type of file (0=image, 1=gif, 2=video, 3=text, etc.)
    - `contentType`: Content type descriptor
    - `relatedFiles`: (Optional) Array of related files for correlation detection
    - Format: `'\n' + "{\"originalName\":\"file.jpg\",\"fileType\":1,\"contentType\":\"FILE\"}" + '\n'`
11. Write the FILE section marker (0x00) and file data.
12. Write the THUMBNAIL section marker (0x01) and thumbnail data (if present).
13. Write the NOTE section marker (0x02) and note data (if present).
14. Write the END marker (0xFF).

## Decrypting
The app reads V5 encrypted files in the following way:
1. Create an unencrypted input stream.
2. Read the encrypted file structure version (4 bytes, integer)
3. Read the 16 byte salt.
4. Read the 12 byte IV.
5. Read the iteration count used for key generation (4 bytes, integer)
6. Read the 12 check bytes.
7. Pass the input stream into a cipher (encrypted) input stream. Everything below is read from encrypted data.
8. Read the check bytes. If the unencrypted check bytes does not equal the check bytes in the encrypted part, the given password is invalid.
9. Read a newline character (`0x0A`) followed by the JSON metadata object and another newline character.
10. Read sections sequentially by their markers:
    - **FILE section (0x00)**: Main file data
    - **THUMBNAIL section (0x01)**: Thumbnail image data
    - **NOTE section (0x02)**: Associated note text
    - **END marker (0xFF)**: End of sections
11. Parse the JSON to extract: original filename, file type, content type, and any related files.

## Security Benefits of V5
- **No metadata leakage through filenames**: All files have random 32-char names
- **Atomic file structure**: Eliminates need for separate thumbnail/note files that correlate together
- **Single encryption context**: All related data encrypted together without external correlations
- **Version transparency**: File version is determined by reading encrypted header, not by filename patterns

