/// <reference path="../../../.sst/platform/config.d.ts" />

/**
 * A rede. Um recurso só, e três decisões dentro dele.
 *
 * <h2>Por que há VPC num desenho "serverless"</h2>
 * Porque o RDS vive nela. É a única razão — se um dia os event stores saírem para um serviço com
 * endpoint público e autenticação IAM, este arquivo some junto.
 *
 * <h2>O NAT, e o que ele custa</h2>
 * As funções precisam do VPC para alcançar o RDS, e de saída para a internet para alcançar SNS, SQS e
 * o discovery do Cognito. `nat: "managed"` resolve as duas com uma linha — e é o item mais caro desta
 * stack por hora. A alternativa mais barata seria endpoints de interface para SNS e SQS e nenhum NAT;
 * não vale a complexidade num ambiente que existe para ser derrubado com `sst remove`.
 *
 * <h2>O security group, e por que não há regra nenhuma escrita aqui</h2>
 * O que o SST cria para o VPC libera TODO tráfego vindo do CIDR do próprio VPC. É o que dá às funções
 * acesso aos dois RDS sem uma regra a mais — e o que torna `vpc.securityGroups` a resposta certa para
 * `securityGroupIds` das funções.
 */
export const vpc = new sst.aws.Vpc('Vpc', { nat: 'managed' });
