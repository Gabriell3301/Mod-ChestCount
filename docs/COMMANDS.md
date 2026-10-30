# Comandos

Todos são locais. `/cc` e `/chestcount` partilham a árvore Brigadier e os handlers.

```text
/cc count
/cc list [página]
/cc info Minerios
/cc location Minerios
/cc rename Bau_1 Minerios
/cc rename Minerios "Baú de Minérios"
/cc info "Baú de Minérios"
/cc rename "Baú de Minérios" Recursos
/cc remove Recursos
/cc remove Recursos confirm
/cc nearby [raio] [página]
/cc where diamond [página]
/cc reload
/cc help
```

`list`, `nearby` e `where` apresentam até 10 resultados e indicam o comando para a página seguinte. Em `nearby`, especifica o raio antes da página. Não há UUIDs completos nem datas ISO nas respostas normais; `info` apresenta tempo relativo, tipo e quantidade total de itens.

O identificador pode ser o nome exato, um UUID completo ou um prefixo único. O autocomplete prefere nomes e insere aspas quando necessário. Para nomes antigos duplicados sugere prefixos que resolvem sem ambiguidade; usa-os para renomear cada registo. A pesquisa por nome ignora maiúsculas e espaços exteriores e normaliza Unicode NFC, preservando a capitalização apresentada. Não usa correspondência parcial de nomes.

Novos nomes são únicos no mundo lógico, incluindo todas as dimensões. Os nomes gerados `Bau_1`, `Bau_2`, etc. escolhem o primeiro número livre entre os registos existentes; não dependem de um contador transitório. Um nome removido ou alterado pode voltar a ficar disponível. UUIDs são sempre preservados.

Nomes que colidam com o UUID/prefixo de outro registo são recusados. Nos dados antigos, um nome exato tem prioridade sobre prefixos. Um conflito entre nome antigo e UUID completo devolve ambiguidade; a forma explícita `"uuid:<UUID completo>"` permite resolver esse caso excecional. O prefixo `uuid:` está reservado e é sugerido apenas quando necessário. Duplicados nunca são eliminados ou renomeados automaticamente.

`info` e `location` conservam nomes com espaços sem aspas. Em `rename` e `remove`, usa aspas no identificador que contém espaços; o novo nome em `rename` continua a aceitar espaços sem aspas. A confirmação de remoção inclui um identificador seguro, já entre aspas, e expira em 30 segundos.

As quantidades refletem a última confirmação conhecida, não inventários fechados em tempo real. `info` avisa quando ainda não existe snapshot ou a estrutura mudou. UUIDs, dimensões e datas completas continuam disponíveis no JSON para diagnóstico.

## HTTP

`/cc sync`, `/cc sync status`, `/cc sync on`, `/cc sync off`: ver [HTTP-SYNC.md](HTTP-SYNC.md). Os comandos existentes continuam disponíveis.

## Backups, exportação e importação

`/cc backup`, `/cc backup list`, `/cc export`, `/cc import ficheiro.json` e `/cc import ficheiro.json confirm`. A importação lê apenas `config/chestcount/imports/`, valida o mundo e faz backup antes de aplicar um merge por UUID. A confirmação exige pré-visualização atual. Ver [guia completo](INCREMENTAL-SYNC.md).
