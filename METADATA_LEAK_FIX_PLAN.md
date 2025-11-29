# Plano de Implementação - Fase 1: Eliminação de Sufixos Reveladores

## 🎯 Objetivo

Remover os sufixos `.thumb.valv` e `.note.valv` que revelam a existência e tipo de arquivos secundários, substituindo-os por um único sufixo genérico `.valv` com metadados criptografados.

## 📋 Mudanças Requeridas

### 1. Encryption.java

#### Remover Sufixos Reveladores
```java
// REMOVER:
public static final String SUFFIX_GENERIC_THUMB = ".thumb.valv";
public static final String SUFFIX_GENERIC_NOTE = ".note.valv";

// MANTER:
public static final String SUFFIX_GENERIC_FILE = ".valv";
```

#### Adicionar Campo de Tipo de Conteúdo
```java
// Novo campo em JSON
private static final String JSON_CONTENT_TYPE = "contentType";

// Enum para tipos
public enum ContentType {
    FILE(0),
    THUMBNAIL(1),
    NOTE(2);
    
    public final int value;
    ContentType(int value) { this.value = value; }
}
```

#### Atualizar Escrita de JSON
```java
// Ao criptografar
JSONObject json = new JSONObject();
json.put(JSON_ORIGINAL_NAME, sourceFileName);
json.put(JSON_FILE_TYPE, fileType);
json.put(JSON_CONTENT_TYPE, contentType);  // ← NOVO
cipherOutputStream.write(("\n" + json + "\n").getBytes(StandardCharsets.UTF_8));
```

#### Atualizar Leitura de JSON
```java
// Ao descriptografar
JSONObject json = new JSONObject(new String(jsonBytes, StandardCharsets.UTF_8));
String originalName = json.has(JSON_ORIGINAL_NAME) ? json.getString(JSON_ORIGINAL_NAME) : "";
int fileType = json.has(JSON_FILE_TYPE) ? json.getInt(JSON_FILE_TYPE) : -1;
int contentType = json.has(JSON_CONTENT_TYPE) ? json.getInt(JSON_CONTENT_TYPE) : 0;  // ← NOVO
```

#### Atualizar Streams para Armazenar ContentType
```java
public static class Streams {
    private final InputStream inputStream;
    private final CipherOutputStream outputStream;
    private final SecretKey secretKey;
    private final String originalFileName, inputString;
    private final int fileType;
    private final int contentType;  // ← NOVO

    // Atualizar todos os construtores para inicializar contentType
    // Padrão: ContentType.FILE (0) se não especificado
}
```

### 2. FileStuff.java

#### Atualizar Detecção de Thumbnails e Notas
```java
// ANTES:
if (name.endsWith(Encryption.SUFFIX_GENERIC_THUMB)) {
    documentThumbs.add(file);
} else if (name.endsWith(Encryption.SUFFIX_GENERIC_NOTE)) {
    documentNote.add(file);
}

// DEPOIS:
// Usar método helper para detectar contentType
int contentType = detectContentType(file);
if (contentType == ContentType.THUMBNAIL) {
    documentThumbs.add(file);
} else if (contentType == ContentType.NOTE) {
    documentNote.add(file);
}

// Método helper
private static int detectContentType(CursorFile file, Context context) {
    // Ler arquivo, descriptografar JSON, extrair contentType
    // Fallback: tentar ler primeiro arquivo para determinar
}
```

#### Atualizar copyTo() e moveTo()
```java
// ANTES:
String thumbSuffix = version >= 3 ? Encryption.SUFFIX_GENERIC_THUMB : ...
DocumentFile thumbFile = directory.createFile("", ... + thumbSuffix);

// DEPOIS:
// Agora tudo usa .valv, contentType diferencia
String fileSuffix = Encryption.SUFFIX_GENERIC_FILE;  // .valv para tudo!
DocumentFile thumbFile = directory.createFile("", generatedName + fileSuffix);
```

### 3. Encryption.java - Métodos de Importação

#### importFileToDirectory()
```java
// Atualizar para usar ContentType.FILE
createFile(..., fileType, ContentType.FILE, ...);
createThumb(..., fileType, ContentType.THUMBNAIL, ...);
```

#### importNoteToDirectory()
```java
// Usar ContentType.NOTE
createTextFile(..., fileType, ContentType.NOTE, ...);
```

#### Métodos createFile, createThumb, createTextFile
```java
// Assinatura nova
private static void createFile(..., int contentType) {
    // Passar contentType para getCipherOutputStream
}

// getCipherOutputStream
private static Streams getCipherOutputStream(..., int contentType) {
    // Escrever contentType no JSON
}
```

