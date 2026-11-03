# Shuffle privado — 03/11/2026

## Causa

O percurso recursivo só reaproveitava as últimas 12 listas de pastas da interface.
Nas demais, relia os registros de todos os vídeos e preparava sessões antes do filtro
de TAGs e do limite de 1.000 itens. Também recalculava a chave legada por pasta,
inclusive para vídeos portáteis que usam chaves individuais.

## Alteração

`ShufflePlaylistBuilder` executa a montagem em IO, consulta os filtros primeiro e
limita a descoberta às pastas dos vínculos positivos. Mantém a semântica de todas as
TAGs obrigatórias, qualquer TAG de inclusão e nenhuma TAG de exclusão, respeitando
o escopo normal/privado. A lista final não tem duplicados e mantém o limite existente.

A enumeração privada usa o índice autenticado e os registros individuais para
entradas desconhecidas. A escolha aleatória ocorre antes de preparar a reprodução.
Só os registros escolhidos são conferidos para obter as chaves atuais; os demais
vídeos conhecidos não são abertos.

O cache de shuffle é separado do cache de 12 listas da tela. Guarda no máximo 128
pastas e 8.192 assinaturas de registros em memória. Datas/tamanhos do diretório e
dos índices invalidam a pasta; tamanho, data de modificação/criação, identidade do
arquivo e fingerprint do registro autenticado controlam o reaproveitamento de um
vídeo. Navegação e refresh manual continuam lendo os registros, invalidando a
fotografia de shuffle. Alterações locais invalidam os snapshots correspondentes.
Encerrar a sessão ou trocar a credencial limpa os caches e as chaves.

Uma descoberta limitada à seleção/pasta atual reúne os diretórios e reaproveita suas
listagens na coleta. As chaves de metadados das pastas encontradas são preparadas em
uma fila contínua com até seis cálculos independentes, respeitando o número de CPUs.
O dono do keyring continua serializando uso/fechamento; somente os cálculos de PBKDF2
são paralelos. Não há mais espera por terminar um lote inteiro para iniciar o seguinte.

