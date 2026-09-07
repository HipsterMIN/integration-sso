# OnePass Support CS Backoffice Plan

## Goal

`idem-support` is developed as an independent CS backoffice module. It does not depend on the SSO/IM user login flow for CS staff access. Public Q&A and FAQ remain user-facing support channels, while CS staff work in a separate operational area.

## Scope

### Phase 1 - Backoffice Foundation

- [x] Define CS backoffice plan as a tracked document.
- [x] Add a separate CS requester context based on `X-CS-Agent-*` headers.
- [x] Keep existing `X-User-*` public Q&A behavior separate from CS staff behavior.
- [x] Add ticket domain tables for a unified support queue.
- [x] Add ticket timeline events for replies, internal notes, phone calls, assignments, and status changes.
- [x] Add phone consultation capture for call-based 민원.
- [x] Add backoffice APIs for queue, ticket detail, phone intake, internal note, assignment, and status update.
- [x] Link new Q&A posts to support tickets.
- [x] Record Q&A answers as ticket timeline events.
- [x] Verify with `:idem-support:test`.

### Phase 2 - Operational Controls

- [ ] Replace temporary CS headers with a dedicated backoffice identity provider client or realm.
- [ ] Add role-based API guards with Spring Security.
- [ ] Add audit logging for secret Q&A, phone records, and requester PII access.
- [ ] Add SLA fields, due dates, and callback queue views.
- [ ] Add answer templates and FAQ conversion workflow.

### Phase 3 - CS Optimization

- [ ] Add dashboard metrics for open, pending, answered, callback due, and SLA-risk tickets.
- [ ] Add attachment support for 상담 evidence.
- [ ] Add CTI/call recording integration points.
- [ ] Add 상담 품질 review and reporting.
- [ ] Add AI-assisted call summary and FAQ recommendation after security review.

## Backoffice Menus

- Dashboard: open tickets, my queue, callbacks due, and response-risk items.
- Unified Queue: all tickets from Q&A, phone, and future channels.
- Q&A Handling: answer public/private Q&A and track status.
- Phone Intake: create a ticket while documenting 전화 민원.
- Ticket Detail: timeline, internal notes, public replies, assignment, status, and requester context.
- Callback Queue: scheduled follow-ups and overdue callbacks.
- FAQ/Templates: reusable answers and FAQ publishing.
- Admin Settings: CS users, roles, categories, priorities, statuses, and SLA policy.

## Role Model

- `CS_AGENT`: create phone tickets, add notes, answer assigned tickets.
- `CS_LEAD`: assign tickets, change priority, escalate, reopen, and close.
- `SUPPORT_ADMIN`: manage settings and privileged operations.
- `AUDITOR`: read-only access for inspection.

## Phase 1 API Target

- `GET /api/v1/admin/support/tickets`
- `GET /api/v1/admin/support/tickets/{id}`
- `POST /api/v1/admin/support/phone-consultations`
- `POST /api/v1/admin/support/tickets/{id}/internal-notes`
- `PATCH /api/v1/admin/support/tickets/{id}/assignment`
- `PATCH /api/v1/admin/support/tickets/{id}/status`
- existing `POST /api/v1/admin/support/qna/{id}/answer` also writes a ticket event.

## Completion Log

- 2026-05-23: Plan created on `feature/idem-support`.
- 2026-05-23: Phase 1 backend foundation implemented on `feature/idem-support`; compile and module tests passed.
