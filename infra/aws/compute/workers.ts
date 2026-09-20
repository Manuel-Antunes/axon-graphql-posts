/// <reference path="../../../.sst/platform/config.d.ts" />

import { changes, completed, precreated } from '../messaging';
import { QueueWorker } from '../support';
import { postsEnvironment, taggingEnvironment } from './environment';
import { codeBucket, platform, projects, sources } from './platform';

const timeout = 120;

export const postsInbox = new QueueWorker('PostsApiInbox', {
  platform,
  code: {
    artifact: 'posts-api-sqs',
    buildCommand: `npx -y nx run "${projects.postsApi}:lambda-sqs:native-container"`,
    output: 'infra/dist/posts-api-sqs.zip',
    bucket: codeBucket,
    sources: sources.postsApi,
  },
  environment: postsEnvironment,
  timeout,
  queue: completed,
  channel: 'post-completed-in',
});

export const taggingDecide = new QueueWorker('TaggingDecide', {
  platform,
  code: {
    artifact: 'tagging',
    buildCommand: `npx -y nx run "${projects.tagging}:lambda:native-container"`,
    output: 'infra/dist/tagging.zip',
    bucket: codeBucket,
    sources: sources.tagging,
  },
  environment: taggingEnvironment,
  timeout,
  queue: precreated,
  channel: 'post-precreated-in',
});

export const taggingReplicate = new QueueWorker('TaggingReplicate', {
  platform,
  code: taggingDecide.code,
  environment: taggingEnvironment,
  timeout,
  queue: changes,
  channel: 'post-changes-in',
});
