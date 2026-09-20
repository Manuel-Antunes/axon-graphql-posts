/// <reference path="../../../.sst/platform/config.d.ts" />

import { HttpApi, QuarkusFunction, StreamingFunction } from '../support';
import { postsEnvironment } from './environment';
import { codeBucket, platform, projects, sources } from './platform';

export const api = new QuarkusFunction('PostsApi', {
  platform,
  code: {
    artifact: 'posts-api-http',
    buildCommand: `npx -y nx run "${projects.postsApi}:lambda-http:native-container"`,
    output: 'infra/dist/posts-api-http.zip',
    bucket: codeBucket,
    sources: sources.postsApi,
  },
  environment: postsEnvironment,
  timeout: 30,
});

export const gateway = new HttpApi('PostsApiGateway', { handler: api });

export const streaming = new StreamingFunction('PostsApiStream', {
  platform,
  code: {
    artifact: 'posts-api-stream',
    buildCommand: `npx -y nx run "${projects.postsApi}:lambda-stream:native-container"`,
    output: 'infra/dist/posts-api-stream.zip',
    bucket: codeBucket,
    sources: sources.postsApi,
  },
  environment: postsEnvironment,
  timeout: 300,
});
