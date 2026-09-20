/// <reference path="../../../.sst/platform/config.d.ts" />

import { Migrator } from '../support';
import { postsEnvironment, taggingEnvironment } from './environment';
import { platform } from './platform';
import { postsInbox, taggingDecide } from './workers';

const MIGRATE_WITHOUT_STREAMING_PROCESSORS = [
  'dev.manuelantunes.axonposts.application.post.projection',
  'dev.manuelantunes.axonposts.application.post.event',
].join(',');

export const postsMigrate = new Migrator('PostsMigrate', {
  platform,
  code: postsInbox.code,
  environment: {
    ...postsEnvironment,
    QUARKUS_AXON_SUBSCRIBINGPROCESSOR_NAMESPACES:
      MIGRATE_WITHOUT_STREAMING_PROCESSORS,
  },
});

export const taggingMigrate = new Migrator('TaggingMigrate', {
  platform,
  code: taggingDecide.code,
  environment: {
    ...taggingEnvironment,
    AXONPOSTS_LAMBDA_SQS_CHANNEL: 'post-precreated-in',
  },
});
