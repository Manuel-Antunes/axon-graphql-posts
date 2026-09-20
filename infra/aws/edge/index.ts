/// <reference path="../../../.sst/platform/config.d.ts" />

import { streaming } from '../compute';

export const router = new sst.aws.Router('Edge');

router.route(
  '/graphql',
  streaming.functionUrl.apply((url) => url.replace(/\/$/, '')),
  {
    readTimeout: '60 seconds',
  },
);