`PortableKeyDerivation` executa o bloco de 256 bits do PBKDF2 com o HMAC-SHA256 da
plataforma. No aparelho, esse HMAC usa AndroidOpenSSL e foi mais rápido que a fábrica
PBKDF2 do provedor BC. Permanecem as mesmas 210.000 iterações, a codificação UTF-8,
as mesmas chaves e o mesmo formato. Entradas especiais preservam o comportamento
do provedor anterior. A equivalência foi conferida com os vetores da
[RFC 7914, seção 11](https://www.rfc-editor.org/rfc/rfc7914.html#section-11), com a
construção da [RFC 8018, seção 5.2](https://www.rfc-editor.org/rfc/rfc8018.html#section-5.2)
e comparações diretas com os provedores do JVM e do Android.

Vídeos portáteis usam suas chaves individuais autenticadas, sem calcular uma chave
XOR legada que não seria usada. Pastas legadas continuam derivando a chave original.
Mudanças de senha, visibilidade ou cancelamento durante a montagem impedem publicar
a playlist daquela sessão. Não há novo formato, conversão nem alteração dos backups.

Como nos demais caches de navegação, modificações externas que preservem todos os
atributos de um arquivo exigem leitura completa/refresh manual. A restauração e a
exportação continuam fazendo suas verificações de conteúdo.

## Progresso visual

O bottom sheet existente recebe indicador determinado, porcentagem e etapa atual:
preparação dos filtros, localização de vídeos, acesso privado, coleta e montagem.
Os pesos por etapa são uma estimativa do trabalho, não do tempo restante: os contadores
avançam com as chaves/pastas/registros concluídos, sem temporizador. O progresso é
monotônico e só chega a 100% após a validação final da sessão. A preparação interrompida
não anuncia conclusão e pode ser tentada novamente. As mensagens foram traduzidas nos
nove idiomas. Só o diálogo observa o fluxo de progresso, sem recompor a biblioteca a
cada atualização. O estilo e o bloqueio de fechamento do bottom sheet são preservados.

## Medição no POCO X6 Pro

Debug, 3.888 vídeos privados, limite de 1.000 itens e filtro com 75 resultados.
São tempos de montagem da lista e preparação das chaves, sem iniciar o player.
O teste carrega o cache de descoberta em disco, como faz MainActivity, mas a descoberta
da montagem atual não depende de esse cache estar completo.

| Cenário | Antes dos ajustes | Primeiro ajuste | Atual, com progresso |
| --- | ---: | ---: | ---: |
| Primeiro shuffle sem TAGs | 36.078 ms | 10.895 ms | 6.907 ms |
| Repetição sem TAGs | 6.577 ms | 1.523 ms | 1.439 ms |
| Com TAGs, sessão já preparada | 6.642 ms | 367 ms | 394 ms |
| Primeiro shuffle com TAGs | Não medido | Não medido | 3.744 ms |

No primeiro shuffle sem filtro, 4.420 ms foram gastos na preparação de 17 chaves,
844 ms na coleta e 1.369 ms na conferência/montagem final. A meta de 3–5 segundos
sem TAGs não foi atingida nessa medição; o usuário optou por manter essa versão com
o feedback de progresso. Não houve redução das iterações nem alteração dos arquivos.

Cada linha representa uma execução. O primeiro uso ainda inclui autenticação dos
índices e derivação das chaves das pastas. As medições reais são somente leitura:
não alteram TAGs, não reproduzem vídeos nem iniciam conversão/exportação. Os hashes
dos índices e marcadores são comparados antes/depois.

Os testes funcionais usam cópias em `Memes/NekoShuffleTest-UUID`, senha exclusiva e
TAGs locais do Debug, com restauração das preferências e limpeza das cópias.

## Validação concluída

- 39 testes locais, incluindo vetores publicados, compatibilidade com o provedor
  anterior, comparação entre derivações paralelas/sequenciais e deduplicação dos
  salts, sem falhas.
- Seis cenários específicos no aparelho: limite antes da preparação e repetição
  em 16 pastas; TAGs obrigatórias/inclusão/exclusão e separação de escopos;
  renomeação/movimento e perda do índice; substituição in-place com índice intacto;
  leitura dos formatos legado e portátil pelo `HybridDataSource` do player; e
  interrupção ao ocultar, sem falso 100%, seguida de retomada.
- O observador de arquivos confirma que só os registros dos vídeos escolhidos
  são abertos na playlist limitada e que uma repetição completa não reabre os
  registros inalterados. As cópias ficam sob `.nomedia` desde sua criação, evitando
  indexação de mídia concorrente durante a medição.
- Cinco regressões adicionais passaram: visibilidade desligada com senha retida;
  shuffle de subpastas sem ler irmãos inalterados; adicionar/renomear/excluir/trocar
  senha/criar subpasta; reprodução nativa/HTTP; entradas danificadas e sem autenticação.
- Compilação Debug, APK de testes e Kotlin de release concluídos. Debug atualizado
  no aparelho; o aplicativo normal não foi reinstalado.
- Progresso visual conferido a 25%, 75% e 100%, incluindo indicador acessível e
  permanência do bottom sheet ao arrastar. A equivalência com o provedor Android
  passou para senhas simples, Unicode, NUL e comportamento de entradas inválidas.
- Após reconectar o ADB, a rodada final completa passou em 20 cenários (107,335 s):
  os seis testes específicos de shuffle, compatibilidade de chaves, progresso visual,
  quatro regressões privadas/reprodução, duas de cache/shuffle e os seis cenários de
  atualização externa/inicialização. A verificação de visibilidade anteriormente
  interrompida foi concluída com sucesso nessa rodada. Build e 39 testes locais
  também confirmados antes do commit autorizado. A tag da versão continua pendente.
