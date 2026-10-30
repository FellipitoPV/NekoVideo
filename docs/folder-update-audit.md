# Atualizações após operações — 30/10/2026

## Privar e desprivar

O FAB chamava `refreshLocalPaths` depois de encerrar a operação. Essa fila aguarda scans
em andamento; para privar na tela principal, também recebia a raiz do armazenamento em
vez dos caminhos selecionados. O evento local só era emitido depois da reconciliação
e da serialização de todo o cache em disco.

Agora `LockedFolderOperations` publica cada sucesso usando os metadados da operação.
O scanner atualiza o estado da pasta, os nomes/caminhos dos descendentes já conhecidos
e a presença de vídeos nos ancestrais, e emite a revisão local antes de fechar o progresso.
O nome final é obtido do resultado da operação, incluindo a troca por UUID ao privar.
Uma reconciliação posterior verifica somente os caminhos anterior e final. A tela não
precisa esperar por ela. Erros usam a leitura local verificada, sem presumir sucesso.

Isso não reduz o trabalho necessário para criptografar/descriptografar os vídeos:
remove a espera adicional da interface depois que a operação foi concluída.

## Revisão dos demais pontos

| Fluxo | Comportamento atual | Avaliação |
| --- | --- | --- |
| Excluir, renomear, criar pasta | `MainScreen.refreshAffectedPaths` envia a pasta atual para `refreshLocalPaths`. Reutiliza vídeos conhecidos, mas lista os filhos e espera a fila antes de notificar. | Vale aplicar publicação imediata de resultados confirmados, especialmente em pastas grandes. Manter confirmação em caso de erro parcial. |
| Mover/colar e enviar para a pasta privada principal | Atualiza pais de origem e destino. Descobre subárvores novas; não faz scan global, mas também aguarda a fila. | Mesma oportunidade de publicar primeiro os caminhos confirmados. Deve tratar conflitos, renomeação e conversão entre estados privados. |
| Atualização após conversão legada | Atualiza os caminhos retornados pela conversão. | Escopo já é local; possível melhoria da notificação, com prioridade menor por ser migração excepcional. |
| TAGs | O evento específico atualiza contagens dos vídeos afetados. | Preservar; não exige reler a pasta. |
| Entrada na pasta e retorno ao app | Reconciliação silenciosa para descobrir alterações externas. | Preservar leitura verificada: não há um resultado de operação do app que permita deduzir os arquivos novos. |
| Vídeo ausente no player | `refreshLibraryAfterMissingVideo` chama scan global ao usar a ação de atualizar a biblioteca. | Candidato a uma atualização da pasta de origem, se o objetivo dessa ação for somente remover o vídeo ausente. O botão atualmente tem alcance de biblioteca. |
| Abertura do app e refresh manual | Scan global, com lista disponível visível durante o trabalho. | Não trocar indiscriminadamente: servem para descobrir alterações externas no aparelho. |

Esta revisão não alterou os outros fluxos. O ajuste implementado nesta etapa está
limitado a privar/desprivar.

## Validação

- 36 testes locais sem falhas; APKs Debug/teste gerados e Kotlin de release compilado.
- Nove cenários no POCO X6 Pro: oito de operações incrementais/interface e um de
  recuperação externa com visibilidade desligada. A execução final passou integralmente.
- O cenário novo mantém um scan global em espera e verifica a publicação antes de seu
  término, ida/volta dos caminhos UUID, cache dos vídeos em subpastas e ausência de
  abertura dos vídeos da pasta vizinha. Usa somente cópias em `NekoIncrementalTest-UUID`.
- O teste de busca/ordenação passou a concluir a descoberta inicial antes de monitorar
  as interações visuais; anteriormente podia contar essa descoberta silenciosa como
  leitura causada pela busca. Uma execução isolada confirmou a diferença de temporização.
- Debug atualizado no aparelho. O normal instalado e o acervo real não foram migrados
  nem modificados para estes testes. As TAGs canônicas foram isoladas pelo modo local de teste.
