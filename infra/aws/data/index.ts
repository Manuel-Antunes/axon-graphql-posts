/// <reference path="../../../.sst/platform/config.d.ts" />

import { vpc } from '../network';

export const postsDb = new sst.aws.Postgres('PostsDb', {
  vpc,
  instance: 't4g.micro',
  storage: '20 GB',
});

export const taggingDb = new sst.aws.Postgres('TaggingDb', {
  vpc,
  instance: 't4g.micro',
  storage: '20 GB',
});
