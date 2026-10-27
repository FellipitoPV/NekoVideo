# Recuperação por arquivo integrada ao NekoVideo Debug

O formato novo está no aplicativo de interface normal, com criação habilitada em **Debug e release** por
`BuildConfig.PORTABLE_PRIVATE_WRITES`. O aplicativo release instalado no telefone não é substituído.
O laboratório separado continua disponível para regressões; seu núcleo foi extraído para
`PortableVideoStore`, compartilhado com o app.

## Teste manual

1. Atualize o NekoVideo Debug por cima da instalação existente, sem desinstalar ou limpar dados.
2. Crie uma **pasta nova** dentro de Memes e coloque algumas cópias de vídeos comuns nela.
3. No NekoVideo Debug, selecione essa pasta e escolha **Privar**.
4. Acesse as pastas privadas com três toques rápidos no ícone superior e sua senha habitual.
5. Confirme a reprodução do vídeo.
6. Usando Files ou outro administrador externo, renomeie um dos arquivos protegidos ou mova-o
   para outra pasta privada de teste. É permitido inclusive renomeá-lo com seu nome original e extensão.
7. Volte ao Debug, atualize a listagem e confirme a reprodução na pasta privada de destino.
8. Mova externamente outro vídeo protegido para uma **pasta comum** de teste. Com a senha já
   validada nesta sessão, atualize a listagem ou abra o destino: o app deve restaurar o vídeo
   automaticamente, com seu nome e bytes originais. A pasta continua comum.
9. Se não houver senha validada nesta execução, libere o acesso privado com sua senha e atualize.
   Enquanto isso, os arquivos criptografados são preservados e os vídeos comuns continuam visíveis.

Use **Privar em uma pasta nova** para exercitar o formato novo. Mandar um vídeo para um cofre
que já estava no formato antigo não converte esse cofre nesta etapa.

## Compatibilidade

- Pastas existentes com `.neko_locked` e `.neko_manifest.enc` continuam no mecanismo legado.
- O scan não converte arquivos antigos silenciosamente. A migração é oferecida após a senha,
  com confirmação explícita, conforme [migração dos vídeos existentes](private-video-migration.md).
- Novas pastas privadas do Debug usam `.neko_recovery` e `.neko_recovery_index.enc`, além de
  um registro de recuperação autenticado dentro de cada arquivo.
- Um arquivo novo movido para uma pasta legada usa sua própria chave e pode ser recuperado sem
  alterar o conteúdo dos vídeos antigos. A entrada só é persistida na pasta legada em uma ação
  explícita, como renomear/extrair aquele arquivo.
- A leitura suporta também os registros produzidos pelas primeiras versões do laboratório.
- A criação do formato novo está habilitada no release; o laboratório legado continua com a criação antiga.

## Integração

- Índice autenticado acelera a listagem; arquivos renomeados, recém-chegados e índices ausentes
  usam os registros individuais. Ler um índice ausente não o recria silenciosamente.
- O índice autenticado também pode salvar um arquivo cujo próprio trailer tenha sido danificado,
  desde que o hash do payload ainda corresponda aos metadados autenticados.
- Sessões de reprodução guardam chaves por arquivo e o tamanho original do payload. O player,
  os leitores de duração/miniaturas e o servidor HTTP excluem o trailer.
- Vídeos comuns colocados ao lado dos protegidos continuam sem chave de reprodução privada.
- Exportações são verificadas por hash; renomear um arquivo externamente para seu próprio nome
  original é tratado com uma cópia temporária verificada, sem apagar a saída recém-restaurada.
- Extrair uma subpasta pelo comando Mover também restaura o conteúdo privado recursivamente.
- A troca de senha das pastas novas preserva um índice de recuperação com a senha anterior até
  salvar a preferência de senha de forma durável. O backup é removido após validar a nova senha.
- Um registro individual não torna a pasta de destino privada. Ao detectar o arquivo em pasta
  comum, o scanner/leitor restaura automaticamente o vídeo usando a senha já validada em memória.
  Não existe serviço de monitoramento quando o app está fechado.
