/// <reference path="../../../.sst/platform/config.d.ts" />

import { postsDb, taggingDb } from '../data';
import { client, issuer } from '../identity';
import { postEvents } from '../messaging';

const shared = {
  QUARKUS_PROFILE: 'lambda,prod',
  AXONPOSTS_EVENTS_TOPIC_ARN: postEvents.arn,
};

export const postsEnvironment = {
  ...shared,
  QUARKUS_DATASOURCE_JDBC_URL: $interpolate`jdbc:postgresql://${postsDb.host}:${postsDb.port}/${postsDb.database}`,
  QUARKUS_DATASOURCE_USERNAME: postsDb.username,
  QUARKUS_DATASOURCE_PASSWORD: postsDb.password,
  OIDC_ISSUER_URL: issuer,
  OIDC_CLIENT_ID: client.id,

  QUARKUS_OIDC_AUTH_SERVER_URL: issuer,
  QUARKUS_OIDC_CLIENT_ID: client.id,
};

export const taggingEnvironment = {
  ...shared,
  QUARKUS_DATASOURCE_JDBC_URL: $interpolate`jdbc:postgresql://${taggingDb.host}:${taggingDb.port}/${taggingDb.database}`,
  QUARKUS_DATASOURCE_USERNAME: taggingDb.username,
  QUARKUS_DATASOURCE_PASSWORD: taggingDb.password,
};
