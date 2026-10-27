# Testes ampliados de recuperação — 26/10/2026

Resultado: os cenários abaixo passaram no POCO X6 Pro 5G conectado por ADB. São testes do
**Recovery Lab**, sem ativar o formato novo no aplicativo normal/debug ou migrar a biblioteca.

## Vídeo legado informado pelo usuário

A pasta `/storage/emulated/0/Memes/88310d4a69fc479c8af04c71eae1a03f` foi copiada com seus
arquivos ocultos para uma área específica do laboratório. O arquivo original e seu manifesto
não foram modificados. A credencial foi fornecida somente como argumento de instrumentação;
ela não foi incluída no código, nos scripts ou neste relatório.

Etapas verificadas:

1. Leitura do manifesto legado e derivação da chave correspondente.
2. Criação de **outra cópia** com os dados internos de recuperação, preservando os bytes do
   conteúdo já protegido. Não houve conversão de arquivos na pasta original.
3. Validação pelo manifesto experimental.
4. Movimentação/renomeação dessa cópia por ADB, fora do aplicativo, e separação do manifesto.
5. Recuperação em novo processo de instrumentação, usando os dados do próprio arquivo.
6. Comparação do SHA-256 do vídeo exportado com a leitura independente pelo leitor legado;
   o vídeo também correspondeu à cópia simples de referência existente no laboratório.
7. Comparação de duração, frames no início/meio e miniatura limitada a 120 × 120 pixels entre leitores.
8. Reprodução com frame renderizado e novo frame após busca no player Media3 real.
9. Troca de senha em **nova cópia**, rejeição da senha anterior e exportação com bytes idênticos.
10. Reconstrução do índice experimental.

Run legado: `advanced/legacy-271eb5d7-bf48-4daf-a68d-2a5211464e2e` dentro da área do laboratório.

## Escala e arquivos grandes

| Cenário no aparelho | Resultado observado |
|---|---|
| 250 arquivos físicos protegidos | Preparação em 3.001 ms |
| Consulta dos 250 pelo índice | 146 ms no total |
| Consulta dos 250 pelo fallback após movimentação/renomeação | 116 ms no total |
| Reconstrução do índice e exportações amostradas | 250 entradas reconstruídas; 10 exportações com SHA-256 correto |
| Índice sintético de 10.000 entradas | 4.520.495 bytes; gravação + leitura autenticada em 1.176 ms |
| Arquivo sintético de 268.435.579 bytes | Proteção, exportação e verificações em 3.029 ms; hashes idênticos |
| Leitura esparsa acima de 2 e 4 GiB | Offsets e limite final corretos, sem estouro de inteiro |

Os 10.000 itens são um teste de **metadados**, não 10.000 vídeos físicos. O teste completo de bytes
grandes usa 256 MiB + 123 bytes; os offsets acima de 4 GiB são um teste direcionado com arquivo
esparso, não um vídeo de 4 GiB integralmente convertido. Esses arquivos grandes descartáveis
foram removidos após a validação.

Os tempos são observações de uma execução, com inicialização/cache e condições do aparelho
sem controle de benchmark. Não demonstram autonomia de bateria, desempenho térmico sustentado
ou uma vantagem geral do fallback sobre o índice. A gravação/leitura do índice grande ocorreu
em uma sessão já desbloqueada.

## Encerramento real durante operações

O roteiro `scripts/test-recovery-lab-crashes.ps1` executou quatro encerramentos reais por ADB,
sempre **somente** em `com.nkls.nekovideo.recoverylab`, após sincronizar o temporário e antes
de publicar o arquivo de destino:

- **Proteção:** a entrada permaneceu intacta, nenhum destino incompleto foi publicado e uma nova
  tentativa criou um arquivo recuperável.
- **Exportação:** o arquivo protegido permaneceu intacto; a nova tentativa exportou bytes corretos.
- **Índice:** o manifesto anteriormente publicado continuou válido e com o mesmo hash; a nova
  tentativa publicou o índice novo e o fallback continuou disponível.
