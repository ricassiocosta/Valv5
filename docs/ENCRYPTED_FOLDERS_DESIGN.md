# Design Document: Encrypted Folders Feature

## Resumo Executivo

Esta feature permite que usuários criem sub-pastas dentro das root folders importadas, com nomes criptografados em disco. O nome original (descriptografado) é exibido apenas dentro do app após autenticação.

## Problema

Atualmente, o Valv5 permite apenas importar pastas existentes (root folders) do sistema de arquivos. Não há suporte para criar sub-pastas com nomes protegidos. Isso limita a organização dos arquivos e pode expor informações sensíveis através dos nomes das pastas.

## Objetivos

1. **Privacidade**: Nomes de pastas não devem revelar seu conteúdo quando visualizados fora do app
2. **Organização**: Permitir estrutura hierárquica de pastas dentro do vault
3. **Compatibilidade**: Funcionar no Android e na CLI
4. **Consistência**: Seguir os mesmos padrões de criptografia V5 existentes

---

## Design Proposto

### 1. Estrutura de Nomes de Pastas Criptografadas

#### Formato do Nome em Disco
```
<base64url_encrypted_name>
```

**Estrutura do nome criptografado (antes do base64url):**
```
[salt:16 bytes][iv:12 bytes][ciphertext:N bytes][poly1305_tag:16 bytes]
```

**Exemplo de fluxo:**
```
Nome original: "Minhas Fotos 2024" (17 chars)
        ↓
    encrypt (ChaCha20-Poly1305 AEAD)
        ↓
    [salt:16][iv:12][ciphertext:17][tag:16] = 61 bytes
        ↓
    base64url encode (sem padding)
        ↓
    "SGVsbG9Xb3JsZCFUaGlzSXNBVGVzdC4uLg" (~82 chars)
```

**Justificativa:**
- **Sem prefixo** - não há link visual entre a pasta e o app Valv
- Nome é apenas uma string base64url (parece hash/UUID genérico)
- Qualquer pasta dentro de uma root folder é tratada como pasta Valv
- Pastas que não descriptografam corretamente mostram o nome original

### 2. Lógica de Identificação

```
┌─────────────────────────────────────────────────────────────────┐
│              IDENTIFICAÇÃO DE PASTAS                            │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  Premissa: Toda subfolder dentro de uma root folder             │
│            é potencialmente uma pasta criptografada             │
│                                                                 │
│  Para cada pasta encontrada:                                    │
│                                                                 │
│  1. Tentar decodificar nome como base64url                      │
│     └─ Se falhar → mostrar nome original                        │
│                                                                 │
│  2. Tentar descriptografar com a senha do vault                 │
│     └─ Se falhar → mostrar nome original                        │
│                                                                 │
│  3. Se sucesso → mostrar nome descriptografado                  │
│                                                                 │
│  Resultado: Pastas normais e criptografadas coexistem           │
│             sem problemas                                       │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

### 3. Limite de Tamanho do Nome

**Limite: 30 caracteres para o nome original**

**Cálculo de viabilidade:**
```
Nome original: 30 chars UTF-8 = até 120 bytes (worst case com emojis/acentos)
+ Salt: 16 bytes
+ IV: 12 bytes
+ Tag: 16 bytes
= 164 bytes máximo

Base64URL: ceil(164 × 4/3) = 219 chars
= 219 chars total ✅ (bem abaixo do limite de 255 do filesystem)
```

**Validação na UI:**
- Mostrar contador de caracteres restantes
- Bloquear input acima de 30 chars
- Mensagem: "Nome da pasta (máx. 30 caracteres)"

### 4. Processo de Criptografia do Nome

```
┌─────────────────────────────────────────────────────────────────┐
│                    CRIAÇÃO DE PASTA                             │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  1. Usuário digita: "Fotos de Viagem" (≤30 chars)               │
│                          ↓                                      │
│  2. Validar tamanho (≤30 chars)                                 │
│                          ↓                                      │
│  3. Gerar salt (16 bytes) e IV (12 bytes) aleatórios            │
│                          ↓                                      │
│  4. Derivar chave com Argon2id (password + salt)                │
│                          ↓                                      │
│  5. Encrypt nome com ChaCha20-Poly1305 AEAD                     │
│                          ↓                                      │
│  6. Concatenar: [salt][iv][ciphertext][tag]                     │
│                          ↓                                      │
│  7. Base64URL encode (sem padding '=')                          │
│                          ↓                                      │
│  8. Criar pasta com esse nome                                   │
│                          ↓                                      │
│  9. Atualizar UI com nome original                              │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

