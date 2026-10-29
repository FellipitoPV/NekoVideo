# Atualizações incrementais de pastas — 28/10/2026

## Comportamento

Busca, ordenação, filtro de TAGs e indicadores de fixados são projeções da lista já carregada.
Não são mais chaves do efeito que lê arquivos. Alterações no cache do scanner de outra pasta
também não invalidam a tela atual. A primeira entrada continua validando a pasta e os registros
privados; o botão de atualizar manualmente e o scan inicial da biblioteca continuam disponíveis.

As ações do app publicam revisões somente para as pastas afetadas e seus ancestrais.
`refreshLocalPaths` reaproveita os vídeos conhecidos, remove caminhos desaparecidos e descobre
somente entradas/subárvores novas. Não solicita loading global nem percorre subárvores irmãs
inalteradas. A tela reaproveita os itens existentes e os metadados autenticados para alterações
locais; em pastas comuns, evita a sondagem de recuperação externa durante esse fluxo.

O índice privado autenticado é o caminho rápido nas edições. Arquivos desconhecidos ainda usam
o registro individual. Restaurar/exportar e substituir um registro continuam verificando o payload
envolvido; migrar, desbloquear e trocar senha mantêm as verificações necessárias. Renomear um
vídeo privado ainda copia o próprio payload para substituir seus metadados atomicamente: esta
mudança elimina leituras dos outros vídeos, não redesenha o formato físico de recuperação.

Exclusões privadas em lote validam a seleção antes de apagar, acumulam as remoções confirmadas
e gravam o índice uma única vez por pasta, inclusive em falha posterior. Diretórios também saem
do manifest. A gravação legada usa AtomicFile. TAGs de exclusões normais são removidas após a
confirmação da exclusão física. Movimentos parcialmente concluídos reconciliam origem/destino
mesmo quando o lote retorna erro, preservando conflitos sem sobrescrever o destino.

A playlist ao abrir vídeos reutiliza a lista carregada e trabalha em IO quando precisa lê-la.
O shuffle reutiliza pastas privadas já carregadas e não prepara a mesma sessão duas vezes.
O cache de conteúdo guarda no máximo 12 pastas, é vinculado à versão da credencial e é limpo
ao bloquear a sessão. Não grava nomes privados descriptografados em armazenamento adicional.

Ao entrar ou retomar a tela, incluindo a raiz, a validação silenciosa compara as datas dos
diretórios já descobertos naquele trecho da biblioteca. Diretórios vazios também são observados:
um vídeo recebido em uma subpasta existente é percebido mesmo sem mudança na data do pai.
Somente os trechos alterados são relidos, e a visibilidade é propagada aos ancestrais sem abrir
os vídeos de subpastas inalteradas. A lista atual continua visível e não há loading global.
As operações conhecidas do próprio app também atualizam essas observações, evitando outra
validação ao retornar. Alterações externas in-place que preservem os carimbos dos diretórios
continuam dependendo da nova leitura de conteúdo ou do refresh manual; não há monitoramento
contínuo de cada arquivo enquanto a tela permanece aberta.

29/10/2026: corrigida a dependência exclusiva do cache global para mostrar subpastas novas.
Antes, a primeira leitura podia salvar o carimbo atualizado antes da verificação de retomada,
e a raiz sequer participava dessa verificação. A reconciliação agora ocorre após exibir a
lista disponível ao navegar e novamente ao retomar, sem dar às alterações externas o caminho de
confiança reservado às operações do app.

Na abertura, a lista disponível é publicada antes de aguardar qualquer scan em andamento.
Assim, o cache anterior fica visível e a navegação continua funcionando durante a atualização.
Mesmo sem cache do scanner, a tela deixa o loading grande assim que a primeira listagem
local estiver disponível; não precisa esperar o scan completo para começar a navegar.

Dois testes de inicialização mantêm o scan deliberadamente pendente por um dispatcher de
teste. Com cache e sem cache do scanner, verificam a exibição da pasta, o clique para entrar
nela e a lista de vídeos enquanto `isScanning` ainda é verdadeiro. Depois liberam e concluem
o scan normal. Os seis cenários de atualização silenciosa/inicialização passaram juntos no
telefone após essa correção. Os 34 testes locais e a compilação Kotlin release também passaram.

`FolderExternalRefreshDeviceTest` passou nos quatro cenários de 29/10: subpasta antes vazia
que recebe vídeo sem alterar a data do pai; nova árvore de pastas e vídeo direto; navegação
real para Downloads mantendo o app aberto; e retomada da tela principal por eventos de
ciclo de vida. Todos usam cópias em `Memes/NekoRefreshTest-UUID`, removidas ao final. O teste
com 16 vídeos na pasta irmã registrou zero aberturas desses arquivos. Uma exclusão feita
pelo app também não causou outra validação. O contador do scan global permaneceu inalterado.

Também passaram os sete cenários incrementais anteriores, a recuperação autenticada após
descoberta inicial e os 34 testes locais. A compilação Kotlin release passou e o Debug foi
atualizado no aparelho. A medição final do scan inicial levou 3.668 ms nessa execução; as
TAGs e os arquivos do acervo real não foram editados pelos testes.

## Remoção da configuração antiga

Foi removida a ação `SET_AS_SECURE_FOLDER`, seus textos em todas as traduções e a API que
definia uma pasta segura personalizada. O mecanismo de privar/desprivar qualquer pasta continua.
A preferência histórica tem somente leitura/realocação de compatibilidade, para não esquecer
um caminho configurado em versões antigas. Não há mais ação para definir outro caminho.

## Validação no POCO X6 Pro

`IncrementalFolderDeviceTest` usa cópias do menor vídeo de memes da área de entrada do Debug,
em raízes novas `/storage/emulated/0/Memes/NekoIncrementalTest-<UUID>`, verificadas antes da limpeza.
Nenhuma operação destrutiva é executada no acervo. Preferências temporárias de exibição e
ordenação são restauradas; fixados criados pelos testes são removidos.

O teste usa FileObserver para contar OPEN em vídeos não envolvidos e MOVED_TO no índice.
O lote de oito exclusões em uma pasta de 36 vídeos publicou um índice uma vez. Excluir,
renomear, criar subpasta e mover não abriram os vídeos restantes. O cenário normal inicialmente
revelou 34 aberturas extras pela detecção de recuperação externa; a correção reduziu a contagem
a zero. O teste de UI pré-carrega miniaturas em RAM para medir a leitura da lista separadamente
da geração legítima de miniaturas, e confirma busca, ordenação e fixados sem reler registros.

Os sete cenários cobrem também falha parcial, conflito de movimento, invalidação após renomeação
externa, bloqueio do cache privado e shuffle com subpastas. As regressões incluem exclusão em lote legada, migração
com TAGs, operações nativas, reprodução nativa/HTTP e recuperação em pastas comuns/privadas.
Na rodada de 17 testes selecionados, 16 executaram sem falhas e o cenário dependente da antiga
pasta legada real foi ignorado por sua ausência; a compatibilidade legada foi validada com cópias
sintéticas independentes. Os 34 testes locais e a compilação Kotlin de release também passaram.
Uma rodada complementar de três testes confirmou a criação de subpasta privada e o shuffle
sem ler vídeos irmãos, a exclusão em lote e as operações nativas com os últimos atalhos de metadados.
Somente o Debug foi atualizado no telefone.
