# Persistência, backups e sincronização incremental

## Três responsabilidades

1. **Estado local:** `worlds/<hash>.json` continua a guardar `formatVersion:1`, `world` e os `ChestRecord` completos, com backups `.bak` e recuperação existente. O cliente agenda gravação no tick seguinte a uma alteração confirmada e imediatamente no fecho/comandos; nunca espera cinco minutos para persistir o inventário.
2. **Histórico:** `backups/<hash-do-mundo>/` guarda snapshots completos. Não é a fila HTTP.
3. **Fila:** `incremental/<hash-do-endpoint>/<hash-do-mundo>.json` guarda alterações, revisões, snapshots locais de comparação, bloqueios e diagnósticos. O servidor aplica apenas as alterações recebidas ao SQLite.

Os hashes tornam os nomes de pastas seguros; os identificadores originais continuam dentro dos documentos. Mesmo UUIDs iguais em mundos diferentes não partilham dados. `client-id.json` contém um UUID gerado uma vez. Conserva-o juntamente com a fila ao mover uma instalação. Não clones a mesma identidade/fila para dois clientes simultâneos.

A interface `SyncProvider` e o transporte anterior continuam disponíveis para compatibilidade dos testes legados. O cliente Minecraft utiliza agora `IncrementalSyncQueue`, `SyncProtocol` e `HttpSyncProvider.exchange`, não a antiga fila de documentos completos. A outbox antiga não é apagada; bloqueios de inventário nela existentes são preservados ao inicializar a fila nova.

## Configuração e migração

Ver [config.example.json](config.example.json). Conservam-se as opções anteriores, incluindo `intervalSeconds` e `connectTimeoutSeconds`; `intervalSeconds` fica como campo legado, sem controlar o novo debounce/backoff.

```json
{
  "sync": {
    "enabled": true,
    "automatic": true,
    "endpoint": "http://127.0.0.1:8080/api/v1/sync",
    "debounceSeconds": 3,
    "retryInitialSeconds": 5,
    "retryMaxSeconds": 300,
    "connectTimeoutSeconds": 5,
    "requestTimeoutSeconds": 10
  },
  "backup": {
    "enabled": true,
    "intervalMinutes": 5,
    "onlyWhenChanged": true,
    "maxAutomaticBackupsPerWorld": 10
  }
}
```

Edita `config/chestcount/config.json` na instância Minecraft e usa `/cc reload`. O endpoint padrão antigo exato `http://127.0.0.1:8080/api/sync` é migrado em memória para `/api/v1/sync`; o ficheiro só é reescrito quando uma opção é guardada. Outros endpoints personalizados são conservados e devem apontar para a rota incremental compatível. Uma configuração inválida conserva o ficheiro original e as opções anteriores.

HTTP apenas em `localhost`, `127.0.0.1` e `::1`; destinos remotos exigem HTTPS. Credenciais na URL, query string e fragmentos são recusados. Não há tokens em configurações nem autenticação nesta fase. Redirects não são seguidos. Limites: pedido 8 MiB, resposta 1 MiB, até 128 alterações por lote do mod. O servidor aceita até 256 por lote.

Mudar o endpoint seleciona uma fila/revisões independentes por destino. O primeiro envio para esse destino faz bootstrap seguro; a fila do destino antigo permanece preservada. Mudar novamente para o endpoint anterior recupera o seu estado, sem confundir revisões entre servidores. `worldAliases` continua a separar contextos locais explicitamente; uma associação futura com mundos partilhados Yii2 deve ser uma configuração explícita, nunca uma inferência pelas coordenadas.

## Deteção e envio

A fila compara o snapshot persistido com o último estado preparado por UUID. Registo cria upsert; inventário consolidado cria upsert; renomeação isolada usa rename; remoção conhecida usa delete; alteração estrutural isolada usa structure_changed. Uma alteração apenas de `syncedAt`, sem mudar conteúdo/identidade/nome/estrutura/estado de confirmação, não cria tráfego redundante. Baús nunca lidos têm `syncedAt:null`; uma lista vazia nesse registo não significa leitura confirmada de um baú vazio.

O clique/abertura bloqueia a saída do baú de forma persistente. As atualizações locais continuam; só o fecho com identidade verificada e sem confirmações em falta liberta esse UUID. Pacotes tardios podem atualizar o documento local e preparar outra alteração. Um baú diferente não desbloqueia uma sessão incompleta. Depois de uma desconexão/crash com confirmações pendentes, reabre e fecha o baú afetado. As regras vanilla, mixins e associação física descritas em [SYNCHRONIZATION.md](SYNCHRONIZATION.md) mantêm-se.

