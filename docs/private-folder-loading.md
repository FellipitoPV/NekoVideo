# Abertura de pastas privadas

Medições em 27/10/2026 no POCO X6 Pro, com Debug, usando a pasta real
`/storage/emulated/0/NekoVideo` e suas três maiores subpastas. O teste opt-in
`PrivateFolderPerformanceDeviceTest`, ativado por `measurePrivateFolder=true`, apenas lê
o acervo e compara os hashes dos índices e marcadores antes e depois. Não converte nem
exporta vídeos; a senha é recebida somente como argumento em execução.

## Causa e alteração

O clique aguardava a leitura integral do manifest e a preparação da sessão antes de navegar.
O carregamento da tela repetia ambas as operações. Na leitura de cada vídeo, a detecção do
rodapé e a inspeção do registro faziam três aberturas, incluindo duas leituras do mesmo
registro completo. A formatação dos hashes também criava um formatador para cada byte.

Agora o clique navega após a autenticação já existente e a tela prepara a sessão uma vez,
em IO, com seu loading habitual. Falhas de leitura continuam gerando a mensagem de erro.
A inspeção lê e calcula o fingerprint do registro uma única vez, reutilizando-o na entrada.
A identificação de um arquivo já restaurado usa primeiro seu tamanho; arquivos protegidos
possuem o registro adicional e não precisam da sondagem prévia. A leitura do salt do índice
lê somente o rodapé, validando o formato e os comprimentos. A conversão hexadecimal foi
substituída por uma implementação direta, mantendo exatamente os mesmos hashes.

Nenhuma alteração no formato, no custo de derivação da senha, nas TAGs ou nas verificações
de integridade para restauração/exportação. O índice continua autenticado, e cada arquivo
continua tendo o registro conferido para detectar movimentações e renomeações externas.

## Tempos medidos

| Pasta | Primeiro manifest antes/depois | Lista repetida antes/depois |
| --- | --- | --- |
| Principal, 16 subpastas | 750 / 753 ms | 86 / 83 ms |
| 1.084 vídeos | 6.751 / 2.318 ms | 6.020–6.169 / 1.654–1.815 ms |
| 564 vídeos | 4.046 / 1.794 ms | 3.005–3.133 / 993–1.204 ms |
| 392 vídeos | 3.243 / 1.355 ms | 2.146–2.296 / 571–682 ms |

São medições das funções de leitura, não do tempo total de renderização da interface.
O primeiro manifest de cada pasta inclui a derivação da senha; esse custo permanece.
A etapa prévia ao navegar foi removida e, portanto, não deve ser somada novamente à lista.

## Validação

Passaram os 34 testes locais, a medição no acervo e oito regressões no aparelho: entrada sem
sessão preparada, erro de senha/metadados sem alteração do vídeo, recuperação entre pastas
privadas, restauração com conflitos de nome, preservação de entradas danificadas/sem senha,
operações de adicionar/renomear/excluir/trocar senha/criar subpasta, reprodução nativa e HTTP,
arquivo portátil em pasta legada e migração de uma árvore com TAGs. Os cenários funcionais
usam somente cópias de teste. A compilação Kotlin de release também passou; somente o Debug
foi atualizado no telefone.
