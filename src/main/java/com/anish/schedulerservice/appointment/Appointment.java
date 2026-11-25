package com.anish.schedulerservice.appointment;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * An immutable appointment for a provider over the half-open UTC interval [start, end).
 * <p>
 * State changes (cancellation) produce a new instance rather than mutating this one, so a stored
 * appointment can only change by the service saving a replacement.
 */
public record Appointment(UUID id, String providerId, Instant start, Instant end, AppointmentStatus status) {

    public Appointment {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(providerId, "providerId");
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(end, "end");
        Objects.requireNonNull(status, "status");
    }

    /** Creates a new active appointment with a generated id. */
    public static Appointment book(String providerId, Instant start, Instant end) {
        return new Appointment(UUID.randomUUID(), providerId, start, end, AppointmentStatus.ACTIVE);
    }

    public boolean isActive() {
        return status == AppointmentStatus.ACTIVE;
    }

    /** Returns a cancelled copy of this appointment. */
    public Appointment cancel() {
        return new Appointment(id, providerId, start, end, AppointmentStatus.CANCELLED);
    }

    /**
     * Whether this appointment's interval overlaps [otherStart, otherEnd).
     * Intervals are half-open, so back-to-back intervals (this.end == otherStart) do not overlap.
     */
    public boolean overlaps(Instant otherStart, Instant otherEnd) {
        return start.isBefore(otherEnd) && otherStart.isBefore(end);
    }
}
