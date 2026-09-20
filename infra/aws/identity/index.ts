/// <reference path="../../../.sst/platform/config.d.ts" />

import { seed } from './seed';

export const users = new sst.aws.CognitoUserPool('Users', {
  usernames: ['email'],

  triggers: {
    preTokenGeneration: 'infra/aws/identity/identity-provider.handler',
    preTokenGenerationVersion: 'v1',
  },
  transform: {
    userPool: {
      autoVerifiedAttributes: ['email'],
      passwordPolicy: {
        minimumLength: 8,
        requireLowercase: false,
        requireNumbers: false,
        requireSymbols: false,
        requireUppercase: false,
      },
      schemas: [
        {
          name: 'email',
          attributeDataType: 'String',
          required: true,
          mutable: true,
        },
        {
          name: 'name',
          attributeDataType: 'String',
          required: false,
          mutable: true,
        },
      ],
    },
  },
});

export const client = users.addClient('Api', {
  transform: {
    client: {
      explicitAuthFlows: [
        'ALLOW_USER_PASSWORD_AUTH',
        'ALLOW_REFRESH_TOKEN_AUTH',
      ],
      generateSecret: false,
    },
  },
});

export const pool = { userPoolId: users.id };

export const authorGroup = new aws.cognito.UserGroup('AuthorGroup', {
  ...pool,
  name: 'author',
  description: 'Pode escrever posts',
});

const userGroup = new aws.cognito.UserGroup('UserGroup', {
  ...pool,
  name: 'user',
  description: 'Leitor autenticado',
});

seed('Manuel', 'manuel@example.com', 'Manuel Antunes', ['user', 'author']);
seed('Leitor', 'leitor@example.com', 'Leitor Anonimo', ['user']);
seed('Promovido', 'promovido@example.com', 'Autor Recente', ['user', 'author']);

export const issuer = $interpolate`https://cognito-idp.${aws.getRegionOutput().name}.amazonaws.com/${users.id}`;
