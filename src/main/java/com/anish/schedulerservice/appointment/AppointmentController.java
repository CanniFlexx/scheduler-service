package com.anish.schedulerservice.appointment;

import java.net.URI;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import com.anish.schedulerservice.error.InvalidAppointmentException;

@RestController
@RequestMapping("/appointments")
public class AppointmentController {

    private final AppointmentService service;

    public AppointmentController(AppointmentService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<AppointmentResponse> book(@Valid @RequestBody BookAppointmentRequest request) {
        Instant start = parseTimestamp("start", request.start());
        Instant end = parseTimestamp("end", request.end());

        Appointment booked = service.book(request.providerId(), start, end);

        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}").buildAndExpand(booked.id()).toUri();
        return ResponseEntity.created(location).body(AppointmentResponse.from(booked));
    }

    @GetMapping("/{id}")
    public AppointmentResponse get(@PathVariable UUID id) {
        return AppointmentResponse.from(service.get(id));
    }

    /** Cancellation is a state change (the record is kept), hence POST rather than DELETE. */
    @PostMapping("/{id}/cancel")
    public AppointmentResponse cancel(@PathVariable UUID id) {
        return AppointmentResponse.from(service.cancel(id));
    }

    /**
     * Accepts ISO-8601 timestamps with a zone designator ("Z" or an offset such as "+05:30");
     * offsets are normalized to UTC. Rejects zone-less timestamps, dates, and bare epoch numbers.
     */
    private static Instant parseTimestamp(String field, String value) {
        // @Valid already rejects null/blank; this keeps the method safe on its own (400, not an NPE/500).
        if (value == null || value.isBlank()) {
            throw new InvalidAppointmentException(field + " is required");
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            throw new InvalidAppointmentException(
                    field + " must be an ISO-8601 timestamp with a zone, e.g. 2026-12-01T10:00:00Z");
        }
    }
}