### 5. Processo de Leitura/Descriptografia do Nome

```
┌─────────────────────────────────────────────────────────────────┐
│                    LEITURA DE PASTA                             │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  1. Scan encontra pasta: "SGVsbG9Xb3JsZC4uLg..."                │
│                          ↓                                      │
│  2. Tentar Base64URL decode → bytes                             │
│     └─ Se falhar: mostrar nome original, FIM                    │
│                          ↓                                      │
│  3. Verificar tamanho mínimo (≥44 bytes: salt+iv+tag)           │
│     └─ Se menor: mostrar nome original, FIM                     │
│                          ↓                                      │
│  4. Separar: [salt:16][iv:12][ciphertext:N][tag:16]             │
│                          ↓                                      │
│  5. Derivar chave com Argon2id (password + salt)                │
│                          ↓                                      │
│  6. Tentar Decrypt com ChaCha20-Poly1305 AEAD                   │
│     └─ Se falhar (tag inválida): mostrar nome original, FIM     │
│                          ↓                                      │
│  7. Resultado: nome descriptografado                            │
│                          ↓                                      │
│  8. Cache do nome para evitar re-decrypt                        │
│                          ↓                                      │
│  9. Exibir nome descriptografado na UI                          │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

---

## Implementação Android

### 6. Modificações no `GalleryFile.java`

```java
public class GalleryFile implements Comparable<GalleryFile> {
    // Campos existentes...
    
    // Novos campos para pastas criptografadas
    private boolean isEncryptedFolder;
    private String decryptedFolderName;  // Nome descriptografado (null se não conseguiu descriptografar)
    
    /**
     * Construtor para pastas.
     * Se decryptedName != null, é uma pasta criptografada com sucesso.
     * Se decryptedName == null, mostra o nome original do filesystem.
     */
    private GalleryFile(@NonNull CursorFile folder, @Nullable String decryptedName) {
        this.fileUri = folder.getUri();
        this.encryptedName = folder.getName();
        this.name = decryptedName != null ? decryptedName : folder.getName();
        this.isDirectory = true;
        this.isEncryptedFolder = decryptedName != null;
        this.decryptedFolderName = decryptedName;
        // ... outros campos
    }
    
    public boolean isEncryptedFolder() {
        return isEncryptedFolder;
    }
    
    public String getDisplayName() {
        return decryptedFolderName != null ? decryptedFolderName : name;
    }
}
```

### 7. Novo Método em `Encryption.java`

```java
public class Encryption {
    
    public static final int MAX_FOLDER_NAME_LENGTH = 30;
    private static final int MIN_ENCRYPTED_FOLDER_NAME_BYTES = SALT_LENGTH + IV_LENGTH + POLY1305_TAG_LENGTH; // 44 bytes
    
