/// <reference path="../../../.sst/platform/config.d.ts" />

import { createHash } from 'crypto';
import { existsSync, readdirSync, readFileSync, statSync } from 'fs';
import { join } from 'path';

export class Artifact {
  constructor(
    readonly bucket: $util.Output<string>,
    readonly key: $util.Output<string>,
    readonly hash: string,
  ) {}
}

export interface LambdaPlatform {
  readonly role: $util.Input<string>;
  readonly subnetIds: $util.Input<string[]>;
  readonly securityGroupIds: $util.Input<string[]>;
}

export type QuarkusCode = QuarkusBuild | Artifact;

export interface QuarkusBuild {
  readonly artifact: string;
  readonly buildCommand: string;
  readonly output: string;
  readonly bucket: $util.Input<string>;
  readonly sources: string[];
}

let lastBuild: $util.Resource | undefined;

export interface QuarkusFunctionArgs {
  readonly platform: LambdaPlatform;
  readonly code: QuarkusCode;
  readonly environment: Record<string, $util.Input<string>>;
  readonly timeout: number;
  readonly memory?: number;
  readonly handler?: string;
  readonly layers?: $util.Input<string>[];
  readonly runtime?: string;
}

export const OTEL_COLLECTOR_ARM64 =
  'arn:aws:lambda:us-east-1:184161586896:layer:opentelemetry-collector-arm64-0_23_0:1';

const HANDLER =
  'io.quarkus.amazon.lambda.runtime.QuarkusStreamHandler::handleRequest';

function requiredEnv(name: string): string {
  const value = process.env[name];
  if (!value) {
    throw new Error(
      `${name} não está definida. Ela vem do \`.env\` da raiz (ver \`.env.example\`), que o ` +
        'SST carrega sozinho. É o destino da telemetria de TODA função.',
    );
  }
  return value;
}

export class QuarkusFunction extends $util.ComponentResource {
  readonly fn: aws.lambda.Function;
  readonly code: Artifact;

  private IGNORED = new Set([
    'target',
    'node_modules',
    '.git',
    '.sst',
    'dist',
    '.DS_Store',
  ]);

  private walk(path: string): string[] {
    let info;
    try {
      info = statSync(path);
    } catch {
      return [];
    }
    if (!info.isDirectory()) return [path];

    const found: string[] = [];
    for (const entry of readdirSync(path, { withFileTypes: true }).sort(
      (a, b) => a.name.localeCompare(b.name),
    )) {
      if (this.IGNORED.has(entry.name)) continue;
      found.push(...this.walk(join(path, entry.name)));
    }
    return found;
  }

  private fingerprintOf(paths: string[]): { base64: string; short: string } {
    const digest = createHash('sha256');
    for (const path of [...paths].sort()) {
      for (const file of this.walk(path)) {
        digest.update(file);
        digest.update(
          readFileSync(file) as unknown as Uint8Array<ArrayBufferLike>,
        );
      }
    }
    return {
      base64: digest.copy().digest('base64'),
      short: digest.digest('hex').slice(0, 16),
    };
  }
  private isBuild(code: QuarkusCode): code is QuarkusBuild {
    return (code as QuarkusBuild).artifact !== undefined;
  }

  private artifactPresence(output: string): string {
    return existsSync(join(process.cwd(), output))
      ? 'artifact-present'
      : `artifact-missing-${Date.now()}`;
  }

  constructor(
    name: string,
    args: QuarkusFunctionArgs,
    opts?: $util.ComponentResourceOptions,
  ) {
    super('axonposts:aws:QuarkusFunction', name, {}, opts);

    this.code = this.isBuild(args.code) ? this.buildCode(args.code) : args.code;

    this.fn = new aws.lambda.Function(
      name,
      {
        role: args.platform.role,
        runtime: args.runtime ?? 'provided.al2023',
        architectures: ['arm64'],
        handler: args.handler ?? HANDLER,
        layers: [...(args.layers ?? []), OTEL_COLLECTOR_ARM64],
        s3Bucket: this.code.bucket,
        s3Key: this.code.key,
        sourceCodeHash: this.code.hash,
        memorySize: args.memory ?? 2048,
        timeout: args.timeout,
        vpcConfig: {
          subnetIds: args.platform.subnetIds,
          securityGroupIds: args.platform.securityGroupIds,
        },
        environment: {
          variables: {
            ...args.environment,
            OPENTELEMETRY_COLLECTOR_CONFIG_URI: '/var/task/collector.yaml',
            BETTER_STACK_URL: requiredEnv('BETTER_STACK_URL'),
            BETTER_STACK_API_KEY: requiredEnv('BETTER_STACK_API_KEY'),
          },
        },
      },
      { parent: this },
    );

    this.registerOutputs({ arn: this.fn.arn });
  }

