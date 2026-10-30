# Sincronização dos inventários

## Diagnóstico

O código original já incrementava a revisão ao receber conteúdo/slots e agendava gravações automáticas a cada 40 ticks. Os comandos não eram a única origem de escritas. Porém, o servidor vanilla compara os hashes previstos enviados pelo cliente: se a previsão estiver correta, `broadcastChanges` pode não reenviar os slots alterados. O snapshot baseado apenas nos pacotes ficava antigo. O teste anterior só alterava itens diretamente no servidor, sem exercitar cliques previstos no cliente.

Também não existia finalização explícita da sessão. `valid()` exigia o menu ainda aberto; no tick seguinte ao fecho, a sessão era descartada, incluindo possíveis respostas em trânsito e a confirmação assíncrona da identidade no servidor integrado.

## Implementação

1. A interação com um baú já registado prepara uma associação. Nenhuma abertura cria registos.
2. O pacote de abertura deve corresponder ao clique recente, mundo, dimensão, geometria, título vanilla/customizado e tamanho de `ChestMenu`. Mantém-se a política de confiança do servidor. Em singleplayer mantém-se a verificação assíncrona da identidade física do inventário; os itens nunca são lidos diretamente do servidor integrado.
3. O primeiro pacote completo fornece os 27/54 slots do baú; os 36 slots do jogador são excluídos. Atualizações individuais só afetam índices do baú. Todos os `ItemStack` recebidos são copiados.
4. Em cada clique real numa sessão associada, `ContainerInteractionMixin` troca apenas a revisão enviada por `-1`. O servidor vanilla 26.3 processa normalmente a ação e, ao detetar revisão divergente, chama `broadcastFullState`. Não há cliques adicionais, alteração da ação ou aceitação de slots previstos como dados confirmados. Isto cobre PICKUP, QUICK_MOVE, SWAP e as outras ações que passam por `handleContainerInput`, incluindo arrastar.
5. `LocalPlayerMixin` observa o fecho local ou imposto pelo servidor em `clientSideCloseContainer`. Guarda o último snapshot válido e chama `flush()`, sem mensagem de sucesso. Uma deteção no tick cobre substituições de menu que evitem esse caminho.
6. A sessão fechada permanece até 100 ticks para aceitar pacotes que já estavam em trânsito. A geometria e o mundo continuam a ser verificados, mas o menu do jogador já não precisa de estar aberto. Cada confirmação tardia agenda nova escrita. Um novo pacote de abertura substitui a sessão anterior; no transporte vanilla ordenado, as respostas aos cliques anteriores precedem a abertura seguinte.
7. O token `sequence` invalida callbacks de verificações antigas. A posição do clique pendente é separada da posição da sessão anterior. Uma confirmação nunca é aplicada a outro registo, dimensão ou geometria.
8. Os snapshots são registos imutáveis, copiados para uma única fila FIFO de disco já existente. Escritas antigas terminam antes das recentes. O token `generation` impede callbacks de carga/erro de alterarem outro mundo. Erros de escrita são registados e comunicados; o agendamento é reativado para nova tentativa no mundo ativo.

Não foi alterado o formato JSON 1: UUIDs, nomes antigos, identidades, posições, timestamps, itens e `structureChanged` são preservados. Não é necessária migração. Permanecem backups, recuperação com preservação de ficheiros corrompidos e substituição atómica quando suportada.

## Limites

- O protocolo vanilla não envia obrigatoriamente um inventário final ao fechar. Guarda-se a última resposta autoritativa, nunca o estado previsto na interface. Itens no cursor não pertencem aos slots do baú.
- Cada clique provoca uma resposta completa adicional em baús registados: há mais tráfego que no vanilla sem este mecanismo. Não há polling nem tráfego extra para menus sem associação.
- Respostas que não cheguem em 100 ticks, desconexão, troca de mundo, falha de verificação de identidade ou menus modificados podem deixar apenas a confirmação anterior. Havendo confirmações pendentes ao descartar a sessão, mostra-se um aviso curto para reabrir. O cliente não pode garantir o resultado de ações ainda não confirmadas numa ligação interrompida.
- Uma abertura fechada antes de a verificação física integrada terminar pode ser recusada conservadoramente. Callbacks antigos não recuperam sessões já substituídas.
- Não se observam alterações enquanto o baú está fechado. Reabrir recebe um snapshot completo.
- Marca vanilla, título e geometria não provam a identidade de menus de servidores que imitem o vanilla. Mantêm-se `trustedServers`, proteção estrutural e os limites de identificação de mundos descritos no README.
- Não existe mod no servidor nem API Yii2 implementada. O envio HTTP dos snapshots confirmados está descrito em [HTTP-SYNC.md](HTTP-SYNC.md).

## Validação

O GameTest usa cliente, mixins, pacotes e servidor integrado reais: abertura, primeiro snapshot, alterações do servidor, Shift+Click, retirar/colocar, troca por hotbar, fecho no mesmo tick do último clique, gravação automática e reabertura. Também verifica baús duplos, exclusão de itens do jogador e invalidação estrutural. A leitura do JSON é verificada antes de executar comandos de gestão. O roteiro manual acrescenta latência, interrupção de ligação, arrastar e servidor multiplayer externo.

## Evolução HTTP

A confirmação vanilla descrita acima alimenta agora a [fila incremental](INCREMENTAL-SYNC.md), com bloqueios por UUID, backups independentes e servidor SQLite. A outbox de documentos completos foi preservada como legado.
