alter table accounts drop constraint ck_accounts_provider;

alter table accounts add constraint ck_accounts_provider
    check (provider in ('CREDENTIAL', 'KEYCLOAK', 'COGNITO', 'GOOGLE', 'GITHUB'));