    /**
     * Cria uma pasta criptografada com nome protegido.
     * O nome é criptografado e codificado em base64url como nome da pasta.
     * 
     * @param context Android context
     * @param parentDirectory Pasta pai onde criar
     * @param originalName Nome original (máx 30 chars, será criptografado)
     * @param password Senha do vault
     * @return DocumentFile da pasta criada, ou null se falhar
     */
    public static DocumentFile createEncryptedFolder(
            FragmentActivity context,
            DocumentFile parentDirectory,
            String originalName,
            char[] password) throws GeneralSecurityException, IOException {
        
        // 1. Validar tamanho do nome
        if (originalName == null || originalName.isEmpty()) {
            throw new IllegalArgumentException("Folder name cannot be empty");
        }
        if (originalName.length() > MAX_FOLDER_NAME_LENGTH) {
            throw new IllegalArgumentException("Folder name exceeds " + MAX_FOLDER_NAME_LENGTH + " characters");
        }
        
        // 2. Gerar salt e IV
        byte[] salt = new byte[SALT_LENGTH];
        byte[] iv = new byte[IV_LENGTH];
        SecureRandom random = new SecureRandom();
        random.nextBytes(salt);
        random.nextBytes(iv);
        
        // 3. Derivar chave
        SecretKey key = deriveKeyArgon2(password, salt);
        
        // 4. Criptografar nome com AEAD
        byte[] nameBytes = originalName.getBytes(StandardCharsets.UTF_8);
        Cipher cipher = Cipher.getInstance(CIPHER_AEAD);
        AlgorithmParameterSpec params = new IvParameterSpec(iv);
        cipher.init(Cipher.ENCRYPT_MODE, key, params);
        byte[] ciphertext = cipher.doFinal(nameBytes);  // Inclui tag de 16 bytes
        
        // 5. Concatenar: [salt][iv][ciphertext+tag]
        byte[] encrypted = new byte[SALT_LENGTH + IV_LENGTH + ciphertext.length];
        System.arraycopy(salt, 0, encrypted, 0, SALT_LENGTH);
        System.arraycopy(iv, 0, encrypted, SALT_LENGTH, IV_LENGTH);
        System.arraycopy(ciphertext, 0, encrypted, SALT_LENGTH + IV_LENGTH, ciphertext.length);
        
        // 6. Base64URL encode (sem padding)
        String folderName = Base64.encodeToString(encrypted, Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
        
        // 7. Criar pasta
        DocumentFile folder = parentDirectory.createDirectory(folderName);
        
        // 8. Limpar chave da memória
        SecureMemoryManager.destroyKey(key);
        
        return folder;
    }
    
    /**
     * Tenta descriptografar o nome de uma pasta.
     * Retorna null se não for uma pasta criptografada válida ou se a descriptografia falhar.
     * 
     * @param folderName Nome da pasta (string base64url)
     * @param password Senha do vault
     * @return Nome original, ou null se falhar
     */
    @Nullable
    public static String decryptFolderName(String folderName, char[] password) {
        if (folderName == null || folderName.isEmpty()) {
            return null;
        }
        
        try {
            // 1. Tentar decodificar base64url
            byte[] encrypted;
            try {
                encrypted = Base64.decode(folderName, Base64.URL_SAFE | Base64.NO_PADDING);
            } catch (IllegalArgumentException e) {
                // Não é base64 válido - pasta normal
                return null;
            }
            
            // 2. Verificar tamanho mínimo
            if (encrypted.length < MIN_ENCRYPTED_FOLDER_NAME_BYTES) {
                return null;  // Muito pequeno para ser válido
            }
            
            // 3. Separar componentes
            byte[] salt = Arrays.copyOfRange(encrypted, 0, SALT_LENGTH);
            byte[] iv = Arrays.copyOfRange(encrypted, SALT_LENGTH, SALT_LENGTH + IV_LENGTH);
            byte[] ciphertext = Arrays.copyOfRange(encrypted, SALT_LENGTH + IV_LENGTH, encrypted.length);
            
            // 4. Derivar chave
            SecretKey key = deriveKeyArgon2(password, salt);
            
            // 5. Descriptografar
            Cipher cipher = Cipher.getInstance(CIPHER_AEAD);
            AlgorithmParameterSpec params = new IvParameterSpec(iv);
            cipher.init(Cipher.DECRYPT_MODE, key, params);
            byte[] nameBytes = cipher.doFinal(ciphertext);
            
            // 6. Limpar chave
            SecureMemoryManager.destroyKey(key);
            
            // 7. Validar que é UTF-8 válido
            String result = new String(nameBytes, StandardCharsets.UTF_8);
            
            // 8. Validar que não está vazio e tem tamanho razoável
            if (result.isEmpty() || result.length() > MAX_FOLDER_NAME_LENGTH) {
                return null;
            }
            
            return result;
            
        } catch (AEADBadTagException e) {
            // Tag inválida - senha errada ou não é pasta criptografada
            return null;
        } catch (Exception e) {
            SecureLog.e(TAG, "Error decrypting folder name", e);
            return null;
        }
    }
}
```

### 8. Modificações no `FileStuff.java`

```java
public class FileStuff {
    
