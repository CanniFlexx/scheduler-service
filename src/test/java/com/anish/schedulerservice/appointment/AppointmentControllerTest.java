package com.anish.schedulerservice.appointment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.jayway.jsonpath.JsonPath;

/**
 * HTTP-level tests through the full Spring context (real service and repository).
 * The booking rules themselves are covered exhaustively in AppointmentServiceTest; these tests
 * prove the wiring: status codes, request parsing, validation and error body shape.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class AppointmentControllerTest {

    private static final String PROBLEM_JSON = "application/problem+json";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private InMemoryAppointmentRepository repository;

    // ---- booking ----

    @Test
    void validBookingReturns201WithLocationAndUtcBody() throws Exception {
        book("P001", "2026-12-01T10:00:00Z", "2026-12-01T11:00:00Z")
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", org.hamcrest.Matchers.matchesPattern(".*/appointments/[0-9a-f-]{36}$")))
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.providerId").value("P001"))
                .andExpect(jsonPath("$.start").value("2026-12-01T10:00:00Z"))
                .andExpect(jsonPath("$.end").value("2026-12-01T11:00:00Z"))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void offsetTimestampsAreNormalizedToUtc() throws Exception {
        book("P001", "2026-12-01T15:30:00+05:30", "2026-12-01T16:30:00+05:30")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.start").value("2026-12-01T10:00:00Z"))
                .andExpect(jsonPath("$.end").value("2026-12-01T11:00:00Z"));
    }

    @Test
    void overlapExpressedInADifferentOffsetIsStillAConflict() throws Exception {
        String existingId = idOf(book("P001", "2026-12-01T10:00:00Z", "2026-12-01T11:00:00Z"));

        book("P001", "2026-12-01T16:00:00+05:30", "2026-12-01T17:00:00+05:30") // 10:30Z-11:30Z
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.conflictingAppointmentId").value(existingId));
    }

    @Test
    void sameProviderOverlapReturns409WithProblemDetail() throws Exception {
        String existingId = idOf(book("P001", "2026-12-01T10:00:00Z", "2026-12-01T11:00:00Z"));
        List<Appointment> before = repository.findAll();

        book("P001", "2026-12-01T10:30:00Z", "2026-12-01T11:30:00Z")
                .andExpect(status().isConflict())
                .andExpect(header().string("Content-Type", PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.title").value("Appointment conflict"))
                .andExpect(jsonPath("$.conflictingAppointmentId").value(existingId));
        assertThat(repository.findAll()).containsExactlyInAnyOrderElementsOf(before);
    }

    @Test
    void adjacentBookingReturns201() throws Exception {
        book("P001", "2026-12-01T10:00:00Z", "2026-12-01T11:00:00Z").andExpect(status().isCreated());
        book("P001", "2026-12-01T11:00:00Z", "2026-12-01T12:00:00Z").andExpect(status().isCreated());
    }

    @Test
    void differentProvidersSameSlotBothReturn201() throws Exception {
        book("P001", "2026-12-01T10:00:00Z", "2026-12-01T11:00:00Z").andExpect(status().isCreated());
        book("P002", "2026-12-01T10:00:00Z", "2026-12-01T11:00:00Z").andExpect(status().isCreated());
    }

    // ---- get ----

    @Test
    void getReturnsStoredAppointment() throws Exception {
        String id = idOf(book("P001", "2026-12-01T10:00:00Z", "2026-12-01T11:00:00Z"));

        mvc.perform(get("/appointments/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void getUnknownIdReturns404() throws Exception {
        mvc.perform(get("/appointments/{id}", UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(header().string("Content-Type", PROBLEM_JSON));
    }

    @Test
    void getMalformedIdReturns400() throws Exception {
        mvc.perform(get("/appointments/not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(header().string("Content-Type", PROBLEM_JSON));
    }

    // ---- cancel ----

    @Test
    void cancelReturns200AndFreesTheSlot() throws Exception {
        String id = idOf(book("P001", "2026-12-01T10:00:00Z", "2026-12-01T11:00:00Z"));

        mvc.perform(post("/appointments/{id}/cancel", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        book("P001", "2026-12-01T10:00:00Z", "2026-12-01T11:00:00Z").andExpect(status().isCreated());
    }

    @Test
    void cancellingTwiceReturns409() throws Exception {
        String id = idOf(book("P001", "2026-12-01T10:00:00Z", "2026-12-01T11:00:00Z"));
        mvc.perform(post("/appointments/{id}/cancel", id)).andExpect(status().isOk());

        mvc.perform(post("/appointments/{id}/cancel", id))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Appointment already cancelled"))
                .andExpect(jsonPath("$.detail", endsWith("is already cancelled")));
    }

    @Test
    void cancellingUnknownIdReturns404() throws Exception {
        mvc.perform(post("/appointments/{id}/cancel", UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Appointment not found"));
    }

    @Test
    void cancellingMalformedIdReturns400() throws Exception {
        mvc.perform(post("/appointments/not-a-uuid/cancel"))
                .andExpect(status().isBadRequest());
    }

    // ---- invalid input ----

    @Test
    void missingFieldsAreAllReportedTogether() throws Exception {
        postJson("{}")
                .andExpect(status().isBadRequest())
                .andExpect(header().string("Content-Type", PROBLEM_JSON))
                .andExpect(jsonPath("$.errors", hasItem("providerId: providerId is required")))
                .andExpect(jsonPath("$.errors", hasItem("start: start is required")))
                .andExpect(jsonPath("$.errors", hasItem("end: end is required")));
    }

    static Stream<Arguments> invalidBodies() {
        String s = "2026-12-01T12:00:00Z";
        String e = "2026-12-01T13:00:00Z";
        return Stream.of(
                Arguments.of("missing providerId", "{\"start\":\"%s\",\"end\":\"%s\"}".formatted(s, e)),
                Arguments.of("null providerId", json(null, s, e)),
                Arguments.of("blank providerId", json("", s, e)),
                Arguments.of("providerId P000", json("P000", s, e)),
                Arguments.of("providerId too short", json("P01", s, e)),
                Arguments.of("providerId too long", json("P0001", s, e)),
                Arguments.of("providerId lowercase", json("p001", s, e)),
                Arguments.of("providerId wrong prefix", json("C001", s, e)),
                Arguments.of("providerId padded", json(" P001", s, e)),
                Arguments.of("providerId as number", "{\"providerId\":1,\"start\":\"%s\",\"end\":\"%s\"}".formatted(s, e)),
                Arguments.of("missing start", "{\"providerId\":\"P001\",\"end\":\"%s\"}".formatted(e)),
                Arguments.of("missing end", "{\"providerId\":\"P001\",\"start\":\"%s\"}".formatted(s)),
                Arguments.of("blank start", json("P001", "", e)),
                Arguments.of("garbage start", json("P001", "not-a-date", e)),
                Arguments.of("start without zone", json("P001", "2026-12-01T12:00:00", e)),
                Arguments.of("start date only", json("P001", "2026-12-01", e)),
                Arguments.of("impossible date", json("P001", "2026-02-30T12:00:00Z", e)),
                Arguments.of("epoch-seconds start", "{\"providerId\":\"P001\",\"start\":1796126400,\"end\":\"%s\"}".formatted(e)),
                Arguments.of("epoch-millis end", "{\"providerId\":\"P001\",\"start\":\"%s\",\"end\":1796130000000}".formatted(s)),
                Arguments.of("zero duration", json("P001", s, s)),
                Arguments.of("negative duration", json("P001", e, s)),
                Arguments.of("malformed JSON", "{\"providerId\":"),
                Arguments.of("empty body", ""),
                Arguments.of("JSON array", "[]"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidBodies")
    void invalidInputReturns400AndLeavesStoreUnchanged(String description, String body) throws Exception {
        book("P001", "2026-12-01T09:00:00Z", "2026-12-01T10:00:00Z").andExpect(status().isCreated());
        List<Appointment> before = repository.findAll();

        postJson(body)
                .andExpect(status().isBadRequest())
                .andExpect(header().string("Content-Type", PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(400));
        assertThat(repository.findAll()).containsExactlyInAnyOrderElementsOf(before);
    }

    // ---- helpers ----

    private ResultActions book(String providerId, String start, String end) throws Exception {
        return postJson(json(providerId, start, end));
    }

    private ResultActions postJson(String body) throws Exception {
        return mvc.perform(post("/appointments").contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private static String idOf(ResultActions result) throws Exception {
        return JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$.id");
    }

    private static String json(String providerId, String start, String end) {
        return "{\"providerId\":%s,\"start\":%s,\"end\":%s}".formatted(quote(providerId), quote(start), quote(end));
    }

    private static String quote(String value) {
        return value == null ? "null" : "\"" + value + "\"";
    }
}