- **Troca de senha em lote:** um arquivo já estava concluído e outro estava no temporário. Um
  novo processo validou e preservou o arquivo concluído, terminou os restantes e gravou o índice.
  As exportações bateram com o SHA-256 original e a senha antiga foi recusada nas novas cópias.

As quatro verificações após reiniciar passaram. Os temporários órfãos foram removidos apenas
dentro dos runs descartáveis. Isso testa morte do processo, **não corte de energia físico**.
Falhas de escrita também foram injetadas por exceções nos testes locais; o armazenamento do
usuário não foi preenchido para provocar falta de espaço real.

## Leitura para transmissão

Um servidor experimental restrito a `127.0.0.1` foi testado com arquivo completo e HTTP Range:
início, fronteira dos 8 KB, final e sufixo. Os bytes retornados foram idênticos ao vídeo original,
o trailer de recuperação ficou fora da resposta e intervalos inválidos foram recusados.

Isso valida o adaptador local de leitura/transporte. O servidor DLNA de produção está inalterado;
uma TV ou outro receptor real não foi testado nesta rodada.

## Melhorias realizadas somente no laboratório

- Capacidade do índice ampliada: limite de 10.000 entradas e 16 MiB; registros por vídeo limitados
  a 16 KiB. A versão inicial tinha um limite de 1 MiB compartilhado que impediria o índice grande.
- Busca de entradas por nome indexada em memória.
- Índice descriptografado vinculado à sessão que o autenticou. Uma sessão diferente ou encerrada
  não consegue reutilizá-lo para contornar a verificação da senha.
- Ponte de cópia para o formato legado, troca de senha por reempacotamento de cópias e retomada
  de lotes mediante validação das saídas já concluídas.
- Pontos de interrupção explícitos e testes de publicação segura dos arquivos.

## Contagem e preservação

- **30 testes locais passaram:** 17 do protótipo e 13 existentes.
- **13 execuções de verificação Android passaram nesta rodada**, incluindo as verificações após
  os quatro encerramentos e as regressões do primeiro protótipo/leitor antigo. As instrumentações
  usadas como alvo dos quatro encerramentos foram interrompidas intencionalmente.
- As regressões cobriram recuperação de arquivos da primeira rodada, reprodução/busca e bloqueio/
  desbloqueio com o mecanismo antigo em cópias descartáveis.
- O código de produção em `app/src/main` permaneceu sem alterações.
- Os três hashes abaixo da pasta privada informada foram iguais antes e depois da bateria:

```text
.neko_locked
c4c7a87fdf591bcf64bb70c728546d406b3da1a7d8886978eb93a879ac15aebc

.neko_manifest.enc
beb641ab64f2f06c22ffa46c33874c461cdcc289ffa1ff559c70cceeb32b2597

b1204cb76b3e43808169363467a62388
6ad1352923dce6f1b95e76eeb3aef5143bbc3fb157fa9d7255f1b82354ef8c63
```

Os memes simples restantes `abc 7.mp4` e `kkkkkb.mp4` também mantiveram seus hashes da primeira
rodada. O vídeo legado correspondeu à cópia simples de `abc 6.mp4` preservada no laboratório,
que serviu como referência sem recriar o arquivo no armazenamento compartilhado.

## Limites para a próxima etapa

O método passou nos cenários executados e sustenta avançar para uma integração controlada no
Debug. Ainda não há migração em massa, alteração da senha real, recuperação automática de
temporários na interface normal, integração de todas as ações de pasta ou validação em vários
aparelhos/receptores. A proteção do conteúdo continua sendo a dos primeiros 8 KB; esses testes
não transformam o formato em criptografia integral autenticada.

Relatório bruto no aparelho: `recovery-lab/advanced-report.txt`.
Logs dos encerramentos no computador: `build/recovery-lab-crash-logs/`.
