# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

A POC/MVP for a bulk email-sending platform (a scaled-down [Notifuse](https://github.com/Notifuse/notifuse)). The defining architectural idea: **the API enqueues work and returns immediately; a separate worker drains the queue asynchronously.** This decoupling keeps the API responsive regardless of recipient-list size. Source comments are in English; the README and UI copy are in Korean.

Feature set: **JWT auth**, **RabbitMQ send queue** (push-based consumer, DLQ), **real SMTP sending** (MailHog in dev) with HTML bodies, **unsubscribe + suppression**, **self-implemented open/click tracking** with per-campaign analytics, **bounce webhooks** with message correlation, **templates with `{{variable}}` personalization** (rendered per contact at send time), **transactional single-send API**, and **contacts/lists with CSV import**. Roadmap history: [docs/MVP-PLAN.md](docs/MVP-PLAN.md). **Stage-by-stage logic walkthroughs (Korean, with code snippets): [docs/logic/](docs/logic/README.md).**

## Commands

Requires **JDK 21**. All `bootRun` tasks run from the repo root (see `build.gradle.kts`) so the relative H2 path `./data/maildb` resolves to one shared file — run them from the root. If `JAVA_HOME` is unset, prefix Gradle with e.g. `JAVA_HOME=/c/Users/user/.jdks/corretto-21.0.11`.

```bash
# Dev infra: RabbitMQ (5672, UI 15672 guest/guest) + MailHog (SMTP 1025, UI 8025)
docker compose up -d          # docker compose down -v to reset queues

# Backend services (each in its own terminal)
./gradlew :mail-api:bootRun       # REST API on :8080 (publishes send jobs)
./gradlew :mail-worker:bootRun    # @RabbitListener consumer (no web server), sends via SMTP -> MailHog
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

A full end-to-end run needs **docker compose (rabbitmq+mailhog) + mail-api + mail-worker + frontend**. To reset state: stop services, `rm -rf ./data` (H2), optionally `docker compose down -v` (queues). Sent mail: `http://localhost:8025`. Queue traffic: `http://localhost:15672`.

## Architecture

Gradle multi-module, **hexagonal / ports-and-adapters**. Dependencies point inward to `mail-core`; `mail-core` knows nothing about web/JPA/AMQP (only `spring-context` for `@Service`/DI + `@Value`).

```
mail-common   DTOs + enums (shared API/frontend contract). Campaign/queue: CreateCampaignRequest,
              CampaignView, SendJob, CampaignStatus, MessageStatus, EventType. Auth: Signup/Login/AuthResponse.
              Bounce: BounceType, BounceNotification. M3/M4: TemplateRequest/View, RenderedTemplate,
              ContactRequest/View, ContactListRequest/View, ImportResult, TransactionalRequest.
mail-core     Domain + ports + use-case services. No web/JPA/AMQP.
                · domain/  Campaign, MailMessage, User, Suppression, EmailEvent, Template, Contact, ContactList
                · port/    CampaignRepository, MailMessageRepository, MailSender, MailQueue,
                           UserRepository, PasswordHasher, TokenService,
                           SuppressionRepository, EmailEventRepository,
                           TemplateRepository, ContactRepository, ContactListRepository
                · service/ CampaignService, MailDispatchService, AuthService, SuppressionService,
                           TrackingService, TrackingRewriter, BounceService,
                           TemplateRenderer, TemplateService, ContactService, ContactListService,
                           TransactionalService
infra         Adapters: persistence/ (JPA repos incl. contact attributes as JSON via Jackson),
              messaging/ (RabbitMailConfig topology + RabbitMailQueue publisher),
              mail/ (LoggingMailSender / SmtpMailSender), security/ (BCryptPasswordHasher, JwtTokenService).
mail-api      Spring Boot web app (:8080). CampaignController, TemplateController, ContactController,
              ContactListController, TransactionalController, TrackingController, UnsubscribeController,
              WebhookController, HealthController, auth/ (AuthController + SecurityConfig + JwtAuthFilter).
mail-worker   Spring Boot app, no web. MailSendListener (@RabbitListener) -> MailDispatchService.dispatchOne.
mail-admin    Spring Boot web app (:8081). Boots but has no features yet.
frontend      React 18 + Vite + TS. Auth gate + tabs: 캠페인 / 템플릿 / 연락처 / 리스트 (src/tabs/*).
              src/api.ts adds Bearer + handles 401; polling for live campaign metrics.
```

### The send pipeline (core flow — diagram: docs/send-pipeline.svg)

1. `POST /api/campaigns` (JWT) → `CampaignService.create()`: content comes **directly** (subject/body) or is **snapshotted from a template** (`templateId`); recipients come from raw strings or a **contact list fan-out** (`listId`, one `MailMessage` per member with `contactId`). Each PENDING row gets `unsubToken` + `trackingToken`, then its id is **published to RabbitMQ** (`SendJob(messageId)` → `mail.exchange`/`mail.send` → `mail.send.queue`, DLQ `mail.send.dlq`). Returns 201 immediately.
2. mail-worker's `MailSendListener` receives jobs by **push** and calls `dispatchOne(messageId)` — **idempotent**: reloads the row by id and skips anything not PENDING (RabbitMQ is at-least-once).
3. `dispatchOne`: suppression check (skip as `SUPPRESSED`) → **personalization** (`TemplateRenderer` renders `{{vars}}` in subject/body with the contact's `toVariables()`, or `{email}` for raw recipients) → tracking-rewrite links + unsubscribe footer + open pixel → `MailSender.send(...)` with an injected `X-Mail-Message-Id` header → `SENT`, or `BOUNCED` + auto-suppress on failure. Campaign flips `SENDING` on first progress, `COMPLETED` when `pending == 0`.
4. Frontend polls `GET /api/campaigns/{id}`: `total/pending/sent/failed/bounced/suppressed` from message rows + `opened/clicked` (distinct-message counts from `EmailEvent`).

Statuses: campaigns `QUEUED → SENDING → COMPLETED`; messages `PENDING → SENT | FAILED | BOUNCED | SUPPRESSED`. **Message status is delivery-outcome only — engagement (opened/clicked) is event-derived, never a status.** H2 is the **state store**, not the queue.

### Key seams and behaviors

- **JWT auth.** Stateless `SecurityConfig`: `permitAll` for `/api/auth/**`, `/api/health`, `/api/unsubscribe/**`, `/api/track/**`, `/api/webhooks/**`; everything else needs a Bearer token (`JwtAuthFilter` → `TokenService`). Secrets via `app.jwt.*`. Frontend stores the token in `localStorage`.
- **Sender swappable by property.** `mail.sender.type=smtp` (worker → MailHog) vs `logging` (default, api/admin). Swap `SmtpMailSender` for SES/Sendgrid to go to production.
- **Tracking self-implemented.** Open pixel + click redirect record `EmailEvent`s keyed by `trackingToken`. Link rewriting is regex-based (`href="http…"`, double-quoted) — known MVP limitation.
- **Bounces.** `POST /api/webhooks/generic` (shared secret `X-Webhook-Token`, config `app.webhook.secret`) takes a normalized `BounceNotification`; HARD_BOUNCE/COMPLAINT → suppress; a supplied `messageId` (echoed from the `X-Mail-Message-Id` send header) also marks that message BOUNCED + records `EmailEvent(BOUNCE)`. Design doc: [docs/bounce-webhook-design.md](docs/bounce-webhook-design.md).
- **Templates/personalization.** Campaign snapshots template content at create; `{{variables}}` render at send per contact (unknown vars → empty string). Preview: `POST /api/templates/{id}/preview`. Transactional: `POST /api/transactional` renders with request variables and reuses the whole pipeline as a single-recipient campaign.
- **Contacts/lists.** Contact has attributes `Map<String,String>` (JSON column); **no status field — the suppression list is the only do-not-send source of truth**. CSV import: `POST /api/contacts/import?listId=` (text/plain, `email,firstName,lastName` lines; duplicates/invalid → skipped).

### Conventions and POC stand-ins

- **H2 file DB (`./data/maildb`, AUTO_SERVER)** shared by api/worker/admin as the state store. Production: Postgres/RDS.
- **RabbitMQ topology** declared in `RabbitMailConfig` (durable, JSON converter); worker listener retry 3x with backoff, then DLQ. Constants (`mail.exchange`, `mail.send.queue`, …) are shared via that class.
- **No throttling, soft-bounce retry policy, provider-specific webhook parsers (SES/SendGrid), or Kafka event stream** yet — next candidates.
- **`InfraJpaConfig`** centralizes `@EntityScan`/`@EnableJpaRepositories`; each app component-scans `io.github.ahrimjang.mail`.

### Adding functionality

- New domain behavior → `mail-core` (extend a port if it needs the outside world; keep Spring web/JPA out).
- New adapter (mail provider, store, broker) → `infra`, implementing a `mail-core` port.
- New endpoint → `mail-api` controller delegating to a `mail-core` service; if public, add to `SecurityConfig` permitAll.
- Shared request/response shape → `mail-common`; mirror it in `frontend/src/types.ts`.