    @NonNull
    public static List<GalleryFile> getFilesInFolder(Context context, Uri pickedDir, boolean checkDecryptable) {
        // ... código existente ...
        
        for (CursorFile file : files) {
            String name = file.getName();
            
            if (file.isDirectory()) {
                // Tentar descriptografar o nome (pode ser pasta criptografada ou normal)
                String cachedName = FolderNameCache.get(name);
                String decryptedName;
                
                if (cachedName != null) {
                    decryptedName = cachedName;
                } else {
                    decryptedName = Encryption.decryptFolderName(name, Password.getPassword());
                    if (decryptedName != null) {
                        FolderNameCache.put(name, decryptedName);
                    }
                }
                
                // Se descriptografou, é pasta criptografada; senão, mostra nome original
                galleryFiles.add(GalleryFile.asDirectory(file, decryptedName));
                continue;
            }
            
            // ... resto do código para arquivos ...
        }
    }
}
```

### 9. UI - Dialog para Criar Pasta

Novo dialog `CreateFolderDialogFragment.java`:

```java
public class CreateFolderDialogFragment extends DialogFragment {
    
    private static final int MAX_NAME_LENGTH = 30;
    
    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        AlertDialog.Builder builder = new AlertDialog.Builder(requireContext());
        
        View view = getLayoutInflater().inflate(R.layout.dialog_create_folder, null);
        TextInputEditText editFolderName = view.findViewById(R.id.editFolderName);
        TextInputLayout inputLayout = view.findViewById(R.id.inputLayout);
        
        // Configurar contador de caracteres
        inputLayout.setCounterEnabled(true);
        inputLayout.setCounterMaxLength(MAX_NAME_LENGTH);
        
        // Limitar input
        editFolderName.setFilters(new InputFilter[] {
            new InputFilter.LengthFilter(MAX_NAME_LENGTH)
        });
        
        builder.setTitle(R.string.create_folder)
               .setView(view)
               .setPositiveButton(R.string.create, (dialog, which) -> {
                   String folderName = editFolderName.getText().toString().trim();
                   if (!folderName.isEmpty()) {
                       createEncryptedFolder(folderName);
                   }
               })
               .setNegativeButton(R.string.cancel, null);
        
        return builder.create();
    }
    
    private void createEncryptedFolder(String name) {
        // Executar em background thread
        new Thread(() -> {
            try {
                DocumentFile folder = Encryption.createEncryptedFolder(
                    requireActivity(),
                    currentDirectory,
                    name,
                    Password.getPassword()
                );
                
                requireActivity().runOnUiThread(() -> {
                    if (folder != null) {
                        galleryViewModel.addFolder(folder, name);
                        Toast.makeText(getContext(), R.string.folder_created, Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(getContext(), R.string.error_creating_folder, Toast.LENGTH_SHORT).show();
                    }
                });
            } catch (Exception e) {
                requireActivity().runOnUiThread(() -> {
                    Toast.makeText(getContext(), R.string.error_creating_folder, Toast.LENGTH_SHORT).show();
                });
            }
        }).start();
    }
}
```

---

## Implementação CLI (Python)

### 10. Novo Módulo `folder_encryption.py`

```python
"""
Valv Folder Encryption Module

Provides functions to create and read encrypted folders.
Folder names are encrypted and stored as base64url in the folder name itself.
No prefix is used - any valid base64url that decrypts successfully is an encrypted folder.
"""

import os
import base64
import secrets
from pathlib import Path
from typing import Optional

from .crypto import encrypt_aead, decrypt_aead, derive_key_argon2

MAX_FOLDER_NAME_LENGTH = 30
SALT_LENGTH = 16
IV_LENGTH = 12
TAG_LENGTH = 16
MIN_ENCRYPTED_LENGTH = SALT_LENGTH + IV_LENGTH + TAG_LENGTH  # 44 bytes


def create_encrypted_folder(
    parent_dir: str,
    original_name: str,
    password: str
) -> Optional[str]:
    """
    Create an encrypted folder with protected name.
    The name is encrypted and encoded as base64url as the folder name.
    
    Args:
        parent_dir: Path to parent directory
        original_name: Original folder name (max 30 chars, will be encrypted)
        password: Encryption password
        
    Returns:
        Path to created folder, or None on failure
        
    Raises:
        ValueError: If name is empty or exceeds 30 characters
    """
    # Validate name
    if not original_name or len(original_name) == 0:
        raise ValueError("Folder name cannot be empty")
    if len(original_name) > MAX_FOLDER_NAME_LENGTH:
        raise ValueError(f"Folder name exceeds {MAX_FOLDER_NAME_LENGTH} characters")
    
