# Testes de integração a executar dentro do Minecraft

Este roteiro não representa resultados executados. Utilizar uma cópia descartável de um mundo.

1. Abrir um baú não registado: `/chestcount count` deve continuar a mostrar zero.
2. Shift + botão direito com bússola: um registo sem itens, sem abertura forçada. Repetir: sem duplicação.
3. Abrir normalmente um baú com diamantes e ferro: confirmar slots/quantidades, data e exclusão dos itens do jogador.
4. Transferir itens nas duas direções, dividir stacks, Shift+Click, arrastar e fechar imediatamente; verificar primeiro o JSON sem executar comandos. Só depois consultar `where` e reabrir para confirmar.
5. Esvaziar o baú: snapshot vazio com data, sem restos do snapshot antigo.
6. Registar duplo pelas duas metades: um registo, 54 slots, um contorno. Renomear no mod e testar autocomplete, rejeição de duplicados e nomes com espaços entre aspas.
7. Juntar dois simples registados, separar um duplo, destruir uma metade: preservar snapshots, assinalar alteração e recusar sincronização até remover/registar explicitamente.
8. Sobrepor coordenadas em dois mundos, Nether, End e dimensão personalizada: não misturar registos nem inventários.
9. Desconectar durante abertura, mudar de dimensão e abrir outro contentor: nunca usar a associação anterior.
10. Servidor vanilla sem Fabric: testar registo e sincronização. Servidor com plugins: sem autorização deve recusar; testar menus de 27/54 slots e títulos personalizados.
11. Segurar bússola em cada mão, andar até 32 blocos, colocar uma parede opaca entre jogador e baú, descarregar chunk: não mostrar contorno através da parede nem de chunks descarregados. Repetir com `alwaysShowOutlines`.
12. Remover sem confirmação/confirmar após 30 segundos: não apagar. Confirmar dentro do prazo: apagar apenas registo local.
13. Reiniciar cliente: recuperar dados, nomes e datas. Corromper JSON numa instância de teste: recuperar `.bak` e preservar original. Sem `.bak`: recusar escrita e permitir reparar/recarregar.
14. Testar nomes personalizados por bigorna, baús bloqueados e baús com gatos/blocos por cima. Não inferir inventário sem interface válida.
15. Fazer várias ações rápidas e fechar no mesmo tick; aguardar a resposta em trânsito e verificar o JSON sem comandos. Simular latência superior a 100 ticks/desconexão: preservar última confirmação e avisar de sincronização incompleta.

16. Testar /cc e /chestcount: count, list, info, location, nearby, where, rename e remove. Após renomear, o nome antigo falha e o novo funciona sem reload. Testar PT-PT e inglês.
17. Criar mais de 10 registos e percorrer páginas de list, where e nearby com o comando indicado no chat. Verificar coordenadas coincidentes em dimensões diferentes com info.
18. Numa cópia de JSON antigo, manter dois nomes iguais: a pesquisa deve recusar a ambiguidade e o autocomplete deve oferecer prefixos resolúveis. Resolver por UUID, sem perda de posições ou itens.
