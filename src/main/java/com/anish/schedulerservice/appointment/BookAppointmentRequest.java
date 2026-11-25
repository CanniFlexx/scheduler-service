package com.anish.schedulerservice.appointment;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * Booking request body.
 * <p>
 * {@code start} and {@code end} are deliberately Strings, parsed with {@link java.time.Instant#parse}
 * in the controller. Binding them straight to {@code Instant} would let Jackson accept bare numbers as
 * epoch values (e.g. epoch-millis 1764583200000 is read as epoch-seconds, i.e. year 57887) instead of
 * rejecting them as invalid timestamps.
 */
public record BookAppointmentRequest(

        @NotBlank(message = "providerId is required")
        @Pattern(regexp = AppointmentService.PROVIDER_ID_REGEX,
                message = "providerId must be 'P' followed by 3 digits from 001 to 999, e.g. P001")
        String providerId,

        @NotBlank(message = "start is required")
        String start,

        @NotBlank(message = "end is required")
        String end) {
}