    # Generate salt and IV
    salt = secrets.token_bytes(SALT_LENGTH)
    iv = secrets.token_bytes(IV_LENGTH)
    
    # Derive key with Argon2id
    key = derive_key_argon2(password.encode(), salt)
    
    # Encrypt name with AEAD (returns ciphertext + 16-byte tag)
    name_bytes = original_name.encode('utf-8')
    ciphertext = encrypt_aead(key, iv, name_bytes)
    
    # Concatenate: [salt][iv][ciphertext+tag]
    encrypted = salt + iv + ciphertext
    
    # Base64URL encode (no padding)
    folder_name = base64.urlsafe_b64encode(encrypted).rstrip(b'=').decode('ascii')
    folder_path = Path(parent_dir) / folder_name
    
    # Create the folder
    folder_path.mkdir(parents=True, exist_ok=False)
    
    return str(folder_path)


def decrypt_folder_name(
    folder_name: str,
    password: str
) -> Optional[str]:
    """
    Try to decrypt a folder name.
    Returns None if the name is not a valid encrypted folder name.
    
    Args:
        folder_name: Folder name (base64url string)
        password: Decryption password
        
    Returns:
        Original folder name, or None if decryption fails
    """
    if not folder_name:
        return None
    
    try:
        # Try to decode base64url (add padding if needed)
        padding = 4 - (len(folder_name) % 4)
        if padding != 4:
            folder_name_padded = folder_name + '=' * padding
        else:
            folder_name_padded = folder_name
        
        try:
            encrypted = base64.urlsafe_b64decode(folder_name_padded)
        except Exception:
            # Not valid base64 - normal folder
            return None
        
        # Validate minimum length
        if len(encrypted) < MIN_ENCRYPTED_LENGTH:
            return None
        
        # Extract components
        salt = encrypted[:SALT_LENGTH]
        iv = encrypted[SALT_LENGTH:SALT_LENGTH + IV_LENGTH]
        ciphertext = encrypted[SALT_LENGTH + IV_LENGTH:]
        
        # Derive key
        key = derive_key_argon2(password.encode(), salt)
        
        # Decrypt (will raise if tag is invalid)
        name_bytes = decrypt_aead(key, iv, ciphertext)
        
        result = name_bytes.decode('utf-8')
        
        # Validate result
        if not result or len(result) > MAX_FOLDER_NAME_LENGTH:
            return None
        
        return result
        
    except Exception:
        # Decryption failed - not an encrypted folder or wrong password
        return None


def list_folders_with_names(
    directory: str,
    password: str
) -> list:
    """
    List all folders in a directory, attempting to decrypt folder names.
    
    Args:
        directory: Path to directory to scan
        password: Password for decryption
        
    Returns:
        List of tuples: (folder_path, display_name, is_encrypted)
    """
    results = []
    dir_path = Path(directory)
    
    for item in dir_path.iterdir():
        if not item.is_dir():
            continue
            
        folder_name = item.name
        
        # Try to decrypt
        decrypted = decrypt_folder_name(folder_name, password)
        
        if decrypted:
            # Successfully decrypted - encrypted folder
            results.append((str(item), decrypted, True))
        else:
            # Could not decrypt - show original name
            results.append((str(item), folder_name, False))
    
