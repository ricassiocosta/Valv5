# Teste de Implementação V3 - Checklist

## ✅ Verificação de Compilação
- [x] `Encryption.java` compila sem erros
- [x] `FileType.java` compila sem erros  
- [x] `FileStuff.java` compila sem erros
- [x] `ImportViewModel.java` compila sem erros
- [x] Sem erros de compilação em todo projeto

## 🔐 Funcionalidade de Criptografia V3

### Arquivos Novos
- [ ] Novos arquivos importados usam sufixo `.valv` genérico
- [ ] Thumbnails novos usam sufixo `.thumb.valv` genérico
- [ ] Notas novas usam sufixo `.note.valv` genérico

### Verificar Estrutura
- [ ] Arquivo V3 contém versão "3" no header
- [ ] JSON contém campo `"fileType"` com valor inteiro correto
- [ ] Tipo não é visível no nome do arquivo

## 🔄 Backward Compatibility

### Leitura de V1
- [ ] Arquivos V1 com prefixo (`.valv.i.1-`) lêem corretamente
- [ ] Tipo de arquivo é detectado a partir do prefixo
- [ ] Descriptografia funciona

### Leitura de V2  
- [ ] Arquivos V2 com sufixo (`-i.valv`) lêem corretamente
- [ ] Tipo de arquivo é detectado a partir do sufixo
- [ ] JSON com `originalName` é lido corretamente
- [ ] Descriptografia funciona

### Leitura de V3
- [ ] Arquivos V3 com sufixo genérico (`.valv`) lêem corretamente
- [ ] JSON com `fileType` é extraído corretamente
- [ ] Tipo é convertido via `FileType.fromTypeAndVersion()`
- [ ] Descriptografia funciona

## 📂 Operações de Arquivo

### Copiar Arquivo
- [ ] V1 arquivo copiado → permanece V1 (prefixo)
- [ ] V2 arquivo copiado → permanece V2 (sufixo com tipo)
- [ ] V3 arquivo copiado → permanece V3 (sufixo genérico)

### Mover Arquivo
- [ ] V1 arquivo movido → permanece V1
- [ ] V2 arquivo movido → permanece V2
- [ ] V3 arquivo movido → permanece V3

### Detecção de Tipo
- [ ] V1 tipo detectado via prefixo
- [ ] V2 tipo detectado via sufixo
- [ ] V3 tipo detectado via JSON criptografado

## 🎯 Casos de Uso Especiais

### Importação Mista
- [ ] Pasta V1 + V2 + V3 é lida corretamente
- [ ] Thumbnails V1 + V2 + V3 são associados corretamente
- [ ] Notas V1 + V2 + V3 são associadas corretamente

### Edição de Arquivo
- [ ] Editar nota em arquivo V1 → cria V1
- [ ] Editar nota em arquivo V2 → cria V2
- [ ] Editar nota em arquivo V3 → cria V3

### Segurança
- [ ] V3 arquivo não revela tipo no nome
- [ ] Tipo só é visível após descriptografia bem-sucedida
- [ ] Senha incorreta não revela tipo

## 🚀 Performance

- [ ] Importação V3 tem performance similar a V2
- [ ] Descriptografia V3 tem performance similar a V2
- [ ] Nenhuma degradação notável

## 📝 Casos de Erro

- [ ] JSON inválido em V2/V3 é tratado graciosamente
- [ ] Arquivo truncado é detectado
- [ ] Senha incorreta lança InvalidPasswordException
- [ ] Versão incompatível é detectada

## 🔒 Validação de Segurança

- [ ] V1 Prefixo não é mais usado em novos arquivos
- [ ] V2 Sufixo com tipo não é mais usado em novos arquivos  
- [ ] V3 Tipo é totalmente criptografado
- [ ] Nenhum metadado de tipo vaza em nome de arquivo

---

## Notas

### Próximos Passos Recomendados
1. Implementar testes unitários para `Encryption.java`
2. Implementar testes de integração para V1/V2/V3
3. Considerar lazy migration V1/V2 → V3
4. Documentar mudanças em CHANGELOG

### Questões em Aberto
- [ ] Quando migrar padrão de novos arquivos de V2 para V3?
- [ ] Implementar auto-upgrade de V1/V2 → V3?
- [ ] Adicionar métodos para enumerar versão de todos os arquivos?

