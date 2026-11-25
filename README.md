# scheduler-service

Conflict-safe appointment booking and cancellation: a small Spring Boot REST service with an in-memory store. No starter project was supplied, so this follows the brief's fallback.

| Deliverable | Where |
|---|---|
| Code and tests | `src/main`, `src/test` |
| Design specification and limitations | [`docs/DESIGN.md`](docs/DESIGN.md) |
| AI prompt and usage record | [`docs/AI_USAGE.md`](docs/AI_USAGE.md) |
| Setup, run, assumptions and invariant | this file |

## Setup and tests

Requires **JDK 25**. Maven runs through the wrapper, so nothing else needs installing.

```bash
./mvnw clean test                               # all tests: 150 executions, a few seconds
./mvnw test -Dtest=AppointmentServiceTest       # booking rules
./mvnw test -Dtest=AppointmentConcurrencyTest   # parallel booking/cancel races
./mvnw test -Dtest=AppointmentControllerTest    # HTTP layer (MockMvc)
./mvnw spring-boot:run                          # serve on http://localhost:8080

./mvnw package                                  # or build an executable JAR and run it
java -jar target/scheduler-service-0.0.1-SNAPSHOT.jar
```

## API

| Request | Success | Errors |
|---|---|---|
| `POST /appointments` `{"providerId","start","end"}` | `201` + body | `400` invalid, `409` overlap |
| `GET /appointments/{id}` | `200` | `400`, `404` |
| `POST /appointments/{id}/cancel` | `200` (`CANCELLED`) | `400`, `404`, `409` already cancelled |

```bash
curl -X POST localhost:8080/appointments -H 'Content-Type: application/json' \
  -d '{"providerId":"P001","start":"2026-12-01T10:00:00Z","end":"2026-12-01T11:00:00Z"}'
```

## Booking invariant

> A provider never has two **ACTIVE** appointments whose half-open intervals `[start, end)` overlap.

`a.start < b.end && b.start < a.end` means overlap. Because the comparisons are strict, back-to-back bookings (10:00–11:00 and 11:00–12:00) are allowed. Cancelled appointments don't block time.

## Assumptions

- **UTC:** timestamps are ISO-8601 with a zone (`Z` or an offset). Offsets are converted to UTC. Bare numbers and zone-less values are rejected.
- **Interval:** given as `start` + `end`. "Non-positive duration" means `end <= start`.
- **`providerId`:** `P` + 3 digits, `P001`–`P999`. There is no provider registry.
- **Past bookings** are allowed. There is no client id.
- **Cancel:** the record is kept with status `CANCELLED`. A second cancel returns `409`.
- **Rejected requests** never change stored bookings.
