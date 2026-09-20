/// <reference path="../../../.sst/platform/config.d.ts" />

function queue(name: string) {
  const dlq = new sst.aws.Queue(`${name}Dlq`, { fifo: true });
  return new sst.aws.Queue(name, {
    fifo: true,
    visibilityTimeout: '180 seconds',
    dlq: { queue: dlq.arn, retry: 5 },
  });
}

export const precreated = queue('TaggingPrecreated');

export const changes = queue('TaggingChanges');

export const completed = queue('PostsApiCompleted');
