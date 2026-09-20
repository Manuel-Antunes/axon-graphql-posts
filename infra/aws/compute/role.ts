/// <reference path="../../../.sst/platform/config.d.ts" />

import { postEvents, precreated, changes, completed } from "../messaging";

/**
 * O papel de execução — <b>um só para as seis funções</b>.
 *
 * Elas fazem o mesmo conjunto de coisas: escrevem log, criam ENI na VPC, publicam no topic e são lidas
 * do SQS pelo serviço do Lambda. Seis papéis idênticos seriam seis lugares para esquecer de conceder a
 * mesma permissão — e o dia em que um deles precisar de menos, é ELE que ganha um papel próprio, não
 * os outros cinco que ganham cópias.
 *
 * É um componente porque papel e política são uma peça só: uma política órfã não autoriza nada, e um
 * papel sem ela não faz nada.
 */
export class ExecutionRole extends $util.ComponentResource {
    readonly role: aws.iam.Role;

    constructor(name: string, opts?: $util.ComponentResourceOptions) {
        super("axonposts:aws:ExecutionRole", name, {}, opts);
        const parent = { parent: this };

        this.role = new aws.iam.Role(
            name,
            {
                assumeRolePolicy: JSON.stringify({
                    Version: "2012-10-17",
                    Statement: [
                        {
                            Effect: "Allow",
                            Principal: { Service: "lambda.amazonaws.com" },
                            Action: "sts:AssumeRole",
                        },
                    ],
                }),
                managedPolicyArns: [
                    aws.iam.ManagedPolicy.AWSLambdaBasicExecutionRole,
                    // Sem este, a função em VPC não consegue criar a ENI — e o sintoma é a invocação
                    // estourando o timeout sem uma linha de log da aplicação, porque ela nem chegou a
                    // iniciar.
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
                            Version: "2012-10-17",
                            Statement: [
                                // A SAÍDA. É o que o `SnsAddressing` usa.
                                { Effect: "Allow", Action: ["sns:Publish"], Resource: topic },
                                // A ENTRADA. Quem chama estas ações não é o nosso código — é o serviço
                                // do Lambda, lendo a fila EM NOME da função, e por isso elas moram no
                                // papel DELA. `ChangeMessageVisibility` entra porque é o que o
                                // `ReportBatchItemFailures` usa para devolver à fila o que voltou na
                                // lista de falhas.
                                {
                                    Effect: "Allow",
                                    Action: [
                                        "sqs:ReceiveMessage",
                                        "sqs:DeleteMessage",
                                        "sqs:GetQueueAttributes",
                                        "sqs:ChangeMessageVisibility",
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
