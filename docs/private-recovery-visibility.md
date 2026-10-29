# Recuperação externa e visibilidade — 29/10/2026

A recuperação automática de um vídeo protegido que foi movido para uma pasta comum exige
uma senha válida e o toggle de pastas privadas ativado. A senha pode continuar na sessão
para reprodução/operações autorizadas; sua presença sozinha não autoriza exportar arquivos
automaticamente. A guarda fica em `restoreExternalArrivals`, compartilhada pela navegação,
scanner e montagem recursiva de listas. A fila e a preparação após autenticação também
respeitam a visibilidade atual.

Cada alteração de visibilidade muda uma versão em memória. A recuperação captura essa
versão e a versão da credencial, verificando-as durante a cópia e na publicação final.
Ocultar e mostrar novamente revoga a tentativa anterior; uma tentativa nova pode prosseguir.

O vídeo é copiado para um arquivo temporário e validado pelos hashes existentes. A publicação
final é sincronizada com a mudança de visibilidade. Cópia, hashes e sincronização do conteúdo
ocorrem fora desse bloqueio, para não prender a interface ao ocultar. Se a autorização for
revogada antes da publicação, o temporário é removido e o arquivo protegido permanece intacto.
Isso também vale quando o nome externo coincide com o nome original e exige substituição
atômica no mesmo caminho. A remoção da cópia protegida também verifica a autorização.

A limpeza de um índice antigo marcado como não privado fica adiada enquanto as pastas estão
ocultas. Uma nova ativação conclui essa limpeza mesmo se os vídeos já estavam restaurados.
Quando a fila recupera vídeos após a ativação, as listas das pastas afetadas e de seus
ancestrais recebem uma atualização local, sem exigir outro scan global.
O formato dos vídeos/índices não mudou, e não é necessária outra conversão do acervo.
As operações explícitas de restaurar/desprivar continuam usando seus fluxos próprios.

## Testes

Os testes novos usam cópias em raízes `Memes/NekoRecoveryDebugTest-UUID`, removidas ao final,
e uma senha gerada exclusivamente para essas cópias. Assim, uma reconciliação de ancestrais
não consegue descriptografar vídeos reais. As preferências de visibilidade do Debug são
restauradas e as TAGs canônicas não são alteradas.

Foram adicionados cenários para senha retida com o toggle desligado em navegação, scanner
e lista recursiva; retomada ao ativar; revogação no ponto imediatamente anterior à publicação;
nomes externos iguais ao original; ciclo ocultar/mostrar durante a mesma tentativa; e limpeza
adiada de índice antigo. Dois testes locais verificam a remoção do temporário e preservação
byte a byte do arquivo protegido ao revogar durante a cópia e antes da publicação.

Validação concluída no POCO X6 Pro com o Debug atualizado: os quatro cenários de
visibilidade/limpeza passaram, assim como os seis testes de atualização externa e abertura
sem bloqueio. A bateria anterior também passou em recuperação de colisões, arquivos
danificados, limites privados, operações nativas, reprodução e migração legada. Os 36 testes
locais passaram; APKs Debug e de teste gerados e compilação Kotlin de release concluída.
O aplicativo normal instalado não foi atualizado durante esta validação.
