# Relatório - persistência e sincronização incremental

Implementado e validado em 30/10/2026 nos dois projetos, sem commit, push, staging, alteração de remotes ou eliminação dos JSONs antigos. Não foram instaladas dependências.

## Resultado funcional

- Mod: estado local confirmado, fila incremental persistente por mundo/destino, UUID de cliente persistente, debounce, revisões por cliente, bases do servidor, bootstrap, confirmações individuais e retries idempotentes.
- Backups completos automáticos independentes a cada cinco minutos quando há alterações, retenção dos dez automáticos mais recentes; manuais preservados.
- Exportação e importação com pré-visualização, confirmação ligada ao conteúdo, backup prévio, validação e merge atómico por UUID.
- Servidor: SQLite consolidado, transações, upsert/rename/delete/structure_changed, tombstones, conflitos otimistas, recibos idempotentes, cursor global e consultas/exportação.
- Associação e contornos agora usam **pena** (`minecraft:feather`), em qualquer mão. Shift + botão direito mantém a interação existente. A bússola já não associa baús; UUIDs existentes permanecem.

## Ficheiros criados nesta implementação

No mod:

- `integration/IncrementalSyncQueue.java`, `integration/SyncProtocol.java`.
- `storage/WorldArchiveService.java`.
- `interaction/ChestAssociationHandler.java`: renomeado a partir do handler de bússola.
- `src/test/java/com/chestcount/mod/IncrementalSyncTest.java`, `WorldArchiveTest.java`, `RealSyncIntegration.java`.
- `docs/INCREMENTAL-SYNC.md`, este relatório e `docs/validation/incremental/` com evidências e exemplos reais.

No servidor:

- `repository.py`, `validation.py`, `inspect_db.py`, `test_server.py`.
- `openapi.json`, `README.md`, `examples/request.json`, `examples/response.json`.
- `data/chestcount.db` e logs de execução; os ficheiros SQLite não são código para publicar.

## Ficheiros modificados

No mod: `ChestCountClient.java`, `commands/ChestCountCommands.java`, `config/ModConfig.java`, `integration/HttpSyncProvider.java`, `storage/JsonStorageService.java`, `rendering/ChestOutlineRenderer.java`, os textos de ajuda `en_us.json`/`pt_pt.json`, `ChestCountGameTest.java`, `StorageTest.java`, `build.gradle`, README e documentação de configuração/comandos/sincronização/validação/testes manuais.

No servidor: `server.py`, mantendo a rota legada isolada. Os adaptadores e testes HTTP legados da primeira integração foram conservados.

## SQLite

`worlds`, `chests`, `chest_items`, `processed_changes` e `server_changes`. Chaves compostas por mundo/UUID, FK dos itens para o baú, unicidade global de changeId, revisão do baú, tombstone e sequência global de eventos. O repositório aplica baú, itens, recibo e evento na mesma transação `BEGIN IMMEDIATE`, com WAL e ligações fechadas explicitamente. A descrição completa está no README do servidor.

## Comandos e endpoints

Comandos novos/adaptados: `/cc sync`, `/cc sync status`, `/cc sync on`, `/cc sync off`, `/cc backup`, `/cc backup list`, `/cc export`, `/cc import ficheiro.json`, `/cc import ficheiro.json confirm`. Os aliases `/chestcount` e todos os comandos anteriores foram preservados.

API: POST `/api/v1/sync`; GET `/api/v1/health`, `/api/v1/worlds`, `/api/v1/worlds/{worldId}/chests`, `/api/v1/worlds/{worldId}/chests/{chestId}`, `/api/v1/worlds/{worldId}/export`, `/api/v1/worlds/{worldId}/changes?since=<cursor>&limit=100`. POST `/api/sync` continua como arquivo legado, sem alterar SQLite.

## Testes executados

