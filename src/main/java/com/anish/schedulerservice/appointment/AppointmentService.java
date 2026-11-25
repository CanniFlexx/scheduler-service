package com.anish.schedulerservice.appointment;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;

import com.anish.schedulerservice.error.AppointmentAlreadyCancelledException;
import com.anish.schedulerservice.error.AppointmentConflictException;
import com.anish.schedulerservice.error.AppointmentNotFoundException;
import com.anish.schedulerservice.error.InvalidAppointmentException;

/**
 * Enforces the booking invariant:
 * <b>a provider never has two ACTIVE appointments whose half-open intervals [start, end) overlap.</b>
 * <p>
 * Concurrency: the overlap check and the save must happen as one atomic step, otherwise two
 * concurrent requests can both pass the check and both save. Each provider gets its own lock
 * object, so bookings for one provider are serialized while different providers proceed in parallel.
 * Cancellation takes the same per-provider lock.
 */
@Service
public class AppointmentService {

    /** "P" followed by 3 digits, 001-999. Shared with the request DTO's @Pattern. */
    public static final String PROVIDER_ID_REGEX = "^P(?!000)\\d{3}$";
    private static final Pattern PROVIDER_ID_PATTERN = Pattern.compile(PROVIDER_ID_REGEX);

    private final InMemoryAppointmentRepository repository;
    private final ConcurrentHashMap<String, Object> providerLocks = new ConcurrentHashMap<>();

    public AppointmentService(InMemoryAppointmentRepository repository) {
        this.repository = repository;
    }

    public Appointment book(String providerId, Instant start, Instant end) {
        // All validation happens before taking the lock or touching the store,
        // so rejected input can never change stored bookings.
        validateBooking(providerId, start, end);

        synchronized (lockFor(providerId)) {
            for (Appointment existing : repository.findActiveByProviderId(providerId)) {
                if (existing.overlaps(start, end)) {
                    throw new AppointmentConflictException(providerId, existing.id());
                }
            }
            return repository.save(Appointment.book(providerId, start, end));
        }
    }

    public Appointment cancel(UUID id) {
        if (id == null) {
            throw new InvalidAppointmentException("appointment id is required");
        }
        // Read once outside the lock only to learn which provider's lock to take.
        Appointment found = repository.findById(id).orElseThrow(() -> new AppointmentNotFoundException(id));

        synchronized (lockFor(found.providerId())) {
            // Re-read under the lock: another thread may have cancelled it in the meantime.
            Appointment current = repository.findById(id).orElseThrow(() -> new AppointmentNotFoundException(id));
            if (!current.isActive()) {
                throw new AppointmentAlreadyCancelledException(id);
            }
            return repository.save(current.cancel());
        }
    }

    public Appointment get(UUID id) {
        if (id == null) {
            throw new InvalidAppointmentException("appointment id is required");
        }
        return repository.findById(id).orElseThrow(() -> new AppointmentNotFoundException(id));
    }

    private Object lockFor(String providerId) {
        // computeIfAbsent is atomic: every thread asking for the same provider gets the same lock object.
        return providerLocks.computeIfAbsent(providerId, k -> new Object());
    }

    private static void validateBooking(String providerId, Instant start, Instant end) {
        if (providerId == null || providerId.isBlank()) {
            throw new InvalidAppointmentException("providerId is required");
        }
        if (!PROVIDER_ID_PATTERN.matcher(providerId).matches()) {
            throw new InvalidAppointmentException(
                    "providerId must be 'P' followed by 3 digits from 001 to 999, e.g. P001");
        }
        if (start == null) {
            throw new InvalidAppointmentException("start is required");
        }
        if (end == null) {
            throw new InvalidAppointmentException("end is required");
        }
        if (!end.isAfter(start)) {
            throw new InvalidAppointmentException("end must be after start (duration must be positive)");
        }
    }
}