- A recuperação publica uma cópia verificada antes de remover a entrada criptografada. Um nome
  ocupado por outro conteúdo recebe um sufixo, como `vídeo (1).mp4`; conteúdo idêntico pode ser
  reutilizado. Senha incorreta, corrupção ou falta de espaço preservam a entrada criptografada.
- Marcadores privados, pastas ocultas, proteção em andamento e ancestrais privados impedem essa
  restauração automática. Mover entre pastas privadas mantém a proteção.
- Índices explicitamente não privados gerados por versões anteriores do Debug são retirados
  depois de restaurar suas entradas. Marcadores e índices de cofres existentes são preservados.

## Compilação

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest
```

APK: `app/build/outputs/apk/debug/app-debug.apk`.

## Validação da integração no telefone

- 34 testes locais passaram no Debug: núcleo portátil, sessões, limites e regressões existentes.
- Na primeira etapa, seis verificações de integração passaram no POCO conectado: preparação pela API normal,
  recuperação após ADB e exportação pelo Mover, adicionar/renomear/excluir/trocar senha em cópias,
  subpastas, player/HTTP de produção, mistura com uma cópia legada e leitura da pasta legada real.
- Na movimentação externa, um arquivo foi renomeado para seu próprio nome original com extensão;
  o outro recebeu um nome sem extensão. Ambos foram recuperados com SHA-256 idêntico.
- O teste com vídeo comum ao lado dos protegidos confirmou que ele não recebe uma chave XOR e
  permanece intacto. Depois de exportar o último privado, a pasta comum continuou normal.
- A senha configurada no Debug foi somente verificada, sem alteração. Trocas de senha ocorreram
  apenas nas pastas descartáveis dos testes, que voltaram à credencial original em seguida.
- A atualização foi instalada por cima de `com.nkls.nekovideo.debug`, sem limpar dados.

### Restauração automática em pastas comuns — 27/10/2026

- 12 testes no telefone passaram: seis cenários de restauração automática, regressões de
  operações privadas e player/HTTP, dois testes do FAB, preparação e recuperação após ADB.
- Dois arquivos foram movidos pelo ADB entre processos, um com o nome original e extensão e
  outro sem extensão. O app restaurou ambos na própria pasta comum com SHA-256 idêntico.
- Foram verificados: sessão sem senha, senha incorreta, payload danificado, conflito de nomes,
  publicação no mesmo caminho, marcadores/ancestrais privados, proteção em andamento,
  movimentação entre cofres novos e retirada de índice explicitamente não privado.
- O scanner detectou e restaurou uma chegada sem abrir o destino. O marcador `.nekovideo`
  de uma pasta comum criada pelo app não impediu a restauração.
- A busca recursiva também restaurou uma chegada em pasta comum e retornou a URI do vídeo
  descriptografado, sem encaminhar o arquivo criptografado ao player.
- A pasta legada real usada na etapa anterior não estava mais no caminho indicado nesta rodada;
  ela não foi usada nem alterada pelos novos testes. Não houve migração de arquivos legados.

Relatório bruto: `Android/data/com.nkls.nekovideo.debug/files/portable-integration/report.txt`.

Os testes Android `PortableIntegrationDeviceTest` só são executados no Debug com o argumento
`privatePassword` explícito, correspondente à credencial já configurada. Eles usam cópias abaixo
de diretórios `Memes/NekoRecoveryDebugTest-<UUID>` e entradas próprias do app, sem trocar a senha
real nem migrar a pasta privada do usuário. A preparação e a recuperação após ADB são métodos
separados para que a movimentação ocorra fora do processo do aplicativo.

## Escopo desta etapa

A proteção do conteúdo continua limitada aos primeiros 8 KB, como no mecanismo anterior;
o registro e o índice usam AES-GCM. Isto não é criptografia integral autenticada do vídeo.
Há migração confirmada para arquivos legados que ainda tenham seus metadados originais.
Arquivos legados sem esses metadados não passam a ter recuperação individual retroativamente.
