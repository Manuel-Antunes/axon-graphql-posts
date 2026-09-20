/**
 * O EVENT STORE de um serviço — o de `posts-api` e o de `tagging` são duas instâncias desta classe.
 *
 * Serem duas é o ponto do teste inteiro: o que prova a integração não é o evento chegar, é ele estar
 * APENDADO no store de quem o recebeu. Uma classe por store, e a simetria fica visível na afirmação.
 */
import type { Container } from "./docker";

/** A identidade de fio de uma mensagem: o que uma reentrega precisa copiar para ser a MESMA. */
export interface MessageIdentity { identifier: string; messageType: string }

export class EventStore {
  constructor(
    private readonly postgres: Container,
    readonly database: string,
  ) {}

  /** Os tipos de evento no stream de um agregado, EM ORDEM. Um laço de reenvio aparece como repetição. */
  streamOf(aggregateId: string): string {
    return this.query(
      `select string_agg(type, ',' order by globalindex) from aggregateevententry `
      + `where aggregateidentifier = '${aggregateId}'`,
    );
  }

  countEvents(aggregateId: string, type: string): number {
    return Number(
      this.query(
        `select count(*) from aggregateevententry `
        + `where aggregateidentifier = '${aggregateId}' and type = '${type}'`,
      ),
    );
  }

  /** O inbox lido como `tipoDaMensagem<-origem`: é o PAR que conta, não a linha crua. */
  inbox(): string {
    return this.query(`select string_agg(message_type || '<-' || origin, ',') from axon_message_inbox`);
  }

  inboxRowsFor(identifier: string): number {
    return Number(
      this.query(`select count(*) from axon_message_inbox where identifier = '${identifier}'`),
    );
  }

  identityOf(aggregateId: string, type: string): MessageIdentity {
    return JSON.parse(
      this.query(
        `select json_build_object('identifier', identifier, 'messageType', type || '#' || version)::text `
        + `from aggregateevententry where aggregateidentifier = '${aggregateId}' and type = '${type}'`,
      ),
    ) as MessageIdentity;
  }

  /**
   * Um estado limpo — e o event store vai JUNTO com o read model.
   *
   * Limpar só as tabelas de leitura deixaria estado incoerente: a linha da tag some, o stream do
   * agregado continua no store, e o `CreateTag` seguinte falha contra um agregado que existe num
   * lugar e não no outro.
   */
  truncate(...readModelTables: string[]): void {
    const readModel = readModelTables.length > 0
      ? `truncate table ${readModelTables.join(", ")} cascade;`
      : "";
    this.query(`${readModel} truncate table aggregateevententry, tokenentry, axon_message_inbox;`);
  }

  private query(sql: string): string {
    return this.postgres.exec("psql", "-U", "axonposts", "-d", this.database, "-tAc", sql);
  }
}
