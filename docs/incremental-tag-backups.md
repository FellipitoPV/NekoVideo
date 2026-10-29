# Backup incremental das TAGs

## Comportamento

O banco continua sendo a fonte ativa. Cada edição concluída tenta salvar seu backup antes
de retornar, sem a antiga espera de cinco minutos. Inclusão/remoção de TAGs em vídeos,
limpeza de vínculos e movimentações consultam somente os caminhos afetados. Os dados das
definições das TAGs são pequenos e acompanham o registro; os demais vínculos não são lidos
novamente do banco nem regravados no armazenamento.

Na primeira gravação é criada uma base completa. Criação, exclusão ou renomeação de uma
TAG e a consolidação após 64 registros também criam uma base completa. As alterações
intermediárias ficam em arquivos pequenos e imutáveis, um por operação/lote.

O normal usa `Documents/NekoVideo/Backup/Tags/Incremental/`. O Debug conectado delega ao
normal; o Debug desconectado e o host de testes continuam com seus diretórios separados.
Os três JSONs anteriores permanecem intactos e são fontes de importação enquanto não
existir o novo formato. A importação automática dá prioridade à base incremental.
Para copiar esse backup para outro lugar, preservar a pasta `Incremental` inteira; um
arquivo `change-*` isolado não representa o banco completo. A exportação manual por API
continua gerando um único JSON completo.

## Integridade e retomada

- Dois arquivos de checkpoint alternam: a base anterior permanece disponível enquanto a
  próxima é gravada. Mantêm-se os registros necessários à base anterior durante a limpeza.
- A publicação usa `IS_PENDING`; o conteúdo só fica disponível para restauração depois
  de uma leitura de verificação. Uma gravação interrompida e ainda não publicada é ignorada.
- Cada arquivo contém revisão sequencial e SHA-256. Revisões faltantes e conteúdo inválido
  impedem a importação, em vez de restaurar silenciosamente vínculos incompletos.
- Cada registro substitui todos os vínculos dos caminhos envolvidos, incluindo listas
  vazias. Assim, remoções de TAGs e mudanças de caminho também são restauradas corretamente.
- O escritor é serializado. Falhas deixam uma pendência persistente, avisam uma vez e são
  tentadas novamente em 30 segundos ou na próxima abertura. A recuperação faz um checkpoint
  completo para incluir também os caminhos de tentativas anteriores que falharam.
- A data apresentada vem do conteúdo validado, não da data de modificação dos arquivos.
- Antes de uma importação substituir o banco, uma cópia atômica do estado anterior fica
  em `files/tag-restore-recovery/before-import-UUID.json`, dentro dos dados do app. Ela não
  tem interface de restauração própria e é removida se os dados do app forem apagados.

Isso não recupera retroativamente vínculos ausentes em todos os bancos e backups existentes.

## Verificação em 28/10/2026

Foram preservadas cópias locais dos backups e do banco antigo do Debug, apenas para análise,
em `app/build/tag-backup-audit-20261028` (ignorado pelo Git). A conferência somente de leitura
encontrou no normal 14 TAGs privadas e 2.681 vínculos, mesma contagem anterior à revisão.
Marvel tinha 239 vídeos e 7 caminhos com TAGs; Overwatch, 1.081 vídeos e 18 caminhos com TAGs.
Não foram encontrados vínculos com o mesmo nome de arquivo apontando para outros pais.
A causa histórica exata das associações ausentes não foi demonstrada; não foi realizada
restauração nem alteração das TAGs reais durante a investigação.

Os testes usam exclusivamente o pacote descartável `.tagsynchost` e verificam backup antes
do retorno, edições simultâneas, importação de remoções/movimentações/renomeação e dos dois
escopos, integridade, consolidação, publicação interrompida, retomada persistente e cópia
anterior à restauração. Num teste com 2.000 vídeos, uma edição gravou 850 bytes mantendo
uma base de aproximadamente 375 KB intacta; a operação levou 102 ms nessa execução.

A rodada final passou nos oito cenários do dispositivo (incluindo falha seguida de uma nova
edição), nos 34 testes locais e na integração compartilhada com 1.200 vínculos. A compilação
Debug e a release com R8 e lint vital passaram. O `lintDebug` completo continua bloqueado por
22 erros anteriores de traduções ausentes; a mensagem nova de falha de backup foi traduzida
em todos os idiomas existentes. A última medição parcial ficou em 850 bytes e 113 ms.

O Debug atualizado foi instalado. O normal instalado foi apenas consultado; para usar o
novo escritor como dono do banco, é necessário instalar a próxima build normal assinada.
O release sem assinatura foi gerado. Pacotes e backups temporários do laboratório foram
removidos; os nove JSONs do backup normal conservaram seus hashes durante esta revisão.
