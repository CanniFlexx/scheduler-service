package com.anish.schedulerservice.error;

import java.util.UUID;

public class AppointmentNotFoundException extends RuntimeException {

    public AppointmentNotFoundException(UUID id) {
        super("Appointment " + id + " not found");
    }
}
