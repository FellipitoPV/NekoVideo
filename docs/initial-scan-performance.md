# Desempenho do scan inicial — 28/10/2026

## Medição no POCO

O benchmark do Debug executa o scanner sem senha, com acesso amplo ao armazenamento já
concedido. Ele não converte nem restaura vídeos reais. Compara uma assinatura dos caminhos,
contagens, estados das pastas e listas de vídeos; datas de modificação não entram na assinatura.

| Etapa | Antes | Primeira otimização | Com contagem reaproveitada |
|---|---:|---:|---:|
| MediaStore | 110 ms | 113 ms | 104 ms |
| Sistema de arquivos | 12.257 ms | 6.115 ms | 3.564 ms |
| Pastas diretas | 244 ms | 216 ms | 107 ms |
| Recuperação/migração | 141 ms | 127 ms | 107 ms |
| Publicação/cache | 28 ms | 22 ms | 19 ms |
| Total | 12.780 ms | 6.593 ms | 3.901 ms |

Outra medição anterior levou 13.767 ms. A repetição otimizada variou até 5.490 ms, portanto
a comparação prática nesta amostra é aproximadamente 13–14 s contra 4–5,5 s, sem garantia
de tempo fixo em outros aparelhos. Todas preservaram os mesmos 65 diretórios de biblioteca,
4.188 vídeos e assinatura `3f1af311666afc95f166e01720895f2cc94ba78be2aaceaebda68563b13b2b0f`.

## Alterações

- Cada diretório é listado uma vez na etapa recursiva; a mesma lista alimenta classificação,
  descoberta de registros privados movidos externamente, contagem e busca de subpastas.
- A descoberta inicial sem senha herda a fronteira privada do pai e usa os marcadores da
  listagem, sem repetir canonicalização e consultas de marcadores de todos os ancestrais.
- O cadastro de pastas é carregado uma vez por scan, e caminhos já percorridos não são
  varridos novamente pelas raízes de fallback. Estas continuam disponíveis quando a raiz
  principal não conseguiu alcançá-las.
- Sem entrada no cadastro, uma contagem privada anterior pode ser usada quando a pasta
  continua privada e sua data de modificação não mudou. Adições, remoções e renomeações
  invalidam essa condição; subpastas continuam sendo percorridas.
- Ao contar registros desconhecidos, arquivos confirmados como vídeos privados dispensam
  uma segunda consulta para descobrir se são diretórios.
- A recuperação autenticada mantém a verificação completa. Caso a senha seja informada
  durante o scan, a listagem inicial não é modificada no meio da classificação; chegadas
  enfileiradas são recuperadas com metadados novos ao final da descoberta.

## Verificação

`InitialScanDeviceTest` validou a assinatura da biblioteca e um laboratório com três cópias
privadas, sem cadastro: a segunda leitura abriu zero vídeos para recontagem; uma quarta
cópia adicionada externamente foi encontrada na leitura seguinte. O laboratório é removido
ao terminar. `PortableIntegrationDeviceTest` passou a testar a descoberta pelo scan inicial
completo, seguida da recuperação autenticada somente do diretório de teste. Também passou
a regressão de exclusão/renomeação/criação e falha parcial em cópias isoladas.

Os 34 testes locais e a compilação Kotlin release passaram. O Debug otimizado foi instalado;
o normal precisa da próxima build assinada. As alterações anteriores de backup incremental
permanecem no mesmo checkout e não fizeram parte do tempo da varredura medido aqui.
