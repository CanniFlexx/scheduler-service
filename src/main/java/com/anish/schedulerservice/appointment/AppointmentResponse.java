package com.anish.schedulerservice.appointment;

import java.time.Instant;
import java.util.UUID;

/** API view of an appointment. Timestamps serialize as ISO-8601 UTC, e.g. "2026-12-01T10:00:00Z". */
public record AppointmentResponse(UUID id, String providerId, Instant start, Instant end, AppointmentStatus status) {

    static AppointmentResponse from(Appointment appointment) {
        return new AppointmentResponse(appointment.id(), appointment.providerId(),
                appointment.start(), appointment.end(), appointment.status());
    }
}