    return sorted(results, key=lambda x: x[1].lower())
```

### 11. Atualização do CLI Principal

```python
# Em valv_cli.py - adicionar subcomandos

def add_folder_subcommands(subparsers):
    """Add folder-related subcommands."""
    
    # create-folder
    create_parser = subparsers.add_parser(
        'create-folder', 
        help='Create an encrypted folder'
    )
    create_parser.add_argument('directory', help='Parent directory')
    create_parser.add_argument('name', help='Folder name (max 30 chars, will be encrypted)')
    create_parser.add_argument('password', help='Encryption password')
    
    # list-folders
    list_parser = subparsers.add_parser(
        'list-folders',
        help='List folders with decrypted names'
    )
    list_parser.add_argument('directory', help='Directory to scan')
    list_parser.add_argument('password', help='Password for decryption')


def handle_create_folder(args):
    """Handle create-folder command."""
    from valv_encryptor.folder_encryption import create_encrypted_folder
    
    try:
        folder_path = create_encrypted_folder(
            parent_dir=args.directory,
            original_name=args.name,
            password=args.password
        )
        
        print(f"✅ Folder created successfully")
        print(f"   📁 Path: {folder_path}")
        print(f"   🏷️  Name: {args.name}")
        return 0
        
    except ValueError as e:
        print(f"❌ Error: {e}")
        return 1
    except Exception as e:
        print(f"❌ Failed to create folder: {e}")
        return 1


def handle_list_folders(args):
    """Handle list-folders command."""
    from valv_encryptor.folder_encryption import list_folders_with_names
    
    folders = list_folders_with_names(args.directory, args.password)
    
    if not folders:
        print("No folders found.")
        return 0
    
    print(f"\n📂 Folders in {args.directory}:\n")
    print(f"{'Name':<35} {'Type':<15}")
    print("-" * 50)
    
    for path, name, is_encrypted in folders:
        folder_type = "🔒 encrypted" if is_encrypted else "📁 normal"
        print(f"{name:<35} {folder_type:<15}")
    
    print(f"\nTotal: {len(folders)} folder(s)")
    return 0
```

---

## Limitações do Android (SAF)

### 12. Considerações do Storage Access Framework

O Android usa o Storage Access Framework (SAF) para acesso a arquivos, o que impõe algumas limitações:

| Operação | Suporte SAF | Notas |
|----------|-------------|-------|
| `createDirectory()` | ✅ | Funciona normalmente |
| `listFiles()` | ✅ | Via ContentResolver query |
| Nomes base64url | ✅ | Caracteres válidos para filesystem |
| Renomear pasta | ❌ | Não suportado pelo SAF - ver seção de renomeação |

### 13. Cache de Nomes Descriptografados (Apenas em Memória)

Como a derivação de chave Argon2id é computacionalmente cara, é importante cachear os nomes.

**⚠️ IMPORTANTE: O cache é APENAS em memória (RAM). Nenhum dado descriptografado é persistido em disco.**

O cache é automaticamente limpo quando:
- O app é fechado
- O usuário faz logout/lock
- A senha é alterada

```java
public class FolderNameCache {
    // LRU cache IN-MEMORY ONLY: encrypted_name -> decrypted_name
    // NEVER persisted to disk - cleared on app close/lock
    private static final LruCache<String, String> cache = new LruCache<>(100);
    
    public static void put(String encryptedName, String originalName) {
        cache.put(encryptedName, originalName);
    }
    
    @Nullable
    public static String get(String encryptedName) {
        return cache.get(encryptedName);
    }
    
