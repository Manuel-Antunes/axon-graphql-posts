CREATE ROLE keycloak WITH LOGIN PASSWORD 'keycloak';
CREATE DATABASE keycloak OWNER keycloak;

\connect keycloak
GRANT ALL ON SCHEMA public TO keycloak;
