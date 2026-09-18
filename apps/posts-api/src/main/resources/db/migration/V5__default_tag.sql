-- A tag padrão como DADO DE REFERÊNCIA.
--
-- POR QUE ELA VEM DO SCHEMA, E NÃO DE UM COMMAND
-- ==============================================
-- Porque ela não é criada por ninguém: a identidade dela é DERIVADA do nome (`Tag.DEFAULT_ID`, um UUID
-- versão 3 de "tag:Untagged"), o que a torna a mesma em todo nó, toda reinicialização e todo serviço.
-- Algo que já tem identidade fixa antes de qualquer decisão é semente, não evento.
--
-- Criá-la sob demanda era um "verifica-então-cria" distribuído, e tinha a corrida que esse padrão sempre
-- tem: duas conclusões de post em paralelo passam pela verificação antes de qualquer uma commitar e as
-- duas despacham o mesmo `CreateTag`. A segunda encontrava o agregado e recusava; engolir a recusa
-- levava ao sintoma seguinte, porque a LINHA da primeira ainda não estava visível:
--   Tag já existe: fa65e148-3f7c-3860-a765-a70f54983048
--   Tag não encontrada: fa65e148-3f7c-3860-a765-a70f54983048
-- Semeando, não há o que verificar nem o que criar.
--
-- CONSEQUÊNCIA: esta tag tem LINHA e não tem STREAM. Está certo — ela não nasceu de uma decisão, e o
-- agregado Tag existe para as tags que nascem. Quem a lê é o read model, que é exatamente onde ela é
-- usada (o `post_tags` referencia esta linha).
--
-- O UUID abaixo é literal porque SQL não deriva UUID versão 3. A duplicação é real e está travada por
-- teste: `TagTest.theDefaultTagIdIsDerivedFromTheName` falha se o nome mudar e este literal não.
insert into tags (id, name, created_at)
values ('fa65e148-3f7c-3860-a765-a70f54983048', 'Untagged', '2026-01-01T00:00:00Z')
on conflict do nothing;
