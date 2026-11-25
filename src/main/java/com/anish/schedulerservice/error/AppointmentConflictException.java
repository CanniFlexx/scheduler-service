package com.anish.schedulerservice.error;

import java.util.UUID;

/** The requested interval overlaps an active appointment for the same provider. */
public class AppointmentConflictException extends RuntimeException {

    private final UUID conflictingAppointmentId;

    public AppointmentConflictException(String providerId, UUID conflictingAppointmentId) {
        super("Provider " + providerId + " already has an active appointment overlapping the requested time");
        this.conflictingAppointmentId = conflictingAppointmentId;
    }

    public UUID getConflictingAppointmentId() {
        return conflictingAppointmentId;
    }
}
