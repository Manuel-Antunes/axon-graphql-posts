/// <reference path="../../../.sst/platform/config.d.ts" />

import { changes, completed, precreated } from './queues';
import { postEvents } from './topic';

const raw = { subscription: { rawMessageDelivery: true } };

postEvents.subscribeQueue('Precreated', precreated, {
  filter: { 'axon-message-name': ['PostPreCreated'] },
  transform: raw,
});

postEvents.subscribeQueue('Changes', changes, {
  filter: {
    'axon-message-name': ['PostUpdated', 'PostDeleted', 'PostRestored'],
  },
  transform: raw,
});

postEvents.subscribeQueue('Completed', completed, {
  filter: { 'axon-message-name': ['PostCreated'] },
  transform: raw,
});
