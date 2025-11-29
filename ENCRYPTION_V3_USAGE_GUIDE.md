# Guia de Uso - Criptografia V3

## 🆕 O Que Mudou Para o Usuário?

### Visível para o Usuário
Praticamente **nada muda** na experiência do usuário. Arquivos novos são simplesmente criptografados com maior segurança.

### Invisível para o Usuário (Segurança)
- Tipo de arquivo agora é **criptografado** junto com o arquivo
- Não há mais sufixos que revelam o tipo (`-i.valv`, `-g.valv`, etc.)
- Todos os arquivos V3 têm o mesmo sufixo: `.valv`

---

## 🔧 Para Desenvolvedores

### 1. Importar Arquivo com V3

#### Automático (Recomendado)
```java
// O sistema detecta automaticamente o tipo do arquivo
Encryption.importFileToDirectory(
    activity,           // FragmentActivity
    sourceFile,         // DocumentFile a importar
    directory,          // DocumentFile de destino
    password.getPassword(),  // char[] senha
    3,                  // versão = 3 (novo padrão)
    onProgress,         // IOnProgress (opcional)
    interrupted         // AtomicBoolean
);
```

#### Explícito (Avançado)
```java
int fileType = Encryption.getFileTypeFromMime(sourceFile.getType());
Encryption.importFileToDirectory(
    activity, sourceFile, directory, password, 3, fileType, onProgress, interrupted
);
```

### 2. Ler Arquivo V3

```java
// Ao descriptografar, tipo é extraído
Streams streams = Encryption.getCipherInputStream(
    inputStream, password, false, 3
);

String originalName = streams.getOriginalFileName();
int fileTypeInt = streams.getFileType();  // Tipo do arquivo

// Converter para enum
FileType fileType = FileType.fromTypeAndVersion(fileTypeInt, 3);

if (fileType.isImage()) {
    // É uma imagem
} else if (fileType.isVideo()) {
    // É vídeo
}
```

### 3. Copiar/Mover Arquivo V3

```java
// Suporta automaticamente V3
FileStuff.copyTo(context, galleryFile, destinationDirectory);
FileStuff.moveTo(context, galleryFile, destinationDirectory);

// Mantém versão original (V1→V1, V2→V2, V3→V3)
```

### 4. Importar Texto com V3

```java
// Nota
DocumentFile note = Encryption.importNoteToDirectory(
    activity, noteText, fileName, directory, password, 3
);

// Texto
DocumentFile text = Encryption.importTextToDirectory(
    activity, textContent, fileName, directory, password, 3
);
```

---

## 📊 Tipos de Arquivo (FileType)

```java
public enum FileType {
    DIRECTORY(0, ...),
    IMAGE_V3(1, ".jpg", SUFFIX_GENERIC_FILE, 3),
    GIF_V3(2, ".gif", SUFFIX_GENERIC_FILE, 3),
    VIDEO_V3(3, ".mp4", SUFFIX_GENERIC_FILE, 3),
    TEXT_V3(4, ".txt", SUFFIX_GENERIC_FILE, 3),
    // ... versões anteriores também suportadas
}

// Converter entre inteiro e FileType
FileType ft = FileType.fromTypeAndVersion(1, 3);  // IMAGE_V3
int type = ft.type;  // 1
int version = ft.version;  // 3
```

---

## 🔄 Compatibilidade com Versões Antigas

### Sistema Detecta Automaticamente

```java
// Não importa qual versão (1, 2 ou 3)
// O sistema funciona corretamente
Encryption.getCipherInputStream(inputStream, password, false, 1);  // V1 OK
Encryption.getCipherInputStream(inputStream, password, false, 2);  // V2 OK
Encryption.getCipherInputStream(inputStream, password, false, 3);  // V3 OK
```

### Operações com Versões Diferentes

```java
// V1 arquivo permanece V1
if (galleryFile.getVersion() == 1) {
    // Cópia mantém V1
    FileStuff.copyTo(context, galleryFile, directory);
}

// V2 arquivo permanece V2
if (galleryFile.getVersion() == 2) {
    // Cópia mantém V2
}

// V3 arquivo permanece V3
if (galleryFile.getVersion() == 3) {
    // Cópia mantém V3
}
```

---

## ⚙️ Configuração

### Definir V3 como Padrão

