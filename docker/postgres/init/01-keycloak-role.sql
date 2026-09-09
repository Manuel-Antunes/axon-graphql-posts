-- Banco e usuário do Keycloak, separados dos da aplicação.
--
-- Este arquivo roda uma vez, na primeira subida do container (docker-entrypoint-initdb.d), já conectado
-- como o superusuário; o banco e o papel `axonposts` vêm das variáveis POSTGRES_* do compose.
--
-- Por que dois usuários e dois bancos no mesmo servidor:
--
--   * isolamento de sessão e de conexões — o Keycloak mantém o próprio pool aberto o tempo todo e roda
--     migração de schema na subida. Dividindo credencial com a aplicação, os dois disputariam o mesmo
--     limite de conexões e um `ddl-auto: update` da aplicação enxergaria as tabelas do Keycloak;
--   * privilégio mínimo — o usuário da aplicação não alcança as tabelas de credencial do Keycloak, onde
--     ficam hashes de senha e sessões. Um SQL injection na aplicação não chega até lá;
--   * ciclo de vida — dá para restaurar o banco da aplicação sem levar junto o realm, e vice-versa.
--
-- Um servidor só, dois inquilinos: é o desenho certo para desenvolvimento.

CREATE ROLE keycloak WITH LOGIN PASSWORD 'keycloak';
CREATE DATABASE keycloak OWNER keycloak;

-- Sem isto o Keycloak sobe mas falha ao criar o schema dele no Postgres 15+, onde o papel `public`
-- deixou de poder criar objetos por padrão.
\connect keycloak
GRANT ALL ON SCHEMA public TO keycloak;
