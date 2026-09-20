-- COGNITO entra no enum AuthProvider, e por isso entra também no CHECK.
--
-- Esta migration é a contrapartida exata do que a V1 prometeu: "o CHECK do provider vem do enum
-- AuthProvider e é deliberado: acrescentar um provedor passa a exigir uma migration". Ela é a primeira
-- vez que essa promessa é cobrada.
--
-- POR QUE UM PROVEDOR NOVO
-- A aplicação passou a rodar na AWS atrás de um user pool do Cognito, e não mais de um Keycloak. Sem
-- esta linha, uma identidade emitida pelo Cognito seria gravada como KEYCLOAK — e a consequência não é
-- cosmética: `uk_accounts_provider_subject` é (provider, subject), então a MESMA pessoa chegando pelos
-- dois emissores produziria duas contas com o provedor KEYCLOAK e subjects diferentes, e
-- `Authenticatable.link` recusaria a segunda com `já tem conta em KEYCLOAK`. Foi exatamente o que
-- aconteceu na primeira execução contra o Cognito.
--
-- Com COGNITO no enum, o mesmo caso vira o que o desenho sempre quis que fosse: account linking. A
-- pessoa mantém um `users`, ganha uma segunda linha em `accounts`, e continua sendo uma identidade só.
--
-- Em dev e teste nada muda: lá o emissor continua sendo o Keycloak, e nenhuma linha COGNITO é escrita.

alter table accounts drop constraint ck_accounts_provider;

alter table accounts add constraint ck_accounts_provider
    check (provider in ('CREDENTIAL', 'KEYCLOAK', 'COGNITO', 'GOOGLE', 'GITHUB'));
