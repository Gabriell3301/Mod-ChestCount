> Histórico da primeira integração por documento completo. O cliente atual utiliza [sincronização incremental e backups](INCREMENTAL-SYNC.md). A rota legada permanece separada no servidor.

# Sincronização HTTP

## Configuração

Edita `config/chestcount/config.json` na instância Minecraft e conserva os campos existentes. Acrescenta o objeto `sync` de [config.example.json](config.example.json), ou usa os valores padrão: `enabled=true`, `automatic=true`, `endpoint=http://127.0.0.1:8080/api/sync`, `intervalSeconds=30`, `connectTimeoutSeconds=5`, `requestTimeoutSeconds=10`.

Usa `/cc reload` num mundo carregado para aplicar a configuração. Uma configuração inválida conserva o ficheiro e a configuração anterior em memória. Os intervalos aceites são 1 a 86400 segundos, 1 a 120 segundos para ligação e 1 a 300 segundos por pedido.

Apenas HTTPS remoto é aceite. HTTP é permitido exclusivamente para `localhost`, `127.0.0.1` e `::1`; endereços privados de LAN não são considerados loopback. URLs com credenciais, query string ou fragmento são recusados. Redirects não são seguidos. Não coloques tokens neste ficheiro.

## Comandos

- `/cc sync`: tenta enviar imediatamente o documento mais recente do mundo atual; funciona mesmo com modo automático desativado. Aguarda o fecho e as confirmações antes de tentar novamente. A mensagem de sucesso só aparece depois de uma resposta 2xx.
- `/cc sync status`: mostra endpoint, modo automático, uma revisão pendente do mundo, indisponibilidade após uma falha e último envio confirmado. O contador representa documentos pendentes, não slots ou baús.
- `/cc sync off`: persiste `automatic=false`; não cancela um pedido que já saiu.
- `/cc sync on`: persiste `automatic=true` e `enabled=true`.

Os mesmos comandos existem em `/chestcount`. Um pedido em curso pode terminar depois de mudar a configuração; a confirmação fica associada ao endpoint usado nesse pedido.

## Fluxo e persistência

O JSON local continua a ser atualizado pelos pacotes confirmados. Ao fechar, o mod guarda o snapshot e atualiza atomicamente `outbox/<hash-do-mundo>.json`. O envio usa `HttpClient.sendAsync`, POST UTF-8 com `Content-Type: application/json; charset=utf-8`, fora da thread Minecraft.

Esta versão envia o documento completo do mundo atual: `formatVersion`, `world` e `chests`, com os mesmos registos usados pelo armazenamento local. Conserva UUID, nome, mundo/dimensão/blocos, itens por slot, `createdAt`, `syncedAt` e `structureChanged`. `syncedAt` continua a significar confirmação local do inventário; o último sucesso HTTP fica na outbox. Nunca mistura mundos num documento.

Agrupa alterações durante aproximadamente 2 segundos e verifica a fila uma vez por segundo de jogo. Não envia por slot. O limite é 8 MiB por pedido; documentos maiores permanecem pendentes. Não há fragmentação nesta versão.

Um inventário aberto ou com confirmações em falta bloqueia os envios do mundo. O bloqueio é persistente: após uma interrupção sem confirmação, reabre e fecha o baú afetado para obter uma sessão confirmada. Cada baú em falta conserva o seu bloqueio; confirmar outro baú não o liberta. Respostas tardias atualizam o documento e voltam a agendar envio. Uma resposta HTTP antiga confirma apenas o hash do documento e endpoint enviados, preservando versões posteriores pendentes.

A outbox guarda apenas o documento mais recente e o hash confirmado, não dezenas de cópias. No carregamento de um mundo, reconcilia o documento local com a outbox, conservando bloqueios existentes. Mundos desligados conservam a fila; voltam a ser tentados quando forem carregados. As escritas local/outbox são atómicas individualmente, não uma transação entre dois ficheiros. Como no armazenamento anterior, uma terminação abrupta pode perder trabalho ainda em memória.

## Offline e segurança

Erros de ligação, timeout e respostas fora de 2xx conservam a revisão pendente. As tentativas automáticas usam backoff exponencial, inicialmente pelo menos 5 segundos ou `intervalSeconds`, até 5 minutos. Não mostram erros repetidos no chat. O modo manual permite tentar antes do prazo. Após reiniciar, a fila continua persistida; o contador de falhas e o prazo de backoff começam de novo.

A saída não espera pelo servidor HTTP. Uma confirmação não persistida pode causar reenvio na sessão seguinte. A entrega é pelo menos uma vez: o servidor Python pode guardar documentos repetidos. Não existe protocolo de revisões remoto, autenticação ou conflitos. O corpo das respostas é descartado: nunca executa comandos nem altera inventários com dados remotos.

## Servidor Python e API futura

Com o servidor existente ativo em `127.0.0.1:8080`, inicia o mod, regista/abre um baú, altera itens, fecha e aguarda alguns segundos. Consulta `/cc sync status` e verifica um novo documento em `C:\ChestCount-Server\data`. Não é necessário modificar `server.py` nem documentos recebidos anteriormente. Para testar recuperação offline, podes desligar e voltar a iniciar o teu servidor manualmente; o mod conserva os dados e tenta novamente.

Para Yii2, configura por exemplo `https://api.chestcount.pt/v1/sync` e executa `/cc reload`. A futura API deve aceitar POST com o mesmo documento e devolver 2xx apenas depois de aceitar os dados. Não é necessário recompilar. Autenticação Bearer futura deve ser acrescentada no transporte através de um fornecedor de credenciais separado, sem tokens no JSON ou logs. Revisões remotas e conflitos exigiriam um contrato novo explícito.

## Validação

`HttpSyncTest` usa um servidor temporário em porta dinâmica: serialização, 200/400/500, indisponibilidade, timeout, retries/backoff, persistência/reinício, resposta antiga, URL, segurança, limite de tamanho e modo manual/automático. O Client GameTest também usa um servidor HTTP temporário, testa fecho com resposta tardia e verifica receção automática dos itens reais do mundo temporário, além dos comandos novos e existentes. Não usa a porta 8080 nem mundos reais.
