# AI Prompt and Usage Record

- **Tool:** Claude Code, model Claude Opus 5.5, one session on 2026-11-25.
- **Developer:** initialized the Spring Boot project, made the scope and design decisions, and approved each step.
- **AI:** analysis, design proposals, all feature and test code, the verification runs, and doc drafts.
- **Verification runs:** the AI executed them in-session and the developer reviewed the output. The developer then re-ran the test suite and build independently (see §3).
- **Privacy:** no credentials or sensitive data were included in any prompt.

## 1. Prompts and decisions

Prompts are quoted, shortened with "…".

| # | Prompt | AI contribution | Decision |
|---|---|---|---|
| 1 | "…understand what we're supposed to build and what the existing project contains… suggest a simple architecture and point out any edge cases…" | Requirements breakdown, layered design, half-open overlap rule, 8 open decisions, edge cases | Accepted as a baseline |
| 2 | "there is no starter project that is provided. i have initialized this spring boot project myself" | Re-framed the work as the brief's fallback case | **Developer correction** of the AI's assumption |
| 3 | "…keep this simple… in-memory store… wouldn't using the rest api's be a good idea? suggest the classes… conflicts, cancellation and concurrency…" | Class plan, soft-cancel via `POST …/cancel`, per-provider lock, build order | Accepted. AI's repository interface **rejected** for simplicity |
| 4 | "…explain the concurrency part in simpler terms… restrict providerId to start with a "P" and clientId to start with a "C"?" | Race explained as a timeline; proposed `^P\d+$`/`^C\d+$`; raised the client-conflict question | Locking accepted after the explanation |
| 5 | "…we don't actually need a client id… P followed by a 3-digit number like P001…" | Pattern `^P\d{3}$`; flagged that `P000` matches | `clientId` **rejected**; pattern **edited** by the developer |
| 6 | "…invalidate P000 as well" | `^P(?!000)\d{3}$`; restated the final defaults | **Developer edit**; plan approved |
| 7 | "yes, start with step 1" | `Appointment`, `AppointmentStatus`, repository | Accepted |
| 8 | "…explain why we are using concurrent hashmaps in simpler terms" + step 2 | Explanation; service, exceptions, 35 tests | Accepted. AI also put provider-ID validation in the service (reported) |
| 9 | "yes, go ahead with step 3" | Concurrency test (20 threads, 25× repeats) | Accepted |
| 10 | "yes, go ahead with step 4" | Controller, DTOs, error handler, 38 tests | Accepted. String timestamps (V4) and a null fix (V5) reported |
| 11 | "…prepare the README, design specification and AI usage record…", then "…keep the AI-usage, readme and design relevant and short…" | These documents | Reviewed and shortened at the developer's request |
| 12 | "i want to understand the test suite properly rather than just relying on the fact that all 150 tests pass… don't modify or delete any tests…" | Plain-English walkthrough of every test, grouped by behaviour and acceptance criterion, essential vs. additional; explained how the concurrency tests force simultaneous requests; identified gaps (no test proves providers run in parallel; weak cancel-race detection; no concurrent book + cancel test; redundant `contextLoads`) | Reviewed by the developer; tests deliberately left unchanged |
| 13 | "…review the assesment pdf once and see if we meet all the requirements…" | Requirement-by-requirement checklist; found the feature work uncommitted, a JDK 25 runnability risk, and this prompt record missing row 12 | Developer chose to commit and add row 12; kept JDK 25 |

## 2. Verification evidence

| # | Check | Result |
|---|---|---|
| V1 | Deliberately break back-to-back handling (`<` → `<=`) | Exactly the 2 back-to-back tests failed. A stale compiled class gave a false failure afterwards; `clean` builds were used from then on |
| V2 | Remove the per-provider locks | **51/76** concurrency runs failed, e.g. `Expected size: 1 but was: 3` (triple booking). Restored → pass |
| V3 | Probe of Jackson `Instant` parsing | `1764583200000` → year 57887; numbers accepted → **design changed** to string timestamps |
| V4 | Remove `@Valid` | Missing `start` gave a **500** → null handling added. Re-run: the service still blocked all invalid bookings |
| V5 | Real server + `curl` | 201 / 409 + conflicting id / back-to-back 201 / epoch 400 / `P000` 400 / cancel 200 / re-cancel 409 / rebook 201 |
| V6 | `./mvnw clean test` | **150/150 pass** |

Files: the developer created `pom.xml`, `.gitignore` and the application class. The AI generated `appointment/*`, `error/*`, the tests and the docs from the approved plan.

## 3. Developer's independent engineering assessment

**Booking invariant.** I reviewed the overlap condition and understood why half-open intervals are necessary. Using strict comparisons allows adjacent appointments without creating false conflicts.

**Concurrency.** I questioned the use of ConcurrentHashMap and asked for an explanation of why it was necessary. I understood that thread-safe storage alone does not make the complete check-and-book operation atomic.

**Persistence.** I agreed with in-memory storage because the assessment explicitly permits this fallback when no starter project is supplied.

**Cancellation.** I agreed with retaining cancelled appointments rather than deleting them, because this preserves their state while allowing their time slots to be reused.

**Scope.** I chose not to introduce client management, authentication, database persistence or other unrelated functionality.

These decisions were reviewed against the assessment requirements and implementation.

### Independent re-verification (25 November 2026)

- Ran `./mvnw clean test` locally.
- Result: BUILD SUCCESS — 150 tests run, 0 failures, 0 errors, 0 skipped.
- Ran `./mvnw package` locally.
- Result: BUILD SUCCESS — executable Spring Boot JAR generated at `target/scheduler-service-0.0.1-SNAPSHOT.jar`.