  private buildCode(spec: QuarkusBuild): Artifact {
    const hash = this.fingerprintOf(spec.sources);

    const built = new command.local.Command(
      `${spec.artifact}Build`,
      {
        create: spec.buildCommand,
        update: spec.buildCommand,
        dir: process.cwd(),
        triggers: [hash.short, this.artifactPresence(spec.output)],
        assetPaths: [spec.output],
      },
      { parent: this, dependsOn: lastBuild ? [lastBuild] : [] },
    );
    lastBuild = built;

    const object = new aws.s3.BucketObjectv2(
      `${spec.artifact}Code`,
      {
        bucket: spec.bucket,
        key: `${spec.artifact}-${hash.short}.zip`,
        source: built.assets.apply(
          (assets) => assets![spec.output] as $util.asset.Asset,
        ),
      },
      { parent: this, dependsOn: [built] },
    );

    return new Artifact($util.output(spec.bucket), object.key, hash.base64);
  }

  get arn(): $util.Output<string> {
    return this.fn.arn;
  }

  get functionName(): $util.Output<string> {
    return this.fn.name;
  }
}

export interface QueueWorkerArgs extends QuarkusFunctionArgs {
  readonly queue: sst.aws.Queue;
  readonly channel: string;
}

export class QueueWorker extends QuarkusFunction {
  readonly mapping: aws.lambda.EventSourceMapping;

  constructor(
    name: string,
    args: QueueWorkerArgs,
    opts?: $util.ComponentResourceOptions,
  ) {
    super(
      name,
      {
        ...args,
        environment: {
          ...args.environment,
          AXONPOSTS_LAMBDA_SQS_CHANNEL: args.channel,
        },
      },
      opts,
    );

    this.mapping = new aws.lambda.EventSourceMapping(
      `${name}Mapping`,
      {
        eventSourceArn: args.queue.arn,
        functionName: this.fn.arn,
        batchSize: 10,
        functionResponseTypes: ['ReportBatchItemFailures'],
      },
      { parent: this },
    );
  }
}

export interface MigratorArgs extends Omit<
  QuarkusFunctionArgs,
  'timeout' | 'memory'
> {}

export class Migrator extends QuarkusFunction {
  constructor(
    name: string,
    args: MigratorArgs,
    opts?: $util.ComponentResourceOptions,
  ) {
    super(
      name,
      {
        ...args,
        environment: {
          ...args.environment,
          QUARKUS_LAMBDA_HANDLER: 'flyway-migrate',
          QUARKUS_HIBERNATE_ORM_SCHEMA_MANAGEMENT_STRATEGY: 'none',
        },
        timeout: 300,
        memory: 1024,
      },
      opts,
    );

    if (!$dev) {
      new aws.lambda.Invocation(
        `${name}Invocation`,
        {
          input: Date.now().toString(),
          functionName: this.fn.name,
        },
        { parent: this },
      );
    }
  }
}

const WEB_ADAPTER_ARM64 =
  'arn:aws:lambda:us-east-1:753240598075:layer:LambdaAdapterLayerArm64:30';

export interface StreamingFunctionArgs extends Omit<
  QuarkusFunctionArgs,
  'handler' | 'layers'
> {}

export class StreamingFunction extends QuarkusFunction {
  readonly url: aws.lambda.FunctionUrl;

  constructor(
    name: string,
    args: StreamingFunctionArgs,
    opts?: $util.ComponentResourceOptions,
  ) {
    super(
      name,
      {
        ...args,
        handler: 'run.sh',
        layers: [WEB_ADAPTER_ARM64],
        runtime: 'java21',
        environment: {
          ...args.environment,
          AWS_LAMBDA_EXEC_WRAPPER: '/opt/bootstrap',
          AWS_LWA_INVOKE_MODE: 'response_stream',
          AWS_LWA_PORT: '8080',
          AWS_LWA_READINESS_CHECK_PATH: '/q/health/ready',
        },
      },
      opts,
    );

    this.url = new aws.lambda.FunctionUrl(
      `${name}Url`,
      {
        functionName: this.fn.name,
        authorizationType: 'NONE',
        invokeMode: 'RESPONSE_STREAM',
      },
      { parent: this },
    );
  }

  get functionUrl(): $util.Output<string> {
    return this.url.functionUrl;
  }
}
