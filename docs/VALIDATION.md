# Validação executada — 28/10/2026

Diretório: `C:\Users\2025168383\Desktop\Mod-ChestCount`. A pasta estava vazia na inspeção inicial. As ferramentas, caches Gradle e mundos temporários usados nesta validação ficam dentro do projeto. Não foi utilizado outro projeto.

## Ambiente e resultado final

- Windows, Oracle JDK 26.0.1; compilação com `--release 25` (class file major 69 verificado no JAR).
- Minecraft 26.3, Fabric Loader 0.20.1, Fabric API 0.165.0+26.3.
- Gradle Wrapper 9.6.0, Loom 1.17.21; checksum oficial da distribuição Gradle incluído no wrapper.
- Comando final executado: `./gradlew.bat --gradle-user-home .gradle-home build runClientGameTest`.
- **BUILD SUCCESSFUL**, código de saída zero.
- **29 testes JUnit passaram**, sem falhas, erros ou testes ignorados: 17 de registos/pesquisa/associação, 7 de armazenamento e 5 de normalização de endereços, incluindo IPv6.
- **1 teste de integração Fabric Client GameTest passou**, com cliente gráfico e servidor integrado reais.
- JAR produzido: `build/libs/chest-count-1.0.0.jar`. SHA-256 ao lado do JAR em `.jar.sha256`.
- Verificados no JAR: ambiente `client`, versão `1.0.0`, licença e ausência das classes do mod de testes.
- Verificados os 21 identificadores de tradução iguais entre `en_us` e `pt_pt`, com strings PT-PT em UTF-8.

Os [logs Gradle](validation/gradle.log), [logs do cliente de teste](validation/client-gametest.log) e XMLs JUnit estão preservados em `docs/validation/`. O relatório HTML interativo dos testes unitários fica em `build/reports/tests/test/index.html`.

## O que o teste dentro do jogo exerceu

O teste cria um mundo plano temporário, coloca baús e itens no servidor integrado, e executa as seguintes verificações no cliente:

1. Baú não registado continua sem registo.
2. Shift + interação real por `MultiPlayerGameMode.useItemOn`, com bússola na mão principal: regista e devolve `FAIL`, a resposta Fabric que cancela o envio da interação de bloco.
3. Repetir o registo, incluindo bússola na mão secundária, não duplica o registo.
4. Interação normal abre a interface real, executa os mixins e recebe o conteúdo completo: 32 diamantes + 64 lingotes = 96 itens.
5. Os 11 diamantes colocados no inventário do jogador não entram no snapshot.
6. Uma alteração do servidor enquanto a interface está aberta (diamantes de 32 para 7) chega por atualização de slot: total passa a 71.
7. Converter para baú duplo assinala a alteração estrutural do registo antigo, sem perder os dados.
8. As duas metades físicas produzem a mesma identidade de 54 slots.
9. Após gestão explícita do registo antigo, abrir pela outra metade sincroniza 76 itens das duas metades, continuando a excluir o inventário do jogador.
10. Executar `count` e `where diamond` no dispatcher Fabric client-side funciona; a pesquisa encontra 12 diamantes do baú, não os 11 do jogador.
11. Renderização de um único contorno azul no baú duplo e captura da mesma cena com parede opaca.
12. Desconexão e encerramento normal do cliente de teste, com escrita local.

Capturas realmente produzidas e inspecionadas:

- [Baú duplo com contorno azul](validation/0000_chest-count-double.png).
- [Parede opaca oculta o baú e o contorno](validation/0001_chest-count-occluded.png).

A inspeção destas capturas é visual; não é um teste automatizado por comparação de pixels. Não constitui uma garantia para todos os drivers, shaders ou renderizadores alternativos.

## Erros encontrados e corrigidos

Os primeiros builds falharam por nomes de APIs alterados em 26.3 (`ClientCommands`, chat em `Gui.hud`, nomes de itens por `ItemStack`). Foram corrigidos consultando as classes/dependências oficiais resolvidas pelo Gradle.

As primeiras execuções de integração falharam ao aguardar o snapshot. O teste revelou um cálculo incorreto dos slots a partir das linhas do menu; foi corrigido para 9 slots por linha. A preparação do mundo de teste também passou a colocar uma plataforma. As execuções finais passaram com interação, interface e pacotes reais; não foram substituídas por mocks.

Permanecem avisos de APIs obsoletas e avisos Gradle relativos a compatibilidade futura com Gradle 10. O cliente de desenvolvimento emite avisos de WMI indisponível, opções gráficas do test runner e autenticação Realms sem conta real; não impediram os testes no mundo local. Não foi afirmada autenticação online bem-sucedida.

## Ainda não validado

- Ligação a um servidor multiplayer externo vanilla sem Fabric.
- Servidores Paper/Spigot/proxies e plugins que substituem menus, incluindo títulos deliberadamente iguais aos vanilla.
- Teste interativo completo de todos os comandos/autocompletes, nomes por bigorna e modos de transferência previstos localmente.
- Mudanças reais de dimensão, dimensões personalizadas e trocas internas de mundos de plugins. O isolamento lógico é coberto nos testes unitários.
- Destruição/substituição durante descarregamento de chunks, crashes do sistema ou falhas físicas de disco.
- Execução numa JVM 25 (o bytecode foi compilado para 25, mas a JVM usada foi 26).
- Outros drivers, shaders e mods de renderização.

O [roteiro manual](MANUAL-TESTS.md) mantém os cenários a completar. As limitações da associação de menus, snapshots baseados em pacotes, identidade por pasta/endereço e autorização de servidores estão detalhadas no README.
