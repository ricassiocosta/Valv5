# Valv5
An encrypted gallery vault for Android devices. Modified version of [Valv-Android](https://github.com/Arctosoft/Valv-Android)

## Features
- Supports images, GIFs, videos and text files
- Organise using folders
- The app requires no permissions
- Encrypted files are stored on-disk allowing for easy backups and transfers between devices
- Supports multiple vaults by the use of different passwords
- Day/night modes
- Add notes/text to files
- **No disk caching** - all decrypted data remains in memory only

## Encryption
Files are encrypted using modern authenticated encryption:
- **Key Derivation**: Argon2id (memory-hard, GPU/ASIC resistant) or PBKDF2 with HMAC-SHA512 (legacy)
- **Small Files (≤50 MB)**: ChaCha20-Poly1305 AEAD
- **Large Files (>50 MB)**: libsodium SecretStream (XChaCha20-Poly1305) for memory-efficient streaming

Read the full details in [ENCRYPTION.md](ENCRYPTION.md).

## Security Features
- **Authenticated encryption**: All data is protected against tampering
- **Secure memory management**: Keys and decrypted content are securely wiped from memory
- **Root detection**: App blocks usage on rooted/compromised devices
- **No metadata leakage**: Files use random 32-character names with no extensions

## Requirements
- Android 9 or newer

## 
This software is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
