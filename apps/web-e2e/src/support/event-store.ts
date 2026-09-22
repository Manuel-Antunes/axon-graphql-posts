import type { Container } from './docker';

export interface MessageIdentity {
  identifier: string;
  messageType: string;
}

export class EventStore {
  constructor(
    private readonly postgres: Container,
    readonly database: string,
  ) {}

  streamOf(aggregateId: string): string {
    return this.query(
      `select coalesce(string_agg(type, ',' order by globalindex), '') from aggregateevententry ` +
        `where aggregateidentifier = '${aggregateId}'`,
    );
  }

  countEvents(aggregateId: string, type: string): number {
    return Number(
      this.query(
        `select count(*) from aggregateevententry ` +
          `where aggregateidentifier = '${aggregateId}' and type = '${type}'`,
      ),
    );
  }

  inbox(): string {
    return this.query(
      `select coalesce(string_agg(message_type || '<-' || origin, ','), '') from axon_message_inbox`,
    );
  }

  inboxRowsFor(identifier: string): number {
    return Number(
      this.query(
        `select count(*) from axon_message_inbox where identifier = '${identifier}'`,
      ),
    );
  }

  identityOf(aggregateId: string, type: string): MessageIdentity {
    return JSON.parse(
      this.query(
        `select json_build_object('identifier', identifier, 'messageType', type || '#' || version)::text ` +
          `from aggregateevententry where aggregateidentifier = '${aggregateId}' and type = '${type}'`,
      ),
    ) as MessageIdentity;
  }

  tagsOf(postId: string): string[] {
    const names = this.query(
      `select coalesce(string_agg(t.name, ',' order by t.name), '') from post_tags pt ` +
        `join tags t on t.id = pt.tag_id where pt.post_id = '${postId}'`,
    );
    return names === '' ? [] : names.split(',');
  }

  versionOf(postId: string): number {
    return Number(
      this.query(`select version from posts where id = '${postId}'`),
    );
  }

  truncate(...readModelTables: string[]): void {
    const readModel =
      readModelTables.length > 0
        ? `truncate table ${readModelTables.join(', ')} cascade;`
        : '';
    this.query(
      `${readModel} truncate table aggregateevententry, tokenentry, axon_message_inbox;`,
    );
  }

  private query(sql: string): string {
    return this.postgres.exec(
      'psql',
      '-U',
      'axonposts',
      '-d',
      this.database,
      '-tAc',
      sql,
    );
  }
}
