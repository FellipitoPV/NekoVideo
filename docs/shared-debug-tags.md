# Banco único de TAGs para normal e Debug

## Fonte de verdade

O banco `video_tags.db` do app normal (`com.nkls.nekovideo`) continua no mesmo local, com
os mesmos IDs e associações. Não houve mudança de schema, importação da lista do Debug,
mesclagem de backups nem migração de banco. Após conectar, o Debug passa a usar o DAO
remoto do normal para consultas e alterações; não mantém uma segunda lista ativa.

Criar, renomear, excluir, associar TAGs, filtrar shuffle, consultar contagens e atualizar caminhos
usam o mesmo banco. As duas divisões NORMAL/PRIVATE continuam distintas. O banco antigo
do Debug permanece preservado para o modo desconectado, sem ser enviado ao normal.
Se o dono do banco ficar indisponível ou revogar a autorização, a operação é interrompida;
não há gravação silenciosa no banco local do Debug.

## Comunicação e autorização

`TagSyncProvider` possui operações explícitas, sem SQL arbitrário. Antes de qualquer consulta
ou mutação, valida o pacote que o Android informa como chamador, seu UID e o certificado
aprovado. O normal apresenta uma folha de autorização ao receber uma solicitação do Debug.
O certificado aprovado fica nas preferências do normal; outro APK com o mesmo nome e outra
assinatura não recebe esse acesso. O endpoint do Debug é desabilitado; ele é apenas cliente.

O normal notifica suas alterações por ContentResolver. O Debug consulta o último evento e
mantém o conjunto de caminhos afetados quando não houve perda de eventos. Reinício do
processo ou eventos perdidos invalidam as contagens de TAGs, sem iniciar um scan de vídeos.
Consultas grandes retornam linhas de Cursor, e listas de parâmetros são divididas em blocos.

Referências primárias: [ContentProvider](https://developer.android.com/reference/android/content/ContentProvider)
e [configuração de providers](https://developer.android.com/guide/topics/manifest/provider-element).
A autorização de `call()` é implementada explicitamente, pois essa API não infere permissões
de leitura/escrita a partir de suas operações particulares.

## Backups

Conectado, o Debug não escreve backups da lista compartilhada e não pode importar um backup
por cima dela. A restauração deve ser feita pelo app normal. Ao terminar alterações remotas,
o normal conclui os registros dos caminhos afetados. Um Mutex serializa os escritores; a
base e os registros parciais são reconstruídos durante a importação. Detalhes e verificação:
[backup incremental](incremental-tag-backups.md).

Normal: `Documents/NekoVideo/Backup/Tags/` (caminho existente, preservado).
Debug desconectado: `Documents/NekoVideo/Backup/Debug/Tags/`.
Simulador: `Documents/NekoVideo/Backup/Testing/TagSyncHost/`.
As cópias antigas de backup não são apagadas automaticamente.

Apagar dados/desinstalar o normal remove o banco ativo e a autorização. O usuário escolheu
manter esse modelo com backup: restaurar as TAGs no normal e autorizar novamente o Debug.
Não foi implementada uma fonte externa que continue ativa sem o app normal instalado.

## Ativação no aparelho

1. Gerar e instalar **por atualização** o normal assinado com a chave habitual, preservando dados.
2. Instalar o Debug atualizado.
3. No Debug: Configurações → TAGs → TAGs compartilhadas → Conectar ao NekoVideo.
4. Confirmar **Permitir conexão** no app normal.

O normal autorizado mostra uma opção de desconectar o Debug. Após apagar os dados do normal
ou trocar a assinatura do Debug, desconectar/reconectar pelo Debug permite refazer a autorização.
O APK assinado antigo em `app/release` não é substituído pelo Gradle: a saída automatizada de
release é `app/build/outputs/apk/release/app-release-unsigned.apk`. O keystore está configurado
no assistente do Android Studio, mas não há credenciais de assinatura configuradas no Gradle.

## Testes isolados no POCO

A variante `tagSyncHost` instala outro pacote, `.tagsynchost`, com banco e pasta de backup próprios.
As instrumentações exigem esse pacote e um prefixo de TAGs de teste. Nenhum teste escreve no
normal instalado. O token é fornecido como argumento, sem depender de TAGs ou vídeos do acervo.

Foram validados: rejeição sem autorização, certificado aprovado, ausência de importação da lista
local do Debug, preservação de IDs, duas divisões de TAGs, 1.200 vínculos, contagens/interseções,
renomeação, movimentação de caminhos, backup pelo dono do banco, bloqueio de restauração pelo
Debug e revogação sem fallback local. A instrumentação do Debug permaneceu ativa enquanto a
instrumentação do simulador alterou o banco: as notificações atualizaram o Debug e suas novas
alterações foram confirmadas no banco do simulador, em outro processo.

O aparelho bloqueou injeção de toques entre apps. A folha real foi exibida; confirmações também
foram feitas fisicamente pelo usuário. Para repetir os testes de dados de forma determinística,
a instrumentação do próprio simulador concede/revoga sua autorização. Isso não habilita nenhum
atalho de autorização no release. Os backups de teste usam apenas o MediaStore e os arquivos
criados pelo próprio simulador; não foi concedido acesso amplo ao armazenamento.

Também passaram os 34 testes locais, nove regressões de operações incrementais/migração/
reprodução e a compilação release com R8 e lint. Os nove arquivos preexistentes da pasta de
backup real conservaram exatamente seus hashes SHA-256 durante os testes. As instalações
isoladas e os três backups temporários do simulador foram removidos ao concluir.

## Conferência com a conexão real

Em 28/10/2026, o usuário instalou o normal assinado por atualização e autorizou a conexão.
O teste `ProductionTagConnectionDeviceTest` consultou somente o banco canônico: 0 TAGs
normais, 14 privadas e 2.681 vínculos, com IDs e contagens consistentes. Não realizou
alterações, restauração nem solicitação de backup. O backup automático do próprio normal
foi atualizado após sua instalação; as cópias antigas com sufixos permaneceram intactas.

As instrumentações de arquivos/migração usam `TagSyncBridge.localTestMode`, uma opção
somente em memória, para trabalhar com o banco local do Debug mesmo quando ele está
conectado. A preferência de conexão não é alterada. Depois de instalar o Debug com essa
proteção, o teste de exclusão/renomeação/criação e falha parcial passou com cópias isoladas;
uma nova leitura real confirmou as mesmas 14 TAGs privadas e 2.681 associações. Compilação
Debug, instrumentação, testes locais e compilação Kotlin release também passaram.
