# EuroTransit Marketplace — Application repository (g03)

Source for the five cooperating services, their tests, the CI workflow and the `justfile`.
This is the **application** half of the two-repository GitOps model. The **configuration** repository instead holds the Helm charts, platform bootstrap, Argo CD `Application`s, SLO/alert definitions and team docs.

> CI builds and tests images, pushes immutable images to ACR, and updates desired image tags in the configuration repository. **CI never holds cluster credentials**; Argo CD reconciles the cluster to match Git.

## Services

| Service | Package | Interaction style | Owns |
|---|---|---|---|
| `catalog` | `it.g03.catalog` | Synchronous read API, tolerant of staleness | read store |
| `orders` | `it.g03.orders` | Synchronous entry (`POST /api/orders`) + async pipeline (coroutines/Flows) | orders DB + transactional outbox |
| `inventory` | `it.g03.inventory` | Synchronous reservation + async events | inventory DB (atomic reservation) |
| `payments` | `it.g03.payments` | Synchronous authorize with strict idempotency | idempotency store |
| `notifications` | `it.g03.notifications` | Fully asynchronous; degrades gracefully | (almost) stateless |

The **money path** (place order → reserve inventory → authorize payment → confirm) is the critical path; SLOs, dashboards and chaos experiments centre on it.

## Stack

- Kotlin 2.2.21, Spring Boot 4.0.3, JDK 21
- Spring Kafka (Strimzi-managed broker) for eventing; Kotlin coroutines/Flows for the async pipeline
- Resilience4j (circuit breaker + retry + bulkhead + time limiter) on cross-service sync calls
- Spring Boot Actuator + Micrometer (Prometheus) + Micrometer Tracing → OpenTelemetry (OTLP)
- PostgreSQL via CloudNativePG in-cluster; H2 in-memory for local dev/tests

## Layout

```
services/<svc>/
  src/main/kotlin/it/g03/<svc>/
  src/main/resources/application.yml
  src/main/resources/application-cluster.yml
.github/workflows/ci.yml
justfile
```

## Local development

```bash
just run catalog          # run one service on :8080 (needs Postgres)
just test orders          # run a service's tests
just test-all             # all services
just image inventory dev  # build a local image
```

Every service runs on PostgreSQL (no H2): the base profile uses the `DB_*` env vars with local-dev defaults. The `cluster` profile (`SPRING_PROFILES_ACTIVE=cluster`) layers on in-cluster hardening — quiet SQL, hidden actuator health details, and ECS JSON logs; in-cluster the `DB_*` values come from the CloudNativePG-generated secret (see the configuration repo).
