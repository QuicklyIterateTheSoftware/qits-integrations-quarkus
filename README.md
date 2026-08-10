# qits-integrations-quarkus

Quarkus glue every qits service needs and no service owns. Two modules today:

| Module | Coordinates | What it is |
| --- | --- | --- |
| `qits-auth-core/` | `eu.wohlben.qits:qits-auth-core` | Forward-auth for user traffic, claim checks for machine tokens, and the one gate that turns machine enforcement on. |
| `qits-arch-rules/` | `eu.wohlben.qits:qits-arch-rules` | Shared ArchUnit rules: platform conventions a service's own build enforces. Today, the causation-row completeness rules. |

Build: `./mvnw verify`. A clone of this repo alone must build — no monorepo, no
prior `mvn install`.

---

# qits-arch-rules

One test-scope dependency and a three-line test class turn the platform's conventions into build
failures:

```xml
<dependency>
    <groupId>eu.wohlben.qits</groupId>
    <artifactId>qits-arch-rules</artifactId>
    <version>…</version>
    <scope>test</scope>
</dependency>
```

```java
@AnalyzeClasses(packages = "eu.wohlben.qits.<service>",
    importOptions = ImportOption.DoNotIncludeTests.class)
class ArchRulesTest {
  @ArchTest static final ArchTests CAUSATION = ArchTests.in(CausationRowRules.class);
}
```

`CausationRowRules` guards qits-eventstream's row stamping, whose participation is opt-in per
entity and therefore silently forgettable. Three rules: every `@Entity` either implements
`CausedRow` or declares `@Uncaused`; every entity that implements `CausedRow` lists
`CausationStamp` in its `@EntityListeners`; nothing carries `@Uncaused` and `CausedRow` at once.
Forgetting becomes a red build naming the entity; opting out becomes one reviewable line.

The rules judge types **by fully-qualified name** — this module deliberately depends on neither
qits-eventstream nor jakarta.persistence. Bytecode carries names, a bare clone builds without the
platform registry, and the two libraries' versions stay uncoupled. The contract that buys: a rename
in qits-eventstream must update the rules' constants and the fixture mirror in this module's test
sources, where the drift surfaces first.

---

# qits-auth-core

## What it is

Two tracks of identity, in one jar.

**Users** arrive through qits-gateway, which performs the login and injects
`X-Qits-User`. `ForwardAuthMechanism` turns that header into a
`SecurityIdentity`. A service authenticates nothing itself; the header is
believed because the gateway strips every client-supplied `X-Qits-*` header
first. This pair was copy-pasted into eight services and is now in one place —
identical in behaviour, config keys included.

**Machines** arrive with a bearer token from qits-idp. The service validates it
with its own `quarkus-oidc`; this jar reads the result. `MachineAuth` answers
"does this caller hold a token for me, covering this project / workspace /
branch", behind a rollout gate so a service can ship the check before qits-idp
exists.

**It stays thin on purpose.** No `quarkus-oidc` dependency — validation is the
service's own choice of extension and config. This jar carries the mechanism,
the vocabulary, the claim checks and the gate.

### Public surface

| Class | |
| --- | --- |
| `ForwardAuthMechanism` | Reads `X-Qits-User` into a `SecurityIdentity`; no header is anonymous, not a denial. |
| `ForwardAuthIdentityProvider` | Completes that request into an identity whose principal is the username. No roles. |
| `QitsClaims` | The claim names (`project`, `workspace`, `branch`), the service ids that double as `aud` and client id, and the `*` that covers every value. |
| `MachineIdentity` | Static, CDI-free reads of a validated token off a `SecurityIdentity`. |
| `MachineAuth` | The `require*` guards, and the rollout gate they sit behind. |

## Adoption

The same shape qits-ci uses for `libs/qits-eventstream`: a nested submodule the
consuming reactor builds in place. From the consuming service's repo root:

```sh
git submodule add --name qits-integrations-quarkus \
  https://github.com/QuicklyIterateTheSoftware/qits-integrations-quarkus.git \
  qits-integrations-quarkus
git config -f .gitmodules submodule.qits-integrations-quarkus.ignore all
git config -f .gitmodules submodule.qits-integrations-quarkus.update merge
git submodule set-branch --branch main qits-integrations-quarkus
```

`--name` is not optional — see the superproject's CLAUDE.md for why.

Then add the directory to the reactor, ahead of the module that uses it:

```xml
<modules>
    <module>qits-integrations-quarkus</module>
    ...
    <module>service</module>
</modules>
```

The directory is this repo's aggregator pom, so Maven picks up `qits-auth-core`
— and anything added here later — on its own. Manage the version in the
service's parent, beside the other reactor modules:

```xml
<dependency>
    <groupId>${project.groupId}</groupId>
    <artifactId>qits-auth-core</artifactId>
    <version>${project.version}</version>
</dependency>
```

…then depend on it version-less from the `service` module.

Finally, in the service:

1. **Delete** `…/security/ForwardAuthMechanism.java` and
   `ForwardAuthIdentityProvider.java`, plus the tests that only re-asserted them
   (`ForwardAuthTest`, `NoDevUserProfile`, `IdentityEchoResource`) — that suite
   moved here with the code.
2. **Delete** the `qits.auth.forward.*` keys from the service's own
   `META-INF/microprofile-config.properties`. This jar ships them at the same
   ordinal (100), and two files carrying one key make the winner arbitrary.
3. A fresh clone now needs `git submodule update --init` before `./mvnw verify`.
   An uninitialised submodule fails as maven's `Child module … does not exist`,
   before a line compiles.

## Config

