package com.anish.schedulerservice.error;

/** Input that can never form a valid appointment (bad provider id, missing or inverted times). */
public class InvalidAppointmentException extends RuntimeException {

    public InvalidAppointmentException(String message) {
        super(message);
    }
}
