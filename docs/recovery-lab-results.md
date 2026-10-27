# Resultado do primeiro experimento — 26/10/2026

Dispositivo: POCO X6 Pro 5G (`2311DRK48I`), conectado por ADB.
Aplicativo separado: `com.nkls.nekovideo.recoverylab`, versão `1.9.1-recoverylab`.

## Preservação e isolamento

- A pasta privada real não foi aberta, migrada ou alterada pelos testes.
- As instalações release/debug existentes não foram substituídas.
- A variante experimental remove o launcher normal e as permissões de acesso à biblioteca de vídeos
  e de acesso a todos os arquivos. Ela usa apenas seu próprio armazenamento externo específico.
- Três arquivos de `/storage/emulated/0/Memes` foram **copiados**, mantendo os originais.
- Todos os arquivos protegidos, exportados, manifestos e testes legados ficaram em
  `/storage/emulated/0/Android/data/com.nkls.nekovideo.recoverylab/files/recovery-lab`.

## Validação executada

- 11 testes locais novos do formato experimental: passaram.
- 13 testes locais existentes na variante debug normal: passaram.
- A variante Recovery Lab também executou os 13 testes locais existentes: passaram.
- Compilação do APK experimental e do APK de instrumentação: passou.
- Preparação dos três vídeos no aparelho e consulta pelo manifesto: passou.
- Movimentação e renomeação reais por ADB, fora do processo do aplicativo: executadas.
- Manifesto original do teste separado em `withheld-metadata`, sem ser apagado.
- Recuperação pelo arquivo em um **novo processo de instrumentação**: passou para os três vídeos.
- Exportações com SHA-256 idêntico às cópias de entrada: passaram.
- Leitura aleatória, incluindo a fronteira de 8 KB e final do vídeo: passou.
- Comparação de duração e de frames decodificados no início/meio: passou.
- Manifesto corrompido e reconstrução do índice: passaram.
- Senha incorreta recusada sem publicar exportação ou modificar o arquivo protegido: passou.
- Player Media3 com `PlayerView`: primeiro frame e novo frame após busca até o meio: passaram.
- Proteção/desproteção com o `FolderLockManager` antigo, em outra cópia descartável: passou.

## Observações de desempenho

Resultados de uma execução pequena, sem controle térmico ou benchmark de bateria. Os tempos
incluem custos de inicialização/armazenamento; não permitem extrapolar para bibliotecas grandes.

| Cópia de entrada | Tamanho original | Dados adicionais | Proteger cópia | Exportar e verificar |
|---|---:|---:|---:|---:|
| `abc 6.mp4` | 2.478.571 bytes | 408 bytes | 868,30 ms | 24,58 ms |
| `abc 7.mp4` | 508.680 bytes | 407 bytes | 6,18 ms | 9,86 ms |
| `kkkkkb.mp4` | 311.931 bytes | 408 bytes | 5,64 ms | 6,63 ms |

- Consulta aquecida pelo manifesto: média de **0,60 ms** por arquivo.
- Consulta aquecida pelo fallback: média de **0,74 ms** por arquivo.
- Primeiro desbloqueio frio na nova sessão: **913,50 ms**.
- A derivação da senha ocorreu uma vez para o grupo em cada sessão; consultas seguintes reutilizaram
  a chave da sessão. Não há derivação de senha durante cada leitura do player.
- Estes números usam a proteção atual dos primeiros 8 KB. **Não medem criptografia integral.**

## Verificação independente dos memes originais

Os SHA-256 abaixo foram calculados pelo ADB nos arquivos originais antes e depois dos testes;
os três permaneceram iguais.

```text
abc 6.mp4
c6e2be0c85545c5add644af27ca38f907cf51d077cdb04214eff72c5441b1330

abc 7.mp4
dbaa8ef74374fc5a5b8638545f7afa57798a432756cb1f6f6aca854442dff894

kkkkkb.mp4
e4c561567c2bb7518a337c78930839630454f4cb7d3848e323eced05e48b1354
```

Run no aparelho: `runs/6eb673d9-cc84-4d57-b6ef-85111bd00f1f`.
Relatórios brutos: `prepare-report.txt` e `verification-report.txt` dentro desse run.

## Limite desta entrega

O formato e a tela de teste existem somente em `src/recoveryLab`; não há integração com o fluxo
normal de tornar pastas privadas, migração automática, troca de senha do formato experimental
ou criptografia integral dos vídeos. O próximo estágio de integração precisa tratar o ciclo de vida
das chaves, atualização consistente dos metadados, lotes grandes e compatibilidade dos recursos.
