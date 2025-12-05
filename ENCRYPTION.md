This is the encryption docs for V5 - the latest and only supported encryption format.

# File encryption

Valv5 uses modern, authenticated encryption with configurable key derivation. The encryption method is automatically selected based on file size for optimal security and performance.

## Key Derivation Functions (KDF)

Valv5 supports two key derivation functions:

### Argon2id (Recommended, Default)
- **Algorithm**: Argon2id (memory-hard, GPU/ASIC resistant)
- **Memory Cost**: 64 MB
- **Time Cost**: 3 iterations
- **Parallelism**: 4 threads
- **Key Length**: 256 bits (32 bytes)

Argon2id provides superior protection against hardware-accelerated brute force attacks compared to PBKDF2.

## Encryption Modes

### AEAD Mode (ChaCha20-Poly1305) - Small Files (≤50 MB)
For files 50 MB or smaller, Valv5 uses authenticated encryption with associated data (AEAD):
- **Cipher**: ChaCha20-Poly1305
- **Authentication**: Poly1305 MAC (16-byte tag)
- **Nonce/IV**: 12 bytes
- **Benefits**: Single authentication tag for entire file, tamper detection

### SecretStream Mode (XChaCha20-Poly1305) - Large Files (>50 MB)
For files larger than 50 MB, Valv5 uses libsodium's SecretStream for memory-efficient streaming encryption:
- **Cipher**: XChaCha20-Poly1305 (via libsodium)
- **Header**: 24 bytes
- **Chunk Size**: 64 KB default
- **Per-Chunk Authentication**: 17-byte tag per chunk
- **Benefits**: Stream processing without loading entire file into memory, per-chunk integrity verification

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

## File Header Format

The file header (unencrypted) is as follows:
1. **Version** (4 bytes, integer = 5)
2. **Salt** (16 bytes, random)
3. **IV/Nonce** (12 bytes, random - used for AEAD, padding for SecretStream)
4. **Iteration Count with Flags** (4 bytes, integer with high-bit flags)

### Iteration Count Flags
The iteration count field contains flags in the high bits:
- **Bit 31 (0x80000000)**: AEAD mode (ChaCha20-Poly1305)
- **Bit 30 (0x40000000)**: Argon2id KDF (instead of PBKDF2)
- **Bit 29 (0x20000000)**: SecretStream mode (libsodium XChaCha20-Poly1305)
- **Bits 0-28**: Actual iteration count (for PBKDF2, ignored for Argon2id)

## Encrypting

### AEAD Mode (Small Files ≤50 MB)
1. Generate random 16-byte salt and 12-byte IV
2. Set AEAD_FLAG (0x80000000) and optionally ARGON2_FLAG (0x40000000) in iteration count
3. Derive 256-bit key using Argon2id or PBKDF2
4. Write header: [version:4][salt:16][iv:12][iteration_count|flags:4]
5. Build plaintext: JSON metadata + FILE section + THUMBNAIL section + NOTE section + END marker
6. Encrypt with ChaCha20-Poly1305, using header as AAD (Associated Authenticated Data)
7. Write ciphertext (includes 16-byte Poly1305 tag)

### SecretStream Mode (Large Files >50 MB)
1. Generate random 16-byte salt and 12-byte padding
2. Set STREAM_FLAG (0x20000000) and optionally ARGON2_FLAG (0x40000000) in iteration count
3. Derive 256-bit key using Argon2id or PBKDF2
4. Write header: [version:4][salt:16][padding:12][iteration_count|flags:4]
5. Initialize SecretStream with key, write 24-byte SecretStream header
6. Stream-encrypt data in 64KB chunks with per-chunk authentication
7. Final chunk has TAG_FINAL (0x03) to detect truncation

### Encrypted Content Format
The encrypted content (after AEAD/stream decryption) contains:
1. Newline character (`0x0A`)
2. JSON metadata object containing:
   - `originalName`: The original filename (e.g., "photo.jpg")
   - `fileType`: Type of file (0=image, 1=gif, 2=video, 3=text)
   - `contentType`: Content descriptor (typically "FILE")
   - `sections`: Object indicating which sections are present (`{"FILE":true,"THUMBNAIL":true,"NOTE":false}`)
3. Newline character (`0x0A`)
4. FILE section marker (0x00) + 4-byte size + file data
5. THUMBNAIL section marker (0x01) + 4-byte size + thumbnail data (if present)
6. NOTE section marker (0x02) + 4-byte size + note data (if present)
7. END marker (0xFF)

## Decrypting

### Determining Encryption Mode
1. Read header: [version:5][salt:16][iv:12][iteration_count:4]
2. Extract flags from iteration_count:
   - If AEAD_FLAG set: Use ChaCha20-Poly1305 AEAD decryption
   - If STREAM_FLAG set: Use SecretStream decryption
   - If neither: Use legacy ChaCha20 with check bytes

### AEAD Mode Decryption
1. Read remaining file as ciphertext + 16-byte Poly1305 tag
2. Derive key using Argon2id (if ARGON2_FLAG) or PBKDF2
3. Decrypt with ChaCha20-Poly1305, header as AAD
4. Authentication failure indicates wrong password or tampering
5. Parse decrypted content (JSON + sections)

### SecretStream Mode Decryption
1. Read 24-byte SecretStream header
2. Derive key using Argon2id (if ARGON2_FLAG) or PBKDF2
3. Initialize SecretStream decryption state
4. Decrypt chunks (64KB) with per-chunk authentication
5. Verify TAG_FINAL on last chunk to detect truncation
6. Parse decrypted content (JSON + sections)

### Section Reading
After decryption, read sections sequentially by markers:
- **FILE section (0x00)**: Main file data
- **THUMBNAIL section (0x01)**: Thumbnail image data
- **NOTE section (0x02)**: Associated note text
- **END marker (0xFF)**: End of sections

## Security Benefits of V5
- **No metadata leakage through filenames**: All files have random 32-char names
- **Atomic file structure**: Eliminates need for separate thumbnail/note files that correlate together
- **Single encryption context**: All related data encrypted together without external correlations
- **Version transparency**: File version is determined by reading encrypted header, not by filename patterns
- **Authenticated encryption**: AEAD and SecretStream modes provide integrity protection against tampering
- **Memory-hard KDF**: Argon2id resists GPU/ASIC brute-force attacks
- **Streaming for large files**: SecretStream enables encryption/decryption of large files without loading into memory
- **Per-chunk authentication**: SecretStream provides integrity verification at chunk level, detecting truncation and corruption
- **No disk cache**: All decrypted data remains in memory only, preventing data leakage through cache files
- **Secure memory management**: Sensitive data (keys, decrypted content) is securely wiped from memory after use
- **Root detection**: App blocks usage on rooted/compromised devices to prevent key extraction

