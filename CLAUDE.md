# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

A POC/MVP for a bulk email-sending platform (a scaled-down [Notifuse](https://github.com/Notifuse/notifuse)). The defining architectural idea: **the API enqueues work and returns immediately; a separate worker drains the queue in batches asynchronously.** This decoupling keeps the API responsive regardless of recipient-list size. Source comments are in English; the README is in Korean.

Beyond the original enqueue/drain POC it now has: **JWT auth** (signup/login), **real SMTP sending** (MailHog in dev) with HTML bodies, **unsubscribe + suppression**, and **self-implemented open/click tracking** with per-campaign analytics. The MVP roadmap and scope decisions live in [docs/MVP-PLAN.md](docs/MVP-PLAN.md) (next up: M3 templates/personalization, M4 contacts/lists).

## Commands

Requires **JDK 21**. All `bootRun` tasks are configured to run from the repo root (see `build.gradle.kts`) so the relative H2 path `./data/maildb` resolves to one shared file — run them from the root, not from module dirs. If `JAVA_HOME` is unset, prefix Gradle with e.g. `JAVA_HOME=/c/Users/user/.jdks/corretto-21.0.11`.

```bash
# Dev SMTP sink (worker sends here; web UI on :8025)
docker run -d --rm --name mailhog -p 1025:1025 -p 8025:8025 mailhog/mailhog

# Backend services (each in its own terminal)
./gradlew :mail-api:bootRun       # REST API on :8080
./gradlew :mail-worker:bootRun    # queue poller (no web server), sends via SMTP -> MailHog
./gradlew :mail-admin:bootRun     # admin console on :8081 (skeleton)

# Frontend
cd frontend && npm install && npm run dev   # Vite dev server on :5173, proxies /api -> :8080

# Build / test
./gradlew build                   # build all modules + run tests
./gradlew :mail-core:test         # test one module
./gradlew test --tests "io.github.ahrimjang.mail.core.service.CampaignServiceTest"  # single test class
cd frontend && npm run build      # tsc -b && vite build
```

> No test classes exist yet — the test wiring (`spring-boot-starter-test`, JUnit Platform) is in place for when they're added.

A typical end-to-end run needs **MailHog + mail-api + mail-worker + frontend** up together, all backends sharing `./data/maildb`. To reset state, stop the services and delete `./data/` (tracking/unsubscribe tokens only exist on rows created after those features landed, so a reset avoids stale nulls). Sent mail is inspected at `http://localhost:8025`.

## Architecture

The backend is a Gradle multi-module project following **hexagonal / ports-and-adapters**. Dependencies point inward toward `mail-core`; `mail-core` knows nothing about web or persistence (only Spring's `spring-context` for `@Service`/DI + `@Value`).

```
mail-common   DTOs + enums shared everywhere. Campaign: CreateCampaignRequest, CampaignView,
              CampaignStatus, MessageStatus, EventType. Auth: SignupRequest, LoginRequest, AuthResponse.
mail-core     Domain + ports + use-case services. No web/JPA.
                · domain/  Campaign, MailMessage, User, Suppression, EmailEvent
                · port/    CampaignRepository, MailMessageRepository, MailSender,
                           UserRepository, PasswordHasher, TokenService,
                           SuppressionRepository, EmailEventRepository
                · service/ CampaignService, MailDispatchService, AuthService,
                           SuppressionService, TrackingService, TrackingRewriter
infra         Adapters for the core ports: JPA repositories + LoggingMailSender / SmtpMailSender,
              BCryptPasswordHasher, JwtTokenService (jjwt).
mail-api      Spring Boot web app (:8080). Controllers: CampaignController, HealthController,
              auth/AuthController + SecurityConfig + JwtAuthFilter, UnsubscribeController, TrackingController.
mail-worker   Spring Boot app, no web. Scheduled poller calling MailDispatchService; sends via SMTP.
mail-admin    Spring Boot web app (:8081). Boots but has no features yet.
frontend      React 18 + Vite + TypeScript. Auth-gated single-page campaign form + live progress/metrics.
```

### How the queue works (the core flow)

1. `POST /api/campaigns` (JWT required) → `CampaignService.create()` saves the campaign (status `QUEUED`) and fans the recipient list into one `MailMessage` row per recipient, each `PENDING` with a generated `unsubToken` + `trackingToken`. Returns immediately.
2. `MailDispatchScheduler` (in mail-worker) fires every `poll-interval-ms` (default 2000) and calls `MailDispatchService.dispatchBatch(batchSize)`.
3. `dispatchBatch` claims up to `batchSize` (default 50) `PENDING` messages ordered by id. Per message: skip suppressed addresses (`SUPPRESSED`); otherwise assemble the HTML body (`TrackingRewriter` rewrites `href` links to click-redirects, then append the unsubscribe footer, then the open-pixel), send via the `MailSender` port, and mark `SENT`, or on failure `BOUNCED` + auto-suppress the address. The campaign flips to `SENDING` on first progress and `COMPLETED` once its queue is fully drained (`pending == 0`).
4. The frontend polls `GET /api/campaigns/{id}`; `CampaignView` carries live `total/pending/sent/failed/bounced/suppressed` (from message rows) plus `opened/clicked` (distinct-message counts derived from `EmailEvent` rows).

`batch-size` is the crude throughput throttle. Statuses: campaigns `QUEUED → SENDING → COMPLETED`; messages `PENDING → SENT | FAILED | BOUNCED | SUPPRESSED`. **Message status is delivery-outcome only — engagement (opened/clicked) is event-derived, never a status.**

### Auth, tracking, and sending seams

- **JWT auth.** `AuthService` (signup hashes with BCrypt, issues a JWT; login verifies). `SecurityConfig` is stateless: `permitAll` for `/api/auth/**`, `/api/health`, `/api/unsubscribe/**`, `/api/track/**`; everything else needs a valid Bearer token (validated by `JwtAuthFilter` → `TokenService`). Secret/expiry via `app.jwt.*` (mail-api yml). Frontend stores the token in `localStorage` and sends `Authorization: Bearer`.
- **Sender is swappable by property.** Both `LoggingMailSender` and `SmtpMailSender` are `@ConditionalOnProperty("mail.sender.type", …)`. Worker sets `mail.sender.type=smtp` (→ MailHog via `spring.mail.*`); api/admin default to `logging` (they don't send). Replace `SmtpMailSender` with an SES/Sendgrid adapter to go to production — nothing else changes.
- **Tracking is self-implemented.** `/api/track/open/{token}` returns a 1×1 GIF and records an `OPEN`; `/api/track/click/{token}?u=<url>` records a `CLICK` and 302-redirects to `u`. `unsubscribe/{token}` adds the recipient to the suppression list, honored at dispatch. Link rewriting is regex-based (`href="http…"`, double-quoted) — a known MVP limitation.

### Conventions and POC stand-ins

Deliberate shortcuts — the seams where production implementations plug in:

- **Queue = shared H2 file DB.** `jdbc:h2:file:./data/maildb;AUTO_SERVER=TRUE` lets the separate api/worker/admin processes share one queue. Production swaps this for Postgres/RDS + a real MQ (SQS/Kafka).
- **`findPending` has no concurrency claim.** Multiple workers would double-send. Horizontal scaling needs `SELECT ... FOR UPDATE SKIP LOCKED` or equivalent.
- **No retry/backoff, throttling, real bounce/complaint webhooks, templating/personalization, or contact/list management** yet (see [docs/MVP-PLAN.md](docs/MVP-PLAN.md) for M3/M4).
- **`InfraJpaConfig`** centralizes `@EntityScan`/`@EnableJpaRepositories` on the infra package; each runnable app component-scans `io.github.ahrimjang.mail` so all share identical persistence wiring.

### Adding functionality

- New domain behavior → `mail-core` (define/extend a port if it needs the outside world; keep it free of Spring web/JPA imports).
- New adapter (real mail sender, different store) → `infra`, implementing a `mail-core` port.
- New endpoint → `mail-api` controller delegating to a `mail-core` service; if public, add its path to `SecurityConfig` `permitAll`.
- Shared request/response shape → `mail-common` (used by both API and frontend contract).
