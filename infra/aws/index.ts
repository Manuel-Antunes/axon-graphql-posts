/// <reference path="../../.sst/platform/config.d.ts" />

import { gateway, postsMigrate, streaming, taggingMigrate } from './compute';
import { postsDb, taggingDb } from './data';
import { router } from './edge';
import { client, issuer, users } from './identity';
import { changes, completed, postEvents, precreated } from './messaging';
import { web } from './web';

export const outputs = {
  web: web.url,

  subgraph: $interpolate`${router.url}/graphql`,

  api: gateway.url,

  stream: streaming.functionUrl,

  issuer,
  userPool: users.id,
  clientId: client.id,

  migrate: {
    posts: postsMigrate.functionName,
    tagging: taggingMigrate.functionName,
  },

  topic: postEvents.arn,
  queues: {
    precreated: precreated.url,
    changes: changes.url,
    completed: completed.url,
  },
  databases: {
    posts: postsDb.host,
    tagging: taggingDb.host,
  },
};
