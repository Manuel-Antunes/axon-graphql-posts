/// <reference path="../../../.sst/platform/config.d.ts" />

import { changes, completed, postEvents, precreated } from '../messaging';

export class ExecutionRole extends $util.ComponentResource {
  readonly role: aws.iam.Role;

  constructor(name: string, opts?: $util.ComponentResourceOptions) {
    super('axonposts:aws:ExecutionRole', name, {}, opts);
    const parent = { parent: this };

    this.role = new aws.iam.Role(
      name,
      {
        assumeRolePolicy: JSON.stringify({
          Version: '2012-10-17',
          Statement: [
            {
              Effect: 'Allow',
              Principal: { Service: 'lambda.amazonaws.com' },
              Action: 'sts:AssumeRole',
            },
          ],
        }),
        managedPolicyArns: [
          aws.iam.ManagedPolicy.AWSLambdaBasicExecutionRole,
          aws.iam.ManagedPolicy.AWSLambdaVPCAccessExecutionRole,
        ],
      },
      parent,
    );

    new aws.iam.RolePolicy(
      `${name}Messaging`,
      {
        role: this.role.id,
        policy: $util
          .all([postEvents.arn, precreated.arn, changes.arn, completed.arn])
          .apply(([topic, ...queues]) =>
            JSON.stringify({
              Version: '2012-10-17',
              Statement: [
                { Effect: 'Allow', Action: ['sns:Publish'], Resource: topic },
                {
                  Effect: 'Allow',
                  Action: [
                    'sqs:ReceiveMessage',
                    'sqs:DeleteMessage',
                    'sqs:GetQueueAttributes',
                    'sqs:ChangeMessageVisibility',
                  ],
                  Resource: queues,
                },
              ],
            }),
          ),
      },
      parent,
    );

    this.registerOutputs({ arn: this.role.arn });
  }

  get arn(): $util.Output<string> {
    return this.role.arn;
  }
}