```java
// Em Settings ou Preferences
public class MySettings {
    public static final int DEFAULT_ENCRYPTION_VERSION = 3;
    
    // Usar em todo código de importação
}
```

### Versão por Objeto

```java
public class GalleryFile {
    private int version;  // 1, 2 ou 3
    
    public int getVersion() {
        return version;
    }
}

// Ao copiar, respeita versão original
FileStuff.copyTo(context, galleryFile, dir);  // Mantém versão
```

---

## 🔐 Segurança - Detalhes Técnicos

### Estrutura V3 no Disco

```
Arquivo externo: 123abc456def789ghi.valv
                 └─────────────────────┘
                 100% opaco, sem informação de tipo

Dentro (criptografado):
VERSION (4 bytes)
SALT (16 bytes)
IV (12 bytes)
ITERATION_COUNT (4 bytes)
CHECK_BYTES (12 bytes)
CHECK_BYTES_ENC (12 bytes)
\n
{"originalName": "photo.jpg", "fileType": 1}
\n
[FILE DATA]
```

### O que Está Criptografado?
- ✅ Nome original do arquivo
- ✅ Tipo do arquivo (inteiro)
- ✅ Conteúdo do arquivo

### O que é Visível?
- ⚠️ Apenas o sufixo genérico `.valv`
- ⚠️ Tamanho do arquivo (metadata do SO)

---

## 🧪 Testando V3

### Teste Básico

```java
@Test
public void testEncryptionV3() throws Exception {
    // 1. Criar arquivo teste
    DocumentFile testFile = createTestFile("photo.jpg");
    
    // 2. Importar com V3
    Pair<Boolean, Boolean> result = Encryption.importFileToDirectory(
        activity, testFile, directory, password.toCharArray(), 3, onProgress, interrupted
    );
    
    // 3. Verificar
    assertTrue(result.first);  // Importado com sucesso
    assertTrue(result.second);  // Thumbnail criado
    
    // 4. Verificar nome
    List<GalleryFile> files = FileStuff.getFilesInFolder(context, directory, false);
    assertTrue(files.get(0).getName().endsWith(".valv"));  // Sufixo genérico
}
```

### Teste de Backward Compatibility

```java
@Test
public void testV1V2V3Compatibility() throws Exception {
    // Importar V1, V2, V3
    importV1(directory);
    importV2(directory);
    importV3(directory);
    
    // Verificar leitura de todos
    List<GalleryFile> files = FileStuff.getFilesInFolder(context, directory, false);
    assertEquals(3, files.size());
    
    // Verificar tipo detectado em cada
    for (GalleryFile file : files) {
        FileType type = file.getFileType();
        assertTrue(type.isImage() || type.isVideo() || ...);
    }
}
```

---

## 📋 Checklist de Implantação

- [ ] Código revisado e testado
- [ ] Testes unitários passam
- [ ] Testes de integração passam
- [ ] V1/V2/V3 funcionam corretamente
- [ ] Performance é aceitável
- [ ] Documentação está completa
- [ ] Equipe entende a mudança
- [ ] Plano de rollout definido

---

## ⚠️ Problemas Conhecidos

Nenhum no momento. Se encontrar problemas:

1. Verificar se versão está correta: `galleryFile.getVersion()`
2. Verificar se JSON está bem-formado
3. Verificar se fileType é inteiro válido (0-4)
4. Consultar logs de erro

---

## 📞 FAQ

**P: Meus arquivos V2 continuam funcionando?**
R: Sim! Sistema detecta automaticamente a versão.

**P: Como convertir V1/V2 → V3?**
R: Copiar arquivo para nova pasta com versão 3 (futura: lazy migration).

**P: O tipo é realmente seguro?**
R: Sim. Apenas visível após descriptografia bem-sucedida com senha correta.

**P: Qual versão usar?**
R: Use 3 para novos arquivos. Mantém 1/2 para compatibilidade.

**P: Há impacto de performance?**
R: Não. V3 é igual a V2 em termos de criptografia.

---

## 🚀 Próximas Versões

### V3.1 (Planejado)
- [ ] Adicionar MIME type ao JSON
- [ ] Adicionar data de criação original
- [ ] Adicionar custom metadata

### V4.0 (Futuro)
- [ ] Novo algoritmo de criptografia
- [ ] Suporte a arquivo em múltiplos formatos