### 4. GalleryFile.java (Se Existir)

#### Adicionar Field ContentType
```java
private int contentType;  // 0 = file, 1 = thumbnail, 2 = note

public int getContentType() {
    return contentType;
}

public void setContentType(int contentType) {
    this.contentType = contentType;
}
```

## 🔄 Fluxo de Detecção

### Problema: Como Saber qual é Thumbnail/Nota Se Todos Têm o Mesmo Sufixo?

**Solução: Ler Metadados Criptografados**

```
Passo 1: Encontrar arquivo com sufixo .valv
Passo 2: Tentar descriptografar
Passo 3: Ler JSON → contentType
Passo 4: Classificar como FILE / THUMBNAIL / NOTE
```

**Otimização: Cache Local**
```
Ao primeiro acesso, armazenar contentType em memória:
{
  "fileUri": "content://...",
  "contentType": 0  // 0 = FILE, 1 = THUMBNAIL, 2 = NOTE
}
```

## ⚠️ Compatibilidade com V1/V2

### V1 Files
- Não têm `.thumb.valv` ou `.note.valv`
- Continuam funcionando normalmente
- Novo código ignora contentType (default = FILE)

### V2 Files
- Podem ter `-t.valv` (thumb) e `-n.valv` (note)
- Continuam sendo detectados e carregados
- Novo código não interfere

### V3 Files
- Novo formato com contentType em JSON
- Método detectContentType() diferencia tipos

## 📊 Mapeamento de Nomes

### Antes (V3 Atual - com leak)
```
Arquivo principal:  "a1b2c3d4e5f6g7h8.valv"
Thumbnail:         "a1b2c3d4e5f6g7h8.thumb.valv"  ← PROBLEMA
Nota:              "a1b2c3d4e5f6g7h8.note.valv"   ← PROBLEMA
```

### Depois (V3 Fase 1 - sem leak)
```
Arquivo principal:  "a1b2c3d4e5f6g7h8.valv"        (contentType=0)
Thumbnail:         "a1b2c3d4e5f6g7h8.valv"        (contentType=1)
Nota:              "a1b2c3d4e5f6g7h8.valv"        (contentType=2)

Nenhum índice ou sufixo diferenciado!
```

## 🧪 Casos de Teste

### Teste 1: Importar Arquivo V3
```
1. Importar arquivo com importFileToDirectory(..., 3)
2. Verificar que resultam 3 arquivos .valv
3. Verificar que contentType[0] = FILE, [1] = THUMBNAIL, [2] = NOTE
4. Verificar que não há sufixos reveladores
```

### Teste 2: Backward Compat V1
```
1. Arquivo V1 existing
2. Ler com getCipherInputStream(..., 1)
3. Verificar que contentType = 0 (default)
4. Funciona normalmente
```

### Teste 3: Backward Compat V2
```
1. Arquivo V2 existing com -t.valv e -n.valv
2. Ler com getCipherInputStream(..., 2)
3. Detectar pelo sufixo antigo
4. Converter contentType para novo formato (lazy)
```

## 🚀 Plano de Implementação

### Ordem Recomendada
1. ✅ Adicionar JSON_CONTENT_TYPE em Encryption.java
2. ✅ Adicionar enum ContentType
3. ✅ Atualizar Streams com field contentType
4. ✅ Atualizar getCipherInputStream para ler contentType
5. ✅ Atualizar getCipherOutputStream para escrever contentType
6. ✅ Atualizar métodos de importação
7. ✅ Atualizar FileStuff.java para detectar contentType
8. ✅ Atualizar copyTo/moveTo
9. ✅ Adicionar detectContentType() helper
10. ✅ Testes

## 📈 Ganho de Segurança

| Vetor de Ataque | V2 | V3 Atual | V3 F1 |
|-----------------|----|---------|-|
| Tipo via sufixo | ✅ Explorado | ⚠️ Parcial | ✅ Fechado |
| Relação óbvia   | ✅ Explorado | ⚠️ Óbvia | ⚠️ Óbvia |
| Forense do FS   | ⚠️ Fraco | ⚠️ Fraco | ✅ Forte |
| Correlação por nome | ✅ Explorado | ✅ Explorado | ⚠️ Presente |

**Melhoria**: De 7/10 → 8/10 de segurança

## ⏭️ Próxima Fase (V3 Fase 2)

Após Fase 1 estabilizar:
- Implementar Arquivo Composto (tudo em um .valv)
- Eliminar correlação por nome
- Chegar a 10/10 de segurança

