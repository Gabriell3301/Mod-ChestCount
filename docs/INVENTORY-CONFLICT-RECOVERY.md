# Recuperação simples de conflitos de inventário

Quando `/cc sync status` indicar um conflito de inventário, abre novamente o baú afetado e fecha-o depois de receber o inventário confirmado. O mod guarda o novo snapshot localmente antes de consultar `GET .../worlds/{worldId}/chests/{chestId}`, no mesmo prefixo do endpoint de sincronização configurado. A consulta é assíncrona e limitada pelo timeout e pelo tamanho de resposta já existentes.

A recuperação cria um **novo changeId**, com revisão local crescente e `baseServerRevision` obtida pela consulta. Usa o contrato existente `upsert`, mas só o prepara quando todos os campos fora do inventário coincidem: UUID, mundo/dimensão/blocos, nome, `createdAt` e `structureChanged`. Recusa baús eliminados, estruturas assinaladas e identidades diferentes. Se os metadados mudaram, conserva o conflito para intervenção explícita, sem sobrescrevê-los. Uma alteração remota posterior ao GET incrementa a revisão e provoca outro conflito no POST, em vez de ser sobrescrita.

A sessão de abertura tem de começar **depois** do conflito. Confirmar cliques da sessão anterior, chamar reload, importar um ficheiro ou apenas libertar um bloqueio não autoriza esta recuperação. Exige novo `syncedAt`, persistência concluída e nenhuma confirmação pendente. Uma leitura nova com os mesmos itens também é válida. Confirmações tardias durante a consulta atualizam o snapshot de recuperação, desde que também estejam persistidas.

O conflito antigo continua no diagnóstico até o novo pedido receber `applied` ou `duplicate`. A relação entre a nova alteração e as antigas fica persistida em `inventoryRecoveries`, permitindo concluir a limpeza mesmo após reiniciar. Alterações locais mais recentes permanecem pendentes e seguem depois dessa confirmação.

Se o servidor devolver outro `conflict`, nenhum desses pedidos volta a ser reenviado automaticamente. O estado mantém-se pendente de resolução; uma nova abertura com leitura confirmada permite outra tentativa. Falhas ou respostas inválidas no GET mantêm o conflito e limitam a consulta a uma tentativa por abertura. Os retries de rede de pedidos POST já preparados mantêm o mesmo ID e as regras anteriores de backoff/idempotência.

Outros baús continuam a ser enviados, inclusive enquanto a consulta da revisão está lenta ou indisponível. Com `/cc sync off`, o novo snapshot fica guardado, sem consulta ou envio automático; `/cc sync` ou `/cc sync on` retomam a recuperação. Uma tentativa manual pode aguardar a consulta assincronamente, sem bloquear o Minecraft.

Esta rotina não aplica inventários remotos ao Minecraft, não renomeia registos locais a partir do servidor e não recupera automaticamente operações `rename`, `delete`, `structure_changed` ou `rejected`. Mantêm-se a pena de associação, os backups, a importação/exportação e a fila incremental.

## Validação

`InventoryConflictRecoveryTest` usa um servidor HTTP temporário numa porta dinâmica, com revisões reais no fixture. Cobre sucesso, novo ID/base, outros baús, segundo conflito, restart, erros/timeout/JSON inválido, metadados protegidos, snapshots tardios e modos manual/automático. O Client GameTest acrescenta um conflito HTTP de inventário ao percurso gráfico: alteração confirmada, fecho, conflito persistido, reabertura física, novo snapshot, consulta de revisão, aceitação e limpeza persistida do conflito, verificando que o inventário físico continua intacto.

A finalização confirmada fica numa fila local de trabalho até a gravação exata terminar. Uma falha transitória de escrita não perde essa intenção nem liberta antecipadamente o bloqueio. Uma abertura/clique posterior invalida a finalização da sessão antiga. `ConfirmedInventorySnapshotsTest` cobre esta ordenação, erros de I/O, snapshots tardios, mundos diferentes e importações sem confirmação física.

Resultado: **113 testes unitários passaram**, incluindo **30 novos**, build passou e Client GameTest gráfico passou. [Evidência](validation/inventory-conflict-recovery/results.json). A primeira execução final do GameTest detetou uma falha transitória de escrita no Windows que deixava a finalização por concluir; foi corrigida e o GameTest repetido passou, incluindo a confirmação de limpeza dos conflitos no ficheiro persistido. Não foram alterados mundos reais nem o servidor Python e não foi executado commit/push.
