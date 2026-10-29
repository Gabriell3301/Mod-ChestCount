# Chest Count

Mod **exclusivamente client-side** para Minecraft Java **26.3**, Fabric Loader **0.20.1+** e Fabric API **0.165.0+26.3**. Java **25 ou superior**. Não precisa de instalação no servidor, permissões de operador, comandos administrativos ou pacotes personalizados.

## Compilar e desenvolver

No PowerShell, neste diretório:

```powershell
.\gradlew.bat --gradle-user-home .gradle-home build
.\gradlew.bat --gradle-user-home .gradle-home runClient
```

O wrapper utiliza Gradle 9.6.0; Loom 1.17.21. A primeira execução descarrega Minecraft e dependências. O alvo do bytecode é Java 25; o build deste ambiente usa JDK 26.0.1. Em IntelliJ IDEA, abre esta pasta como projeto Gradle e seleciona um JDK 25+ para Gradle. Não é necessário instalar Gradle globalmente.

Instala Fabric para **26.3**, coloca Fabric API e `build/libs/chest-count-1.0.0.jar` na pasta `mods` da instância Minecraft e inicia o perfil Fabric. O JAR `-sources` destina-se à consulta do código.

Versões verificadas em 28/10/2026 nas fontes oficiais:

- [Fabric para Minecraft 26.3](https://fabricmc.net/2026/09/15/263.html)
- [Versões Minecraft no Fabric Meta](https://meta.fabricmc.net/v2/versions/game)
- [Loader para 26.3](https://meta.fabricmc.net/v2/versions/loader/26.3)
- [Fabric API no Maven oficial](https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/)
- [Loom no Maven oficial](https://maven.fabricmc.net/net/fabricmc/fabric-loom/)
- [Projeto exemplo oficial para 26.3](https://github.com/FabricMC/fabric-example-mod/tree/26.3)

## Utilização

1. Segura uma **bússola vanilla numa das mãos**.
2. Faz **Shift + botão direito** num baú normal. Baús armadilhados e ender chests estão excluídos.
3. O mod regista o baú com um nome livre como `Bau_1` e cancela essa interação apenas no cliente. Não força a abertura nem simula pacotes.
4. Larga Shift e abre o baú. Apenas baús previamente registados podem receber snapshots.
5. Usa `/cc list` para consultar os nomes. `/cc rename Bau_1 Minerios` permite passar imediatamente a usar `Minerios` em todos os comandos de gestão.

O contorno azul aparece ao segurar uma bússola em qualquer mão. É desenhado com os gizmos vanilla de 26.3, com teste de profundidade: não é um efeito de visão através de paredes. Só considera baús carregados até 32 blocos, com limite de 256 contornos por frame. Um baú duplo tem uma única caixa.

Mensagens traduzidas em inglês e português de Portugal, conforme o idioma do jogo. O chat usa coordenadas compactas e tempos relativos. UUIDs e datas UTC continuam no JSON; avisos de estrutura alterada permanecem visíveis em `info`.

## Comandos locais

`/cc` e `/chestcount` partilham os mesmos handlers. [Referência completa e exemplos](docs/COMMANDS.md).

| Comando | Função |
| --- | --- |
| `/cc help` | Ajuda |
| `/cc count` | Total de baús, itens e tipos |
| `/cc list [página]` | Nomes e coordenadas, 10 por página |
| `/cc location <baú>` | Nome e coordenadas |
| `/cc nearby [raio] [página]` | Distâncias na dimensão atual; raio padrão 32, máximo 4096 |
| `/cc where <item> [página]` | Quantidade por baú; ID ou nome localizado do item |
| `/cc info <baú>` | Posição, tipo, itens e tempo desde sincronização |
| `/cc rename <baú> <nome>` | Renomeia imediatamente; 1–128 caracteres |
| `/cc remove <baú> [confirm]` | Confirmação válida durante 30 segundos |
| `/cc reload` | Guarda alterações pendentes e recarrega configuração e dados |

Identificadores: nome exato sem distinguir maiúsculas, UUID completo ou prefixo único. Nomes únicos em todas as dimensões do mesmo mundo, com espaços exteriores removidos e normalização Unicode NFC. Usa aspas para nomes com espaços, por exemplo `/cc rename Minerios "Baú de Minérios"`. `info` e `location` também conservam a pesquisa de nomes com espaços sem aspas. O autocomplete prefere nomes; duplicados antigos usam prefixos de UUID. Os dados antigos, incluindo nomes duplicados, não são migrados nem renomeados. Os totais são as últimas leituras conhecidas, incluindo snapshots antigos de estruturas alteradas; não são uma consulta em tempo real.

## Sincronização e limites de segurança

A associação exige um clique local recente (até 5 segundos), registo exato, estrutura carregada consistente, interface `ChestMenu` com 27/54 slots e título igual ao título vanilla ou nome personalizado do bloco. Depois espera um pacote completo do servidor. Guarda apenas os primeiros 27/54 slots, excluindo os 36 slots do jogador. Substitui snapshots e aplica as atualizações individuais recebidas do servidor; não lê alterações previstas pela interface nem inventários fechados.

**Não existe no protocolo vanilla um identificador de posição dentro do pacote de abertura de interface.** Por isso, sem cooperação do servidor, não é possível provar em todos os casos que uma interface pertence ao bloco clicado. Em singleplayer, verifica adicionalmente na thread do servidor integrado se o contentor aberto contém exatamente as entidades dos blocos registados; apenas a identidade é consultada, nunca os itens do servidor. Em multiplayer aceita servidores que anunciam a marca `vanilla`; servidores com outra marca são bloqueados por defeito. Uma marca vanilla, um título e o tamanho dos slots não são uma garantia contra servidores que deliberadamente imitam esse comportamento. Não autorizes servidores desconhecidos que tenham menus personalizados.

Se conheces o servidor e verificaste que os baús abrem inventários físicos normais, podes adicionar a sua chave `server:host[:porta]` a `trustedServers` em `config.json`. Isso aceita explicitamente a limitação da associação por clique/título/estrutura; não acrescenta qualquer protocolo ou instalação no servidor. Interfaces com título ou tamanho diferente continuam recusadas. O mod não sincroniza automaticamente menus arbitrários de plugins. Uma recusa pode resultar em baús registados sem itens, que continuam pesquisáveis por nome e posição.

Os snapshots guardam o **último estado recebido em pacotes**. Nos cliques reais de uma sessão associada, o mod envia uma revisão divergente (`stateId = -1`): o servidor vanilla executa a ação e responde com o estado completo, mesmo quando a previsão local estava correta. Não envia cliques adicionais nem guarda a previsão como confirmação. O fecho agenda a escrita e conserva a associação até 100 ticks para receber respostas em trânsito. Não existe um snapshot garantido no pacote de fecho. Se faltar uma confirmação, surge um aviso curto; reabre o baú. Consulta os detalhes e limites em [docs/SYNCHRONIZATION.md](docs/SYNCHRONIZATION.md). Nenhum conteúdo fechado ou alteração de outro jogador fora da interface é observado. Itens são agregados por ID, sem distinguir componentes/NBT, encantamentos ou conteúdo de shulker boxes.

O mod observa alterações na estrutura de baús carregados. Ao juntar/separar baús ou destruir uma metade, assinala os registos antigos, preserva o snapshot e bloqueia novas sincronizações. Não tenta dividir ou somar snapshots, pois a ordem e os itens podem ter mudado. Usa `info`, confirma a remoção dos registos antigos e regista a estrutura atual. Clicar nas duas metades de um duplo produz a mesma identidade canónica. Proximidade isolada nunca cria uma associação.

Destruição e substituição de um baú enquanto o chunk está descarregado não são observáveis: não existe um UUID persistente do bloco no protocolo. A localização continua a ser a identidade conhecida até o cliente observar outra estrutura.

## Mundos, armazenamento e configuração

Tudo fica em `config/chestcount/`, relativo à instância Minecraft, sem envio para serviços externos:

```text
config.json                 opções e aliases manuais
worlds/<sha256>.json         documento por identidade de mundo, incluindo todas as dimensões
worlds/<sha256>.json.bak     versão anterior válida, antes de cada substituição
backups/*-corrupt-*.json     originais danificados preservados durante recuperação
```

Cada documento contém `formatVersion: 1`, identidade do mundo, UUIDs, nome, posições canónicas, dimensão, datas UTC, itens por slot e estado de alteração estrutural. O tipo simples/duplo deriva de um/dois blocos da identidade; não há um segundo campo redundante. As posições são ordenadas lexicograficamente, mas os slots mantêm a ordem recebida do servidor.

Escritas são agrupadas a cada 2 segundos e agendadas imediatamente no fecho, nas confirmações recebidas após fecho e na desconexão/saída, numa única thread de disco FIFO. Snapshots imutáveis enfileirados mantêm a ordem: uma escrita antiga não ultrapassa uma recente. Os documentos são substituídos por movimento atómico quando suportado. Um encerramento abrupto pode perder alterações ainda na fila. Leituras validam o documento completo antes de substituir o estado. JSON corrompido recupera da cópia `.bak`, preservando o original; sem backup válido, a carga falha e o mod recusa sobrescrever o ficheiro. Corrige o ficheiro e executa `reload`. Uma versão de formato desconhecida é recusada sem recuperação automática para um formato antigo. Não existem migrações na versão inicial; qualquer migração futura deve criar backup antes de converter.

Configuração criada no primeiro arranque:

```json
{
  "formatVersion": 1,
  "alwaysShowOutlines": false,
  "outlineRadius": 32,
  "nearbyRadius": 32,
  "trustedServers": [],
  "worldAliases": {}
}
```

`outlineRadius`: 1–128; `nearbyRadius`: 1–4096. Edita o JSON e usa `reload`. Um ficheiro inválido é preservado, e a configuração em memória mantém-se.

Singleplayer usa SHA-256 do caminho absoluto normalizado da pasta de gravação. Assim distingue mundos com o mesmo nome apresentado. A identidade mantém-se enquanto a pasta não mudar; mover/renomear a pasta requer migração manual dos dados. Multiplayer usa endereço normalizado, removendo porta padrão 25565, preservando portas diferentes e sem resolver DNS. Endereços alternativos do mesmo servidor são identidades diferentes. As dimensões usam os identificadores oficiais, incluindo dimensões personalizadas.

Um endereço pode conter vários mundos lógicos indistinguíveis pelo cliente. O sufixo manual `worldAliases` separa esses contextos:

```json
"worldAliases": { "server:play.exemplo.pt": "sobrevivencia" }
```

Muda o alias e recarrega ao mudar de mundo lógico; não existe deteção automática de trocas internas do servidor. A identidade final é `server:play.exemplo.pt|sobrevivencia`. O alias também está disponível para chaves `local:<sha256>`. Não são guardadas credenciais.

## Arquitetura e evolução

- `model`: identidades canónicas e snapshots imutáveis, sem dependências Minecraft.
- `service`: registo, pesquisa, política de associação e sincronização de pacotes.
- `storage`: JSON validado, backups e substituição atómica.
- `interaction`: bússola e validação das duas metades do baú.
- `commands` e `rendering`: integrações Fabric client-side.
- `integration/SyncProvider`: contrato futuro independente do armazenamento. Não há implementação REST nem endpoints fictícios.

Uma futura integração Yii2 deverá implementar associação de contas, armazenamento de tokens no cofre do sistema operativo, mapeamento explícito de mundos partilhados, fila persistente offline, revisões idempotentes e resolução de conflitos antes de ativar envios. O JSON atual pode continuar como cache offline. Não são implementadas autenticação nem uma fila remota nesta versão.

## Validação

```powershell
.\gradlew.bat --gradle-user-home .gradle-home test
.\gradlew.bat --gradle-user-home .gradle-home runClientGameTest
```

Testes unitários em `src/test/java`: registo simples/duplo, canonicalização, duplicados, mundos, dimensões, snapshots, pesquisa de itens/nomes, distâncias, remoção, renomeação, associação de interfaces, estruturas, persistência, recuperação e proteção contra corrupção. Relatórios em `build/reports/tests/test/index.html`. Estes testes não exercitam o motor Minecraft, gráficos nem um servidor real.

`src/gametest` contém um teste de integração separado do JAR final. `runClientGameTest` abre um cliente e um servidor integrado reais num mundo temporário dentro de `build/run/clientGameTest`, testa pacotes/interface/estrutura e termina automaticamente. Precisa de ambiente gráfico e descarrega os recursos oficiais do jogo. O diretório temporário do teste é recriado pela tarefa; não usa os teus mundos em `run/saves` ou noutras instâncias. Capturas ficam em `build/run/clientGameTest/screenshots`.

O roteiro de testes dentro do jogo está em [docs/MANUAL-TESTS.md](docs/MANUAL-TESTS.md). Consulta [docs/VALIDATION.md](docs/VALIDATION.md) para os resultados realmente executados neste ambiente.