Depois do debounce, o cliente agrupa alterações e faz POST assíncrono, sem bloquear a renderização. Não envia o documento completo do mundo nem um pedido por slot. A thread de disco FIFO persiste o estado e a fila; apenas documentos com gravação local concluída alimentam o agendamento periódico. Não há polling HTTP quando a fila está vazia. O agendador verifica as tarefas uma vez por segundo de jogo e só tenta mundos carregados.

## Revisões, bootstrap e idempotência

Cada alteração tem `changeId`, `clientId`, `worldId`, `chestId`, `dimension`, `revision`, `operation`, `baseServerRevision`, `data`, `createdAt` e `status`. A revisão local cresce por cliente/baú. As revisões do servidor são independentes por `(worldId,chestId)`.

A base inicial é 0 quando o UUID ainda não existe. O primeiro lote de um mundo/destino inclui `bootstrap:true`; o servidor só cria o mundo ausente mediante esse sinal e nunca ignora uma revisão existente. Um UUID já presente com base errada gera conflito mesmo em bootstrap. Depois de receber uma resposta válida, a fila persiste bootstrap concluído. As revisões confirmadas ficam na fila, por isso o reinício não exige adivinhar bases.

Antes de enviar, o cliente grava a alteração como `sent`, com ID, base e payload fixos. Um timeout pode significar que o servidor aplicou o pedido e perdeu-se a resposta: o retry repete o mesmo ID e corpo. O recibo durável do servidor responde `duplicate`, sem voltar a aplicar. A fila valida protocolo, IDs, revisões e estados; remove só `applied` e `duplicate`. Um HTTP 200 sem confirmações correspondentes não confirma nada.

Há no máximo um pedido em curso por mundo/destino e uma alteração por UUID em cada lote. Alterações novas durante o envio ficam atrás da operação enviada; só recebem a nova base depois da confirmação anterior. Uma resposta antiga nunca remove uma alteração mais recente.

Alterações **ainda não enviadas** do mesmo UUID podem ser substituídas por um upsert completo mais recente, preservando nome e inventário. Uma criação que nunca saiu e foi removida pode ser cancelada. Depois de existir qualquer envio, a remoção é um delete posterior e conserva a dependência. Alterações `sent` nunca são compactadas ou recebem outro ID durante o retry.

## Offline, diagnóstico e conflitos

Falhas de ligação, timeout, 408, 429 e erros 5xx mantêm o pedido pendente e usam backoff exponencial configurável, de 5 até 300 segundos por defeito. O prazo e o contador ficam persistidos, assim como os IDs/revisões. O envio manual pode antecipar o prazo sem duplicar um pedido em curso. A saída não espera pela rede. Um sucesso cuja confirmação ainda não foi gravada pode causar reenvio, tratado idempotentemente pelo servidor.

`conflict`, `rejected` e erros HTTP 4xx permanentes ficam retidos para diagnóstico, sem retries infinitos. As alterações dependentes desse UUID também ficam bloqueadas; outros baús podem continuar a sincronizar. `/cc sync status` mostra pendentes, conflitos/rejeições, endpoint e última resposta válida. O timestamp pode corresponder a uma resposta com conflitos; consulta sempre os contadores.

Os conflitos exclusivamente de inventário podem agora ser recuperados ao reabrir e fechar o baú, depois de um novo snapshot autoritativo confirmado e persistido. O mod consulta a revisão atual e cria outro changeId, sem reutilizar a operação rejeitada. Só avança se nome, identidade, data de criação e estrutura ainda coincidirem e não existir tombstone. A confirmação da nova alteração limpa os conflitos antigos. Um segundo conflito fica pendente, sem loop; reabrir novamente permite outra tentativa segura. Ver [recuperação de inventário](INVENTORY-CONFLICT-RECOVERY.md).

Para conflitos de nomes, eliminações ou estrutura, não existe escolha automática do vencedor nem comando que force sobrescrita remota. Para resolver: faz backup/export local, consulta o GET/export remoto e compara os dois estados. A futura ferramenta administrativa deve enviar uma operação nova, com nova identidade de alteração e a revisão atual, depois da decisão explícita. Esses conflitos de metadados e as rejeições permanecem visíveis e preservados para essa intervenção. Não apagues a fila ou tombstones para contornar conflitos.

A fila usa escrita atómica e `.bak`. Em caso de JSON corrompido, tenta a cópia e conserva o original com sufixo `.corrupt-<uuid>`. Sem cópia utilizável, recusa sobrescrever a fila. O estado local continua independente. As escritas de mundo e fila não são uma transação entre ficheiros: ao carregar, a fila reconcilia o estado local persistido. Uma terminação antes de a escrita assíncrona terminar pode perder trabalho apenas em memória; não há garantia contra falha física do disco.

