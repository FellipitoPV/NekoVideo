# Atualização dos vídeos privados existentes

O scanner global identifica pastas legadas e conversões interrompidas. Após autenticar e mostrar
as pastas privadas, o app lê seus manifests e oferece **Atualizar agora** ou **Mais tarde**, com
a quantidade confirmada de vídeos e pastas. O scanner não converte vídeos sem confirmação.
O aviso espera o scan e outras ações/diálogos terminarem. Adiar não modifica os arquivos;
o aviso pode reaparecer ao liberar o acesso privado novamente.
O aviso usa o `AppBottomSheet` padrão. A contagem autenticada termina antes de desligar o loading;
o bottom sheet consome esse resultado, sem uma segunda leitura dos manifests após o scan.
Varreduras canceladas não podem encerrar o indicador de uma varredura mais recente.

A autenticação não dispara outra varredura global. A descoberta usa o scan normal da biblioteca
e guarda, nas preferências privadas do app, as pastas pendentes e a versão da conversão concluída.
Depois de concluir, liberar o acesso privado não relê os manifests para a migração. Adiar ou falhar
mantém somente as pastas pendentes para a próxima tentativa. Se o scan normal encontrar uma pasta
legada restaurada posteriormente, ela volta à fila sem exigir outra varredura pela autenticação.
Após converter, a atualização do cache também se limita às pastas afetadas.

Diretórios comuns com arquivos protegidos movidos externamente são guardados em uma fila durante
o scan já existente. Após a senha, o app restaura apenas esses diretórios conhecidos e atualiza seus
dados no cache. Isso mantém a recuperação automática sem percorrer novamente todo o aparelho.

## Conversão

- Pasta principal, subpastas e pastas vazias são tratadas individualmente, preservando caminhos,
  nomes físicos, nomes de exibição, senha, miniaturas e associações de TAGs.
- O vídeo permanece criptografado. Uma cópia recebe o registro de recuperação AES-GCM; nenhum
  vídeo em texto claro é escrito durante a migração. O payload criptografado anterior é preservado.
- A cópia é sincronizada em disco e conferida por hash antes da substituição atômica. Uma
  alteração externa do arquivo entre a cópia e a confirmação impede sua substituição.
- O manifest antigo permanece até todos os registros individuais estarem disponíveis. O leitor
  reconhece os arquivos parcialmente migrados pelo registro anexado, inclusive após reiniciar.
- O novo índice é publicado antes do novo marcador. Só então o marcador e manifest antigos
  são retirados. O índice é relido e autenticado, conferindo entradas e nomes das subpastas,
  antes da troca de marcador. Um marcador de retomada permite terminar uma limpeza interrompida.
- O índice antigo não é regravado a cada vídeo. Há espaço temporário para uma cópia por vez,
  além do índice e metadados. Falta de espaço preserva o original e permite tentar novamente.
- TAGs continuam no banco atual. Seus caminhos não mudam durante a conversão.

Arquivos ausentes/truncados, manifest ilegível, proteção anterior incompleta e arquivos
criptografados sem identificação impedem a conversão da pasta afetada. As demais pastas podem
ser atualizadas. O resumo informa pendências; elas não são apagadas nem marcadas como concluídas.
A migração não recupera retroativamente informações já perdidas de arquivos legados.

## Verificação no aparelho

`PrivateVideoMigrationDeviceTest` usa exclusivamente cópias abaixo de
`/storage/emulated/0/Memes/NekoMigrationTest-<UUID>`. Os manifests de teste reproduzem o formato
legado publicado, com AES-CBC/PBKDF2 e cabeçalho XOR. Os vídeos de entrada são lidos da área de
testes do Debug; não há alteração de senha ou conversão do acervo privado do usuário.

Os cenários cobrem descoberta pelo scanner, árvore de pastas, pastas vazias, preservação de TAGs,
leitura integral pelo DataSource de produção, movimentação externa após migrar, confirmação e
adiamento no diálogo, senha incorreta, arquivo truncado, espaço insuficiente simulado, mudança
externa concorrente e lote de 36 vídeos. A leitura é comparada por SHA-256 com o vídeo de referência.

`scripts/test-private-migration-crashes.ps1` encerra o processo de verdade em oito pontos:
durante a cópia, após sincronizar a cópia, com a cópia pronta, com a sessão preparada, após
substituir o vídeo, após salvar o novo índice, após salvar o novo marcador e após retirar o
marcador antigo. Cada retomada ocorre em outro processo, verifica a reprodução e termina a migração.
O argumento de senha é fornecido em execução e não é salvo nos arquivos de teste.

