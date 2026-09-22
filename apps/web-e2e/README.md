# web-e2e

The whole system, through a browser: **Playwright**, the two Quarkus services as containers, a real
RabbitMQ, a real Postgres, a real Keycloak and `apps/web` as a Next server.

```bash
pnpm test:e2e                                   # this level, plus the two Java ones
npx nx run web-e2e:test:e2e                     # only this level
```

It **replaced `apps/posts-api-e2e`**, and the reason is the frontend: the saga was already proven
across two processes, but nothing proved that a person could sign in, be refused, write a post in a
form and watch another service complete it. The same run now does both — and it keeps every assertion
the old suite had, because a Playwright test is Node and can read an event store or a queue as easily
as a page.

## The four levels, and where this one sits

This is the outermost. `apps/posts-api`'s own suite fakes the second service on purpose
(`InProcessTagAssignment`), and `apps/tagging`'s fakes the transport. Here nothing is faked:
`posts-api` and `tagging` are two containers, the broker routes between them, each service keeps its
own database, the client is a Next server and the user is Chromium.

## What the stack does

`src/support/stack.ts`, once, in Playwright's `globalSetup`:

1. **The infrastructure comes up** — `docker compose up -d --wait postgres keycloak rabbitmq`.
2. **Both databases are dropped and recreated**, then migrated by the `flyway-*` services. There is no
   history to disagree with, so a migration whose checksum changed cannot take the run down. That is
   the same reasoning `posts-api-e2e` had, inherited whole.
3. **The queues are deleted, not purged.** Purging takes the messages and leaves the BINDINGS, which
   are durable and survive a redesign of the topology.
4. **`apps/web` is built with the e2e environment** (`npx nx run web:build`), and the environment is
   passed explicitly for a reason given below.
5. **The three applications start** — two `docker compose --profile apps` containers and one
   `next start` — logs captured to `target/logs`, and the suite waits for each to answer.

**Readiness is a strategy, not an `if`** (`src/support/service.ts`): `posts-api` has `/q/health`,
`web` answers `/login`, and `tagging` has **no port at all** — `quarkus-opentelemetry` depends on
`quarkus-vertx`, not on `quarkus-vertx-http`, so the only sign it came up is the line in its log.
Three answers to the same question is what makes `HttpHealth`, `HttpAnswering` and `LogLine` three
implementations of `Readiness`.

The two Java applications are containers and the client is a process, and the asymmetry is not
sloppiness: the Java side ships images (that is what `sst deploy` and the `docker:build` targets
produce), and the Next side ships a `.next` directory. Each is started the way it is deployed.

## THE ENVIRONMENT IS PASSED EXPLICITLY, AND THAT IS NOT DEFENSIVE

`NEXT_PUBLIC_GRAPHQL_URL` is **inlined at build time** — Next bakes every `NEXT_PUBLIC_*` into the
bundles, so the value that counts is the one the build saw, not the one `next start` sees. And
`apps/web/.env.local` exists on a developer's machine pointing at the **deployed AWS stage**
(`infra/scripts/discover.sh` writes it). A build that inherited it would produce a client talking to
API Gateway while the suite asserted against a container on `localhost:8080`, and the failure would
look like the saga not closing.

Real environment variables beat `.env` files in Next, so the stack sets them and runs the build
itself, in one place, instead of declaring `web:build` as an Nx `dependsOn` — a dependency runs with
the ambient environment, which is exactly the one that is wrong here.

## The identity provider, and why `apps/web` grew one

The client used to sign in **only** against Cognito, and locally the identity provider is Keycloak. A
browser suite could not log in at all: the server action posted to `cognito-idp.<region>.amazonaws.com`,
and the UI gated every write on `cognito:groups`, which a Keycloak token does not carry.

What that bought, in `apps/web`:

| file | what it does |
|---|---|
| `lib/auth/identity.ts` | `AuthTokens`, `IdentityError` and the `PasswordIdentityProvider` port |
| `lib/auth/oidc.ts` | the password grant, against whatever `OIDC_ISSUER_URL` announces |
| `lib/auth/provider.ts` | picks one: OIDC when `OIDC_ISSUER_URL` is set, Cognito otherwise |
| `lib/auth/claims.ts` | groups come from `cognito:groups`, and fall back to `realm_access.roles` |

