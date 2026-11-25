# Design Specification: Appointment Booking Slice

## Boundary

A Spring Boot REST service with three endpoints: book, get, cancel. Data is held in memory.

REST was chosen over a CLI because HTTP status codes express the outcomes directly (201/400/404/409), and Bean Validation was already on the classpath.

Out of scope: clients, authentication, a database, listing endpoints, a frontend.

```
AppointmentController          parse + validate HTTP input → call service → map to response
AppointmentService             booking invariant, validation, per-provider locking
InMemoryAppointmentRepository  ConcurrentHashMap<UUID, Appointment> (storage only)
```

- **`Appointment`** is an immutable record: `id, providerId, start, end, status (ACTIVE|CANCELLED)`.
- **Errors** are RFC 9457 `application/problem+json`.
- **A conflict response includes `conflictingAppointmentId`.**

## Booking invariant

> For any provider, no two ACTIVE appointments have overlapping half-open intervals `[start, end)`.

The overlap test is `a.start < b.end && b.start < a.end`. This one condition:
- rejects partial overlaps, containment and duplicates, and allows back-to-back bookings
- is checked only against the same provider's ACTIVE appointments, so other providers and cancelled appointments never conflict.

## Concurrency

**Problem:** requests run on parallel threads. "Check for overlap, then save" is two steps, so two requests for the same slot can both pass the check.

**Solution:** one lock per provider, held across the check and the save. Cancel takes the same lock and re-reads the appointment inside it, so two concurrent cancels can't both succeed.

```java
synchronized (providerLocks.computeIfAbsent(providerId, k -> new Object())) { check; save; }
```

- `ConcurrentHashMap` keeps each storage operation safe, and its `computeIfAbsent` gives every thread the same lock for a provider. On its own it does **not** make check-then-save atomic; the lock does that.
- Different providers never block each other. A single global lock was rejected because it serializes all providers.
- **Verified:** removing the locks made 51 of 76 concurrency test runs fail, including a triple booking. With the locks in place, all pass.

## Validation

All checks run before the lock is taken or anything is written:
- **`providerId` must match `^P(?!000)\d{3}$`.** This is enforced in both the request DTO and the service, using one shared constant.
- **`end` must be after `start`.**
- **Timestamps arrive as strings and are parsed with `Instant.parse`.** A probe showed that binding JSON directly to `Instant` read epoch-millis `1764583200000` as the year 57887.

## Key trade-offs

| Decision | Why | Cost |
|---|---|---|
| Per-provider lock | Correct; providers proceed in parallel | Works in one running instance only |
| Soft cancel (status change) | Keeps the record; frees the slot | Cancellation can't be undone; book again instead |
| `409` on second cancel | Explicit "clear result" | Retrying clients must treat 409 as already done |
| Validate in DTO **and** service | Service is safe without HTTP | Provider rule checked twice |
| No repository interface | Simplicity; one implementation | Swapping storage means editing one class |

## Limitations and follow-up risks

- **One running instance only.** Multiple instances would need a database exclusion constraint or a distributed lock.
- **Data is lost on restart** (in-memory).
- **No idempotency keys.** A retried booking can conflict with itself or create a duplicate.
- **The overlap check scans all appointments, O(n).** That's fine for a prototype.
- **No limits** on duration or how far in the past/future a booking can be.
- **The concurrent-cancel race is hard to provoke:** it was caught in only 4 of 25 runs with locking removed.
- **At most 999 providers** (ID format), and client double-booking isn't checked.

## Tests (150 executions)

- **`AppointmentServiceTest`:** booking, 6 overlap shapes, back-to-back, different providers, cancel/rebook/missing/double cancel, and 18 invalid inputs that leave the store unchanged.
- **`AppointmentConcurrencyTest`:** 20 threads released together. Same slot gives exactly 1 winner; overlapping slots leave no overlaps stored; concurrent cancels give exactly 1 success. Each scenario runs 25 times.
- **`AppointmentControllerTest`:** HTTP status codes, UTC output, offset normalization, error format, 24 invalid request bodies.
