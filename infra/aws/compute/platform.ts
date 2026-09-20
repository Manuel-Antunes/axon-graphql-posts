/// <reference path="../../../.sst/platform/config.d.ts" />

import type { LambdaPlatform } from '../support';
import { vpc } from '../network';
import { ExecutionRole } from './role';

const role = new ExecutionRole('LambdaRole');

export const platform: LambdaPlatform = {
  role: role.arn,
  subnetIds: vpc.privateSubnets as unknown as $util.Input<string[]>, // O SST não expõe o tipo certo, mas é o que ele devolve.
  securityGroupIds: vpc.securityGroups as unknown as $util.Input<string[]>, // O SST não expõe o tipo certo, mas é o que ele devolve.
};

const bucket = new sst.aws.Bucket('CodeBucket');

export const codeBucket = bucket.name;

export const projects = {
  postsApi: 'dev.manuelantunes:quarkus-axon-graphql-posts',
  tagging: 'dev.manuelantunes:axonposts-tagging',
};

const COMUM = [
  'pom.xml',
  'mvnw',
  '.mvn',
  'libs',
  'infra/lambda/collector.yaml',
];
export const sources = {
  postsApi: [...COMUM, 'apps/posts-api/pom.xml', 'apps/posts-api/src'],
  tagging: [...COMUM, 'apps/tagging/pom.xml', 'apps/tagging/src'],
};