## Backups

O temporizador usa tempo de relógio, verificado nos ticks. Inicia ao carregar o mundo e persiste o último prazo e o hash do último backup automático. A cada cinco minutos, se o documento mudou, grava `auto-<tempo>-<uuid>.json`, relê e valida o snapshot. Conserva os dez automáticos mais recentes. `manual-*.json`, exportações e cópias de recuperação antigas não entram na retenção. Os manuais são forçados e não alteram a comparação com o último automático.

Um mundo descarregado não cria backups enquanto não voltar a ser carregado. Jogo pausado sem ticks pode adiar a verificação. `onlyWhenChanged:false` permite backups periódicos mesmo idênticos; `backup.enabled:false` desativa apenas os automáticos. Backups manuais continuam disponíveis.

## Comandos e import/export

| Comando | Ação |
| --- | --- |
| `/cc sync` | Tenta enviar agora; resultado apenas após confirmação |
| `/cc sync status` | Estado e diagnóstico |
| `/cc sync on` / `off` | Ativa/desativa automático e guarda configuração |
| `/cc backup` | Backup manual completo |
| `/cc backup list` | Lista manuais e automáticos do mundo |
| `/cc export` | Exporta para `config/chestcount/exports/` |
| `/cc import ficheiro.json` | Valida e mostra quantos registos são novos/existentes |
| `/cc import ficheiro.json confirm` | Revalida, faz backup e aplica |

Todos existem também em `/chestcount`; os comandos anteriores permanecem. `/cc sync` manual funciona com automático desligado. `off` não cancela pedidos já enviados. O autocomplete de import procura a pasta dedicada `config/chestcount/imports/` e atualiza enquanto o mundo está carregado.

Para restaurar um backup/export, copia-o primeiro para `imports/` com nome simples ASCII, por exemplo `restauro.json`. O mod não lê caminhos absolutos, `..`, links simbólicos ou ficheiros fora desta pasta. Limite de importação: 8 MiB. Valida versão, tipos, UUIDs, mundo, dimensão, coordenadas, itens, slots, duplicados e sobreposições. Um UUID existente tem de conservar a identidade. Outro mundo é recusado, sem associação automática a baús físicos.

A importação é **merge por UUID**: acrescenta novos e substitui os incluídos, preservando registos locais ausentes do ficheiro. Não é uma eliminação implícita do mundo atual. A confirmação fica ligada ao hash do ficheiro e do estado local; qualquer mudança exige nova pré-visualização. Antes de aplicar, cria um backup manual; depois substitui atomicamente o documento local e prepara as alterações incrementais. O Minecraft nunca recebe alterações físicas de inventário por esta importação.

## Servidor, testes e Yii2

O servidor e a estrutura SQLite estão documentados em `C:\ChestCount-Server\README.md`, com contrato `openapi.json`. A rota nova é `/api/v1/sync`; `/api/sync` fica como arquivo legado e não consolida dados.

```powershell
# Mod
.\gradlew.bat --gradle-user-home .gradle-home test
.\gradlew.bat --gradle-user-home .gradle-home build
.\gradlew.bat --gradle-user-home .gradle-home runClientGameTest
# Integração real, exige porta 8080 livre; gere apenas os filhos Python que inicia
.\gradlew.bat --gradle-user-home .gradle-home realSyncIntegration
# Servidor
python -m unittest -v test_server
```

`realSyncIntegration` usa o servidor em `C:\ChestCount-Server`; `CHESTCOUNT_SERVER_PROJECT` pode selecionar outro caminho. Cria apenas mundos `integration-test:incremental:<uuid>` e preserva os JSONs existentes por comparação de hashes. Não encerra listeners externos; se a porta estiver ocupada, falha sem os alterar.

A API Yii2 futura pode manter o mesmo contrato em HTTPS e substituir o repositório SQLite por MySQL, preservando transações, recibos, cursor global e bases otimistas. O transporte tem um ponto claro para acrescentar Bearer através de um fornecedor de credenciais separado. Não há contas fictícias, website, Android nem sincronização bidirecional ativa. Os eventos GET preparam essa evolução sem alterar o inventário remoto/local nesta fase.

## Item de associação

Usa agora uma **pena vanilla** (`minecraft:feather`), na mão principal ou secundária, com Shift + botão direito no baú. A pena também ativa os contornos. A bússola deixa de associar baús. Os registos existentes mantêm os seus UUIDs e não precisam de nova associação.