Na validação de 27/10/2026 passaram os seis cenários Android, os oito encerramentos reais com
retomada em outro processo e os 34 testes locais compartilhados. Cada encerramento real foi
seguido de reprodução integral comparada por hash e migração concluída. O teste de 36 vídeos
também confirmou que o manifest legado permaneceu byte a byte intacto durante as substituições.
Espaço insuficiente foi simulado pela função de disponibilidade, sem encher o armazenamento.

O recurso está habilitado para Debug e release. O laboratório isolado continua criando o formato
legado para regressões. Nenhum APK de produção foi instalado ou publicado por esses testes.
O release passou por compilação, lint vital e minificação R8. Os 34 testes compartilhados também
passaram na variante Recovery Lab, além do Debug. O APK release gerado permanece sem assinatura
de distribuição; publicação e versionamento da próxima atualização continuam na etapa de lançamento.

## Teste manual do acervo

Abra o NekoVideo Debug atualizado, aguarde o scan e libere as pastas privadas com a senha.
Confira o total no diálogo e escolha **Atualizar agora** para iniciar a conversão confirmada.
Após terminar, confira a reprodução e as TAGs em algumas subpastas. Vídeos já atualizados
não voltam à lista de conversão; uma interrupção deixa somente o trabalho pendente para retomar.

A melhoria adiciona recuperação individual ao formato; ela não muda a proteção do payload
para criptografia integral do vídeo. O release instalado não é substituído pelos testes do Debug.

## Modal de progresso fixa

O progresso usa `NonDismissibleBottomSheet`, uma janela fixa alinhada à parte inferior, com o
mesmo corpo, cores, espaçamento e formato visual dos outros bottom sheets. Ela não possui
estado arrastável. Voltar e toque fora são bloqueados pela própria janela; somente o término
da operação retira o progresso da composição.

`ProcessingDialogDeviceTest` reproduziu o deslocamento da implementação anterior durante um
arraste e validou a correção no POCO: arrastes a partir do título e do conteúdo mantêm a posição
inclusive com o dedo ainda pressionado; toques externos e Voltar do sistema não fecham a janela;
o texto de progresso continua atualizando. Esses testes não iniciam scans nem conversões.
O teste de confirmação/adiamento da migração também passou usando uma cópia descartável.

## Verificação da conclusão persistida

A rodada complementar passou em nove testes no aparelho: reabertura do estado de conclusão,
ausência de nova inspeção de manifests depois de concluir, manutenção das pendências ao adiar
ou falhar, descoberta parcial sem conclusão indevida, pasta legada restaurada após finalizar,
recuperação direcionada de arquivos movidos antes da autenticação, abertura do aviso ao fim
do loading e regressões dos gestos da modal. As preferências desses testes são isoladas e removidas
ao terminar; os vídeos usados são cópias descartáveis.

## Falsas pendências de diretórios do sistema

No POCO, o histórico do Debug continha somente `/`, `/storage` e `/storage/emulated` como
pendências. Esses caminhos são ancestrais adicionados ao cache da biblioteca e não possuem
marcadores de pasta privada. A restrição de leitura do Android fazia o histórico classificá-los
indevidamente como pastas privadas indisponíveis, impedindo salvar a conclusão.

A descoberta agora exige um marcador legado/journal, ou evidência anterior de pasta privada
para conservar uma pendência sem acesso. O histórico guarda essa evidência separadamente;
restrições de acesso em diretórios comuns não criam pendências. As entradas indevidas da versão
anterior são retiradas na próxima verificação, sem alterar vídeos ou manifests. Uma pasta privada
com manifest ilegível continua pendente e não é convertida silenciosamente.

Validação: 34 testes locais e seis testes no POCO passaram. O teste opt-in
`verifyAndRepairReportedSystemAncestors`, ativado por `repairReportedAncestors=true`, confirmou
as três entradas reais do Debug, corrigiu somente seu histórico e verificou duas autenticações
sem loading ou aviso de migração. Os outros cenários usaram preferências isoladas e cópias de
vídeos, incluindo um manifest removido intencionalmente que continuou sendo sinalizado. O
histórico final do Debug ficou com `completed_version=1` e nenhuma pasta pendente; o release
instalado não foi substituído nem teve seus dados internos modificados.