| Key | Default | Meaning |
| --- | --- | --- |
| `qits.auth.forward.user-header` | `X-Qits-User` | The header qits-gateway asserts. |
| `qits.auth.forward.dev-user` | `dev` under `%dev`/`%test`, unset otherwise | Synthetic identity when no header arrives. Ignored by a prod build even if it leaks in via env. |
| `qits.auth.machine.required` | `false` | The rollout gate. |
| `qits.auth.machine.audience` | unset | This service's own id, e.g. `qits-ci`. Required once the gate is on. |

Defaults ship in this jar's `META-INF/microprofile-config.properties`
(ordinal 100), below the service's `application.properties` (250) and env (300).

### Bearer validation and token fetching

The service adds these itself:

```xml
<dependency>
    <groupId>io.quarkus</groupId>
    <artifactId>quarkus-oidc</artifactId>
</dependency>
<dependency>
    <!-- only if the service also CALLS another service -->
    <groupId>io.quarkus</groupId>
    <artifactId>quarkus-oidc-client</artifactId>
</dependency>
```

Validating inbound bearers (qits-ci shown; substitute the service's own id):

```properties
qits.auth.machine.audience=qits-ci

quarkus.oidc.auth-server-url=http://qits-idp:8080/idp
quarkus.oidc.application-type=service
# Discovery off: the fetch is internal on qits-net while the issuer string is a
# public URL, so the JWKS path is given rather than looked up. It is joined onto
# auth-server-url, so it is `jwks`, not `/idp/jwks`.
quarkus.oidc.discovery-enabled=false
quarkus.oidc.jwks-path=jwks
quarkus.oidc.token.audience=${qits.auth.machine.audience}
```

Set `quarkus.oidc.token.issuer` when the two diverge — they do the day the idp
serves users at a public URL and the services keep fetching direct.

Bearer validation sits *beside* forward-auth, it does not replace it: a request
with no `Authorization` header falls through to the header mechanism and stays
user traffic.

Services reach qits-idp direct on qits-net, never through the gateway.

Fetching outbound tokens (qits-ci calling qits-cd):

```properties
quarkus.oidc-client.auth-server-url=http://qits-idp:8080/idp
quarkus.oidc-client.discovery-enabled=false
quarkus.oidc-client.token-path=token
quarkus.oidc-client.client-id=qits-ci
quarkus.oidc-client.credentials.secret=${QITS_CI_CLIENT_SECRET}
quarkus.oidc-client.grant.type=client
quarkus.oidc-client.grant-options.client.audience=qits-cd
```

`quarkus-oidc-client` caches and refreshes, so an idp restart pauses new-token
issuance and nothing else — validation runs off cached JWKS.

## Enforcing

Inject `MachineAuth` and call one `require*` method where the decision belongs.
It works the same from a JAX-RS filter and from a resource method.

```java
@Inject MachineAuth machineAuth;

@POST
@Path("/post-receive")
public Response postReceive(@Valid PostReceiveEvent event) {
  machineAuth.requireProject(event.repoId());
  ...
}
```

| Call | Demands |
| --- | --- |
| `require()` | A machine token addressed to this service. |
| `requireProject(p)` | …and a `project` claim equal to `p`. |
| `requireWorkspace(w)` | …and a `workspace` claim equal to `w`. |
| `requireBranch(b)` | …and a `branch` claim equal to `b`. |
| `requireClaim(name, v)` | …and any claim named in `QitsClaims`. |
| `permits(name, v)` | The same decision as a boolean, for filtering a list. |
| `enforced()` | Whether the gate is on. Log the posture with it; do not branch on it. |

An absent claim is a mismatch: a token never granted a `project` may not act on
one.

### The wildcard

A claim value of `*` (`QitsClaims.ANY`) covers every value. A client granted
`project=*` passes `requireProject(anything)`.

That is how a service acting across all of something holds its claim rather than
being granted a list that grows: qits-artifacts hosts every project's git
repositories, so its token says `project=*`.

The wildcard is read on the token side only. Passing `"*"` as the *target* asks
about a thing named `*` and gets the ordinary equality answer, so no call site
can widen its own check with it. And a wildcard on one claim grants nothing on
another — `project=*` still fails `requireWorkspace(...)`.

A failure throws `UnauthorizedException` (401) when no machine token was
presented, `ForbiddenException` (403) when one was but it does not cover the
target. Quarkus REST maps both — no exception mapper needed.

Reach for `MachineIdentity`'s static methods when the decision is more than an
equality check; they take the identity explicitly and need no CDI.

Spell claim names and service ids from `QitsClaims` (`PROJECT`, `WORKSPACE`,
`BRANCH`; `CI`, `CD`, `ARTIFACTS`, `WORKSPACES`, `GATEWAY`; `ANY`). A mistyped claim
name reads as "claim absent", which is a silent pass on an unenforced path.

### The gate

`qits.auth.machine.required` is `false` everywhere until qits-idp is deployed.
Off, every `require*` returns at once and the endpoint behaves exactly as it
does today — network trust, no bearer needed. That is what lets enforcement code
ship first and switch on later, one service at a time, with an env var.

There is no third state. Turning the gate on without
`qits.auth.machine.audience` fails at startup rather than accepting a token
minted for another service.

### Phase-1 call sites

| Service | Endpoint | Call |
| --- | --- | --- |
| qits-ci | `POST /ci/api/events/*` | `requireProject(<the event's project>)` — replaces `CiTokenFilter` / `X-CI-Token`. |
| qits-cd | `POST /cd/api/events/build-succeeded` | `require()`. |
| qits-artifacts | JAX-RS writes under `repositories`, `store`, `gc`, `mirror-upstreams` | `require()` — replaces `ArtifactsTokenFilter` / `X-Artifacts-Token`. `/artifacts/git/*` and `/v2/*` are unchanged. |

Each service sets `qits.auth.machine.audience` to its own id, so `require()`
already means "a token minted for me".
