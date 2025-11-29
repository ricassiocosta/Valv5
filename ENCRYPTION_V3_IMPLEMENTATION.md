# Implementação da Versão 3 de Criptografia - Eliminação de Vazamento de Tipo de Arquivo

## 📋 Resumo Executivo

A versão 3 de criptografia elimina o **vazamento de metadados de tipo de arquivo** armazenando o tipo dentro do arquivo criptografado em JSON, em vez de expô-lo no nome do arquivo.

### Problema Resolvido
- **V1 (Prefixo)**: `.valv.i.1-hash` → Tipo visível no prefixo
- **V2 (Sufixo)**: `hash-i.valv` → Tipo visível no sufixo
- **V3 (Metadados Criptografados)**: `hash.valv` → Tipo criptografado, nome opaco

## 🔒 Mudanças Principais

### 1. Novos Sufixos Genéricos (`Encryption.java`)

```java
public static final int ENCRYPTION_VERSION_3 = 3;
public static final String SUFFIX_GENERIC_FILE = ".valv";
public static final String SUFFIX_GENERIC_THUMB = ".thumb.valv";
public static final String SUFFIX_GENERIC_NOTE = ".note.valv";
```

**Impacto**: Todos os arquivos criptografados com V3 usam o mesmo sufixo opaco, escondendo o tipo original.

### 2. Armazenamento do Tipo em Metadados Criptografados

#### Estrutura V3:
```
1. VERSION SALT IV ITERATIONCOUNT CHECKBYTES CHECKBYTES_ENC
2. {originalName, fileType}\n
3. FILE DATA
```

O `fileType` (inteiro) é armazenado no JSON criptografado e só pode ser lido após descriptografação bem-sucedida.

**Constante adicionada**:
```java
private static final String JSON_FILE_TYPE = "fileType";
```

### 3. Atualização da Classe `Streams`

Adicionado field para armazenar tipo de arquivo:
```java
private final int fileType;

private Streams(@NonNull InputStream inputStream, @NonNull SecretKey secretKey, 
                @NonNull String originalFileName, int fileType) {
    // ...
}

public int getFileType() {
    return fileType;
}
```

### 4. Métodos de Criptografia - Novos Overloads

#### `getCipherOutputStream()`
```java
// Signature original (backward compat)
private static Streams getCipherOutputStream(...)

// Novo overload
private static Streams getCipherOutputStream(..., int fileType)
```

O novo overload inclui o `fileType` no JSON quando versão >= 3:
```java
if (fileType >= 0 && version >= ENCRYPTION_VERSION_3) {
    json.put(JSON_FILE_TYPE, fileType);
}
```

#### `getTextCipherOutputStream()`
Similar ao acima - novo overload com suporte a `fileType`.

### 5. Métodos de Descriptografia - Suporte V3

**`getCipherInputStream()`** atualizado para ler versão e tipo:
```java
final int DETECTED_VERSION = fromByteArray(versionBytes);
// ...
JSONObject json = new JSONObject(...);
int fileType = json.has(JSON_FILE_TYPE) ? json.getInt(JSON_FILE_TYPE) : -1;
return new Streams(cipherInputStream, secretKey, originalName, fileType);
```

### 6. FileType com Variantes V3

Adicionadas em `FileType.java`:
```java
IMAGE_V3(1, ".jpg", Encryption.SUFFIX_GENERIC_FILE, 3),
GIF_V3(2, ".gif", Encryption.SUFFIX_GENERIC_FILE, 3),
VIDEO_V3(3, ".mp4", Encryption.SUFFIX_GENERIC_FILE, 3),
TEXT_V3(4, ".txt", Encryption.SUFFIX_GENERIC_FILE, 3),
```

**Novo método helper**:
```java
@NonNull
public static FileType fromTypeAndVersion(int type, int version) {
    // Converte inteiro + versão para FileType enum
}
```

### 7. Funções de Importação Atualizadas

#### `importFileToDirectory()` - Novo Overload
```java
public static Pair<Boolean, Boolean> importFileToDirectory(
    FragmentActivity context, DocumentFile sourceFile, DocumentFile directory,
    char[] password, int version, int fileType,  // ← NEW
    @Nullable IOnProgress onProgress, AtomicBoolean interrupted)
```

O método original agora detecta automaticamente o tipo do arquivo:
```java
int fileType = getFileTypeFromMime(sourceFile.getType());
return importFileToDirectory(..., fileType, ...);
```

**Novo método helper**:
```java
public static int getFileTypeFromMime(@Nullable String mimeType)
```

#### `importNoteToDirectory()` e `importTextToDirectory()`
Ambos têm novos overloads com parâmetro `fileType`.

### 8. Detecção de Arquivos V3 em FileStuff

**`getEncryptedFilesInFolder()`** atualizado:
```java
// Detecta V3 generics
if (name.endsWith(Encryption.SUFFIX_GENERIC_THUMB)) { ... }
if (name.endsWith(Encryption.SUFFIX_GENERIC_NOTE)) { ... }
```

**`copyTo()` e `moveTo()`** atualizados para usar sufixos generics quando versão >= 3.

### 9. Atualização de Chamadas de Importação

**`ImportViewModel.java`** - Mudança crítica:
```java
// Antes (V2):
imported = Encryption.importFileToDirectory(..., 2, onProgress, interrupted);

// Depois (V3):
imported = Encryption.importFileToDirectory(..., 3, onProgress, interrupted);
```

O mesmo para `importTextToDirectory()`: `version 2` → `version 3`.

## 🔄 Backward Compatibility

### V1 e V2 Ainda Funcionam ✅

1. **Leitura**: `getCipherInputStream()` detecta a versão a partir do arquivo:
   - V1: Lê apenas originalName
   - V2+: Lê JSON com originalName e fileType (se presente)

2. **Cópia/Movimento**: `copyTo()` e `moveTo()` mantêm comportamento:
   - V1 → V1 (prefixos)
   - V2 → V2 (sufixos com tipo)
   - V3 → V3 (sufixos genéricos)

3. **Nenhuma Quebra**: Arquivos V1 e V2 continuam descriptografando normalmente.

## 🛡️ Segurança

### Antes (V2)
```
Arquivo no disco: "a1b2c3d4-e5f6.jpg.valv"
                          ↓ VAZAMENTO
                     Tipo visível
```

### Depois (V3)
```
Arquivo no disco: "a1b2c3d4e5f6g7h8.valv"
                           ↓ OPACO
                  Tipo criptografado, não visível
```

## 📊 Sumário de Arquivos Modificados

| Arquivo | Mudanças |
|---------|----------|
| `Encryption.java` | Versão 3, sufixos genéricos, métodos com fileType, detecção de versão |
| `FileType.java` | Variantes V3, método `fromTypeAndVersion()` |
| `FileStuff.java` | Detecção V3, atualização copyTo/moveTo |
| `ImportViewModel.java` | Versão 3 nas chamadas de importação |

## ✨ Benefícios

1. **Segurança**: Tipo de arquivo não é mais visível externamente
2. **Compatibilidade**: V1 e V2 continuam funcionando
3. **Escalabilidade**: Fácil adicionar novos tipos sem quebrar formato
4. **Flexibilidade**: Metadados adicionais podem ser armazenados no JSON

## 🔮 Próximos Passos (Future Work)

1. **Lazy Migration**: Converter V1/V2 → V3 opcionalmente
2. **Metadados Adicionais**: Armazenar MIME type no JSON para melhor compatibilidade
3. **Audit**: Verificar se há outros vazamentos de metadados