    /**
     * Clear all cached folder names.
     * MUST be called on app lock/logout to prevent data leakage.
     */
    public static void clear() {
        cache.evictAll();
    }
}

// Uso no FileStuff:
String decryptedName = FolderNameCache.get(folderName);
if (decryptedName == null) {
    decryptedName = Encryption.decryptFolderName(folderName, password);
    if (decryptedName != null) {
        FolderNameCache.put(folderName, decryptedName);
    }
}

// No Password.lock() ou MainActivity.onDestroy():
FolderNameCache.clear();
```

---

## Renomeação de Pastas

### 14. Fluxo de Renomeação

Como o nome está criptografado no próprio nome da pasta, renomear requer criar uma nova pasta:

```
┌─────────────────────────────────────────────────────────────────┐
│                    RENOMEAR PASTA                               │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  1. Usuário solicita renomear para "Novo Nome"                 │
│                          ↓                                      │
│  2. Criar nova pasta criptografada com "Novo Nome"             │
│                          ↓                                      │
│  3. Mover todos os arquivos da pasta antiga → nova             │
│                          ↓                                      │
│  4. Deletar pasta antiga (vazia)                               │
│                          ↓                                      │
│  5. Atualizar cache e UI                                       │
│                                                                 │
│  ⚠️ Operação mais pesada que com arquivo de manifesto          │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

---

## Migração e Compatibilidade

### 15. Pastas Normais vs Criptografadas

O sistema tenta descriptografar todas as pastas:

| Resultado da descriptografia | Comportamento |
|------------------------------|---------------|
| Sucesso | Mostra nome descriptografado, marca como `isEncryptedFolder = true` |
| Falha (base64 inválido) | Mostra nome original do filesystem |
| Falha (AEAD tag inválida) | Mostra nome original do filesystem |
| Falha (resultado > 30 chars) | Mostra nome original do filesystem |

**Exemplos:**
```
/vault/
├── SGVsbG9Xb3JsZC...    → descriptografa → "Minhas Fotos"
├── Vacation2024         → falha base64   → "Vacation2024"
├── xYz123AbC...         → falha AEAD     → "xYz123AbC..."
└── arquivo.jpg          → (é arquivo, não pasta)
```

### 16. Backward Compatibility

- Versões antigas do app verão o nome base64 (string sem sentido)
- CLI antiga pode processar os arquivos dentro normalmente
- Pastas normais (não criptografadas) continuam funcionando
- Não há breaking changes - feature é 100% aditiva

---

## Segurança

### 17. Análise de Segurança

| Aspecto | Tratamento |
|---------|------------|
| **Nome original** | Criptografado com ChaCha20-Poly1305 AEAD |
| **Derivação de chave** | Argon2id (64MB, 3 iterações) |
| **Salt** | 16 bytes aleatórios por pasta (único) |
| **IV/Nonce** | 12 bytes aleatórios por pasta (único) |
| **Integridade** | Poly1305 MAC (16 bytes) protege contra tampering |
| **Identificação** | Nenhum prefixo ou marcador - apenas base64url genérico |

### 18. Ameaças Mitigadas

1. **Observador do filesystem**: Vê apenas string base64 (parece hash/UUID genérico)
2. **Link com o app**: Nenhum prefixo ou extensão que identifique o Valv
3. **Backup não autorizado**: Nomes permanecem criptografados
4. **Análise de diretório**: Estrutura não revela organização real
5. **Tampering**: AEAD detecta modificações (pasta se torna inacessível)
6. **Replay/Copy**: Cada pasta tem salt/IV único

### 19. Informação Vazada

O que um atacante pode inferir:
- Tamanho aproximado do nome original (pelo tamanho do base64)
- Quantas sub-pastas existem
- Que existe algo ali (é uma pasta)

O que **NÃO** pode inferir:
- Que é uma pasta do Valv (sem prefixo identificador)
- Nome original da pasta
- Relação entre pastas (hierarquia lógica)
- Conteúdo ou tipo de arquivos dentro

---

## Plano de Implementação

### Fase 1: Core Android (Estimativa: 2-3 dias)
- [ ] Implementar `createEncryptedFolder()` em `Encryption.java`
- [ ] Implementar `decryptFolderName()` em `Encryption.java`
- [ ] Modificar `GalleryFile` para suportar pastas criptografadas
- [ ] Atualizar `FileStuff.getFilesInFolder()` para tentar descriptografar todas as pastas
- [ ] Adicionar `FolderNameCache` para performance

### Fase 2: UI Android (Estimativa: 2 dias)
- [ ] Criar layout `dialog_create_folder.xml` com contador de chars
- [ ] Criar `CreateFolderDialogFragment`
- [ ] Adicionar FAB/menu option para criar pasta
- [ ] Atualizar ícones/visual para diferenciar pastas criptografadas vs normais
- [ ] (Opcional) Implementar renomeação de pasta

### Fase 3: CLI Python (Estimativa: 1-2 dias)
- [ ] Implementar `folder_encryption.py`
- [ ] Adicionar subcomando `create-folder`
- [ ] Adicionar subcomando `list-folders`
- [ ] Atualizar `--preserve-structure` para criar pastas criptografadas

### Fase 4: Testes e Documentação (Estimativa: 1 dia)
- [ ] Testes unitários Android (encrypt/decrypt folder name)
- [ ] Testes de integração CLI
- [ ] Testes de compatibilidade Android ↔ CLI
- [ ] Atualizar ENCRYPTION.md
- [ ] Atualizar README.md

---

## Status de Implementação

### ✅ Implementado (Junho 2025)

| Componente | Arquivo | Status |
|------------|---------|--------|
| FolderNameCache | `encryption/FolderNameCache.java` | ✅ Criado |
| Métodos de criptografia | `encryption/Encryption.java` | ✅ Adicionados |
| GalleryFile updates | `data/GalleryFile.java` | ✅ Novos campos e métodos |
| Decrypt automático | `utils/FileStuff.java` | ✅ Integrado em getFilesInFolder() |
| Limpar cache no lock | `data/Password.java` | ✅ FolderNameCache.clear() |
| Criar pasta criptografada | `utils/FileStuff.java` | ✅ createEncryptedFolder() |
| CLI - criptografia de pasta | `valv-cli/ValvEncryptionCLI.java` | ✅ --encrypt-folder-name |
| CLI - Python wrapper | `valv-cli/valv_cli.py` | ✅ --encrypt-folders flag |

### 🔧 Pendente

| Item | Descrição |
|------|-----------|
| UI de criação | Diálogo para criar pasta criptografada no app |
| Adapter/ViewHolder | Exibir ícone diferente para pastas criptografadas |
| Testes | Unitários e de integração |
| Documentação | Atualizar ENCRYPTION.md e README.md |

---

## Decisões Tomadas

| Decisão | Escolha | Justificativa |
|---------|---------|---------------|
| Onde armazenar nome | No nome da pasta (base64) | Mais simples, sem arquivo extra |
| Limite de tamanho | 30 caracteres | Garante que cabe no filesystem (≤219 chars final) |
| Encoding | Base64URL | Filesystem-safe, sem `/` ou `+` |
| Prefixo | **Nenhum** | Sem link visual com o app Valv (privacidade) |
| KDF | Argon2id | Consistente com arquivos, resistente a GPU |
| Identificação | Tentativa de decrypt | Toda subfolder é potencialmente criptografada |

---

## Questões Resolvidas

1. ~~**Arquivo de metadados vs nome criptografado?**~~ → Nome criptografado direto (mais simples)
2. ~~**Prefixo da pasta?**~~ → **Nenhum** (melhor privacidade)
3. ~~**Limite de nome**~~ → 30 chars (cabe no filesystem com margem)
4. ~~**Como identificar pasta criptografada?**~~ → Tentar descriptografar, se falhar mostra nome original

## Questões em Aberto

1. **Pastas aninhadas**: Limitar profundidade máxima?
2. **Exclusão de pasta**: Deletar recursivamente ou exigir que esteja vazia?
3. **Move/Copy entre vaults**: Reencriptar com senha diferente?

---

## Referências

- [ENCRYPTION.md](/ENCRYPTION.md) - Documentação de criptografia V5
- [Android SAF Documentation](https://developer.android.com/guide/topics/providers/document-provider)
- [Argon2 RFC 9106](https://www.rfc-editor.org/rfc/rfc9106.html)
- [Base64URL RFC 4648](https://www.rfc-editor.org/rfc/rfc4648#section-5)