- `python -m unittest -v test_server`: **23 testes, todos passaram**, com bases temporárias e portas dinâmicas. O erro SQLite injetado pelo teste de rollback aparece no log interno deliberadamente; o teste passou e confirmou reversão do inventário, revisão e evento.
- `./gradlew.bat --gradle-user-home .gradle-home test`: **83 testes**, 0 falhas, 0 erros, 0 ignorados. Inclui todos os anteriores.
- `./gradlew.bat --gradle-user-home .gradle-home build`: **BUILD SUCCESSFUL**.
- `./gradlew.bat --gradle-user-home .gradle-home runClientGameTest`: **passou com interface gráfica**, mundo temporário, pacotes vanilla reais, pena nas duas mãos, recusa da bússola como associador, confirmações tardias, envio HTTP automático incremental e comandos existentes/novos, incluindo importação confirmada. O HTTP deste teste usa porta dinâmica.
- `./gradlew.bat --gradle-user-home .gradle-home realSyncIntegration`: **passou**, 24 verificações registadas, com o provider/fila Java e o Python real na porta 8080. Criou 32 diamantes, atualizou para 48 no mesmo registo, renomeou, confirmou duplicado sem incrementar revisão, eliminou logicamente, testou offline e reinício da fila, retry automático, dois reinícios Python, tombstone persistente, cursor, exportação, importação e preservação dos JSONs anteriores.
- `python -m py_compile ...`: passou; OpenAPI JSON analisado com oito rotas.
- `git diff --check`: sem erros de whitespace.

Evidências: [resumo unitário](validation/incremental/unit-summary.json), [integração real](validation/incremental/report.json), [pedido real recuperado da outbox](validation/incremental/request.json), [recibo real SQLite](validation/incremental/response.json), [exportação remota](validation/incremental/server-export.json). O Postman CLI não estava disponível; a validação HTTP usou os servidores locais temporários existentes nos testes e o servidor real, sem instalar ferramentas.

## Iniciar e testar

1. Com a porta livre, abre PowerShell e executa `cd C:\ChestCount-Server` seguido de `python server.py`. Confirma `Invoke-RestMethod http://127.0.0.1:8080/api/v1/health`. Para parar uma execução em terminal próprio, Ctrl+C apenas nesse terminal.
2. Instala `build/libs/chest-count-1.0.0.jar` na instância Fabric existente, juntamente com as dependências Minecraft/Fabric já documentadas no README.
3. Configura `sync.endpoint` para `http://127.0.0.1:8080/api/v1/sync`; inicia Minecraft ou usa `/cc reload` num mundo carregado.
4. Segura uma pena, Shift + botão direito para registar; abre normalmente, coloca 32 diamantes e fecha. Após cerca de três segundos consulta `/cc sync status` e `python inspect_db.py`.
5. Altera para 48 diamantes e fecha; o UUID no servidor mantém-se e a revisão aumenta. Após cinco minutos com alterações deve existir um `auto-*.json` na pasta de backups do mundo.
6. Para testar offline no teu terminal, para apenas este servidor com Ctrl+C, altera um baú e volta a iniciar `python server.py`. A fila tenta novamente com backoff. `/cc sync` antecipa a tentativa.
7. Usa `/cc export`; copia o ficheiro para `config/chestcount/imports/restauro.json`; executa `/cc import restauro.json` e depois `/cc import restauro.json confirm`.

JAR final SHA-256: `9409f1cbfe794fb7e8d5c5dea0e9a6234563831cf0f66df11c68a1b00f3ada04`.

## Limitações conhecidas

- Sem autenticação, website Yii2, Android ou sincronização bidirecional automática. O servidor de testes deve continuar em loopback.
- Conflitos ficam retidos para diagnóstico; não há resolução automática nem comando de sobrescrita forçada.
- Importação faz merge no mesmo mundo, não remapeia mundos nem remove automaticamente registos ausentes.
- A janela de cinco minutos foi verificada com tempo simulado no serviço de backups, sem esperar cinco minutos de jogo contínuo. Backups manuais e importação foram exercitados no GameTest gráfico.
- O GameTest usa um mundo temporário. Não foram criados dados falsos nem executada uma sessão manual nos mundos reais do utilizador. O cenário real Java/Python foi validado separadamente e não é apresentado como sessão manual nesse mundo.
- Escritas são assíncronas; uma terminação antes da sua conclusão pode perder trabalho apenas em memória. Alterações enfileiradas e confirmadas em disco sobrevivem a reinícios; não há garantia contra falhas físicas do disco.

## Estado do servidor entregue

O novo servidor foi iniciado no terminal gerido em `127.0.0.1:8080`, depois dos testes. `C:\ChestCount-Server\running-server.json` identifica o PID, executável, linha de comando e data de criação verificados por CIM. A tentativa de o iniciar oculto em segundo plano foi bloqueada pela política da ferramenta; foi iniciado normalmente no terminal gerido, com logs visíveis. Não inicies outra instância na mesma porta enquanto esta estiver ativa.

Para parar essa execução, confirma que o PID ainda tem a mesma linha de comando, executável e data de criação do manifesto antes de usar Stop-Process nesse PID. Para sessões futuras num terminal teu, Ctrl+C é suficiente. O teste de integração real foi repetido após a última alteração Python e voltou a passar.
