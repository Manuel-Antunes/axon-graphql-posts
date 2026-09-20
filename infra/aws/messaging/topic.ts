/// <reference path="../../../.sst/platform/config.d.ts" />

export const postEvents = new sst.aws.SnsTopic('PostEvents', { fifo: true });
