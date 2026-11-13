# Ligação por código e atualização de 10/11/2026

## Instalação

Projeto Gradle na raiz de `C:\Users\gabri\Desktop\Mod-ChestCount`. Alvo: Minecraft Java 26.3, Java 25+, Fabric Loader 0.20.1+ e Fabric API 0.165.0+26.3. Compilar com o Wrapper:

```powershell
.\gradlew.bat build --console=plain
```

Instalar `build/libs/chest-count-1.0.0.jar` na pasta `mods` do perfil Fabric, juntamente com Fabric API. Não instalar o JAR de fontes. O mod mantém as funcionalidades existentes de observação, comandos, backup, export e import; não escreve inventários físicos.

## Ligar à conta

1. Atualizar o Yii2 e executar a migration incremental `m261110_000001_minecraft_devices`.
2. Abrir Perfil → Ligar Minecraft e gerar um código de 12 caracteres, válido durante 5 minutos e uma única utilização.
3. No Minecraft executar `/cc connect CODIGO`. Nenhum token de ambiente é necessário.
4. Executar `/cc status` para validar a credencial no servidor; `/cc sync status` mostra a fila e os diagnósticos.
5. Abrir um mundo, mesmo sem baús. O mod publica identidade e nome original automaticamente. Um nome personalizado no website não altera o save nem a identidade.

O endpoint local é `http://127.0.0.1:8080/index.php/api/v1/sync`. Outro website exige definir apenas `sync.endpoint` em `config/chestcount/config.json` e `/cc reload`. Endereços remotos exigem HTTPS e não admitem credenciais, query string, fragmento ou redirects. Confirmar o domínio antes de ligar.

`/cc disconnect` suspende localmente, revoga no servidor e elimina a credencial quando recebe confirmação. Se a rede falhar mantém a revogação pendente, sem uploads normais; repetir o comando quando a rede voltar ou revogar pelo website e repetir para limpar a credencial. Não afirmar que uma credencial foi revogada remotamente enquanto offline. Antes de substituir uma ligação existente executar disconnect.

## Segurança e compatibilidade

`DeviceCredentials` guarda o token em `config/chestcount/credentials/<hash-endpoint>.json`, separado de config, snapshots, arquivos e fila, com ACL Windows ou permissões POSIX apenas do proprietário. O segredo precisa de estar disponível ao cliente; esta proteção não é uma cifra contra processos da mesma conta. Não partilhar essa pasta. O token fica vinculado ao endpoint e é revogável no servidor. O JSON da API devolve o segredo uma vez; o MySQL guarda apenas SHA-256.

O token antigo `CHESTCOUNT_API_TOKEN` continua como fallback para instalações existentes sem dispositivo local. Uma ligação nova tem prioridade. Um disconnect explícito deixa marcador para não reativar inadvertidamente o token legado. Tokens legados exigem revogação administrativa, pois não têm dispositivo associado. Erros e mensagens não incluem segredos; o `toString` da credencial é redigido.

O servidor continua a determinar o titular; o cliente não envia um `user_id` escolhido. Conservar IDs e revisões evita duplicação; `Authorization: Bearer` mantém-se em todos os uploads e leituras de revisão. Gson continua a usar `serializeNulls()` na fila: o upsert não observado contém `syncedAt:null` e `items:[]`.

## Mundos

`SaveWorldIdentity` grava `chestcount-world-id.json` no save. A primeira identidade reproduz o hash do caminho usado pela versão anterior, evitando duplicações na atualização. Mudanças de pasta mantêm a identidade guardada. Uma cópia cujo caminho anterior ainda existe recebe outra identidade persistente. Cópia/restauro quando o caminho original já não existe é indistinguível de mudança e conserva a identidade; não há metadados suficientes para garantir distinção universal. Não editar este ficheiro durante utilização normal; conteúdo inválido bloqueia o acompanhamento e preserva o ficheiro.

Multiplayer conserva endereço normalizado e `worldAliases`. O nome enviado é o da entrada do servidor; vanilla não transmite ao cliente a identidade persistente/nome interno de cada mundo remoto ou os resets do servidor. Dimensões pertencem à identidade dos baús, não criam mundos duplicados.

## Fila e recuperação

`IncrementalSyncQueue` guarda operações antes do envio, congela IDs/payload/base e aplica confirmações apenas à versão correspondente. Os estados antigos e snapshots locais são preservados nos reinícios.