**The token endpoint is DISCOVERED, not written.** `/.well-known/openid-configuration` is asked once
and the answer is cached in the module, so the provider is not Keycloak-shaped — pointing
`OIDC_ISSUER_URL` at any OIDC issuer with a direct access grant works.

**And the bearer stored in the session is the ACCESS token, not the ID token** — that is the one
divergence from the Cognito path, and it is forced. Keycloak puts `realm_access.roles` in the access
token, and `posts-api` validates the access token; its ID token from the same grant has no roles, so
a session holding it would sign in and be refused on the first mutation. On Cognito the opposite is
true (its access token carries no `email`, which `UserProvisioning` needs), which is why that
provider keeps returning the ID token. `withTheAccessTokenAsBearer` is where the OIDC side says so.

Nothing about the AWS deployment changed: `OIDC_ISSUER_URL` is unset there, so `provider()` answers
Cognito exactly as before.

## What the specs read

| spec | what it proves |
|---|---|
| `authentication` | the form signs in against the environment's issuer; the session is an `httpOnly` cookie the page's JavaScript cannot reach; it survives a reload; a wrong password is refused with the issuer's own reason; signing out clears it — **and the token the web stored is accepted by the posts-api**, which is the claim the proxy rests on |
| `authorization` | the three states of `/posts/new` (anonymous, authenticated without the role, author); that the refusal is the SERVER's and not the screen's (`FORBIDDEN` through the proxy); that reading is anonymous on purpose; and that `me` is polymorphic — `Reader` for one, `Author` for the other |
| `reading` | a post written in the form reaches someone who never signed in, with author and tag resolved |
| `live` | the subscription reaches the browser THROUGH the proxy: `onPostCreated` announces version 2 — the saga closing — and `onPostUpdated` starts at 3 |
| `saga` | everything `posts-api-e2e` asserted, now entered through the form: version 1 with no tag, version 2 with the tag the other service decided, both event stores, both inboxes with the right origin, a redelivery held by the inbox and the aggregate, and the replica channel |

Everything goes through the browser and `/api/graphql` — the proxy the page itself uses, which reads
the `httpOnly` cookie and puts the `Authorization` on the way out. **One assertion talks to the
posts-api directly**, to show the cookie the web wrote is accepted there; that is the claim, so
bypassing the web is the test.

The redelivery case builds the `AxonEnvelope` **by hand** and publishes it through the management API,
which makes it a test of the wire format as well: the origin mark, the tag and the identity are what
the ingestion reads to decide the message is a duplicate.

## What a failure leaves behind

`retain-on-failure` for the video and the trace, `only-on-failure` for the screenshot — a green run
writes almost nothing, and a red one writes everything about the test that went red.

```
target/playwright/<test>/video.webm          only when it failed
target/playwright/<test>/test-failed-1.png   only when it failed
target/playwright/<test>/trace.zip           only when it failed
target/playwright-report/index.html          under CI, always
target/test-results/junit.xml                under CI, always
target/logs/{posts-api,tagging,web}.log      always
```

In CI those are three artifacts of the run — `playwright-report`, `playwright-artifacts` and
`e2e-reports` — and the job writes a summary saying which is which. A trace opens at
<https://trace.playwright.dev> with nothing installed.

## Running one thing

```bash
cd apps/web-e2e
npx playwright test src/specs/authorization.spec.ts
npx playwright test -g "reentregar a MESMA mensagem"
npx playwright test --ui                     # the same stack, driven by hand
npx playwright show-trace target/playwright/<test>/trace.zip
```

`workers: 1` and `fullyParallel: false` are not caution: the stack is one Postgres, one broker and one
set of three processes, and the saga's assertions read durable state a second worker would be writing
at the same time. This suite trades parallelism for being able to claim what it claims. And `retries: 0`
for the same reason the old suite had it — a test that only passes on the second attempt hides exactly
what this app exists to measure.

`globalSetup` and `globalTeardown` share the stack through a module-level holder
(`src/support/running-stack.ts`), because Playwright runs both in the **main** process — a second
`ChoreographyStack` would have no child handle for the Next server and would leave it holding the
port. The specs never need it: they reach the durable state through their own fixtures, over
`docker exec` and the management API, which need nothing handed across a process boundary.
