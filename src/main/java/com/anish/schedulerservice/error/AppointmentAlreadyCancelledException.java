package com.anish.schedulerservice.error;

import java.util.UUID;

public class AppointmentAlreadyCancelledException extends RuntimeException {

    public AppointmentAlreadyCancelledException(UUID id) {
        super("Appointment " + id + " is already cancelled");
    }
}