- 400 e 4xx permanentes: rejected, sem repetição automática.
- 401/403: preservar operações e mostrar recuperação por ligação, com backoff.
- 409 do envelope: voltar a pedir bootstrap, conservando alterações. Conflitos individuais usam leitura de revisão e observação nova após reabrir/fechar o baú.
- 408/429/5xx/rede/resposta inválida: retentativa com backoff exponencial configurável.
- `/cc sync retry-rejected`: libertar só rejeições de transporte, após corrigir a causa. Para o antigo upsert sem `syncedAt` e sem itens, arquivar o original em `recoveredTransportHistory`, acrescentar null e criar nova operação; nunca apagar a fila inteira.

`worldName` é uma extensão opcional do envelope protocolo 1. A fila envia bootstrap vazio e alterações de nome mesmo sem alterações de baús. Rejeições do registo vazio também ficam bloqueadas até correção/retentativa explícita.

## Rotas

Relativas à base `/index.php`:

| Rota | Uso |
|---|---|
| `POST /api/v1/link/exchange` | JSON `{code,label}` → `{deviceId,token,expiresAt}` |
| `GET /api/v1/device/status` | Bearer → `{connected:true,deviceId}` |
| `POST /api/v1/device/disconnect` | Bearer → `{disconnected:true}` |
| `POST /api/v1/sync` | Protocolo 1 com `worldName` opcional |
| `GET /api/v1/worlds/{source-encoded}/chests/{uuid}` | Metadados de revisão para reconciliação |

Os endpoints de conta e gestão no website usam sessão/CSRF e nunca o Bearer de dispositivo. Contrato completo, migrations, BlocksItems, testes e operação encontram-se no repositório Yii2: `docs/MINECRAFT-INTEGRATION.md` e `docs/openapi-minecraft.json`.

## Validação reproduzível

JUnit: `.\gradlew.bat build`. A task usa um token explicitamente sintético apenas nos servidores HTTP de teste, sem herdar segredos do launcher.

Integração real: no Yii2 iniciar PHP em `127.0.0.1:8090` com DocumentRoot `frontend/web`, preparar a base isolada existente e executar `php scripts/test-minecraft-integration.php fixture`. Depois, neste projeto:

```powershell
.\gradlew.bat yiiSyncIntegration --console=plain
```

O runner lê a fixture privada em `../Dados-ChestCount/.work/minecraft-integration.json`, troca o código através de HTTP, guarda/reabre a credencial, usa a fila e modelos Java reais para criar mundo vazio, publicar baús simples/duplos/vazios/não observados, alterar quantidades/estrutura, ler null explícito e confirmar revogação 401. Não usa o stub Python da antiga task `realSyncIntegration`.

Teste dentro do jogo: `runClientGameTest`, cliente e servidor integrados, com HTTP de teste local. Neste computador foi necessário limitar a JVM para evitar falha de memória nativa:

```powershell
$env:CHESTCOUNT_API_TOKEN='AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA' # sintético, só teste
$env:JAVA_TOOL_OPTIONS='-Xmx1024m -XX:ActiveProcessorCount=2'
.\gradlew.bat runClientGameTest --console=plain --max-workers=2
```

Validar `CHESTCOUNT_GAME_TEST_COMPLETED` em `build/run/clientGameTest/logs/latest.log`; exit code 0 sem esse marcador não prova execução completa. O cenário executa connect/status/disconnect, observações de baús, slots, sincronização automática e recuperação. O servidor HTTP deste cenário é uma simulação de teste, distinta da integração real Yii2/MySQL. Os ficheiros deste source set não entram no JAR instalável.

O cenário gráfico **com o backend real** é ativado por `CHESTCOUNT_YII_GAME_FIXTURE`, apontando para uma fixture nova criada com `php scripts/test-minecraft-integration.php fixture`. Não consumir esse código no runner `yiiSyncIntegration` antes do jogo. Manter o PHP de testes em `127.0.0.1:8090`, executar `runClientGameTest` com a variável definida e exigir `CHESTCOUNT_YII_GAME_TEST_COMPLETED` no log. Em seguida, no website, executar `php scripts/test-minecraft-integration.php verify-game` e `node scripts/test-minecraft-browser.cjs`. Este percurso foi executado: o cliente gráfico publicou baús físicos simples/duplos no Yii2/MySQL e o browser consultou os mesmos registos. O cenário adicional só aceita o endpoint de testes em loopback.

Os testes não equivalem a implantação HTTPS em produção, utilização num servidor multiplayer público ou reinício manual de um launcher com o JAR instalado. Estes passos ficam no roteiro manual do relatório Yii2.
