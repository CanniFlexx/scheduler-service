package com.anish.schedulerservice.appointment;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Repository;

/**
 * Thread-safe in-memory store. Data is lost on restart.
 * <p>
 * Individual operations are safe to call concurrently, but this class does NOT make
 * "check for overlap, then save" atomic — that is the service's job (per-provider locking).
 */
@Repository
public class InMemoryAppointmentRepository {

    private final ConcurrentHashMap<UUID, Appointment> appointments = new ConcurrentHashMap<>();

    /** Inserts or replaces the appointment with the same id. */
    public Appointment save(Appointment appointment) {
        appointments.put(appointment.id(), appointment);
        return appointment;
    }

    public Optional<Appointment> findById(UUID id) {
        return Optional.ofNullable(appointments.get(id));
    }

    /** Active appointments for one provider. Linear scan — acceptable for an in-memory prototype. */
    public List<Appointment> findActiveByProviderId(String providerId) {
        return appointments.values().stream()
                .filter(a -> a.providerId().equals(providerId))
                .filter(Appointment::isActive)
                .toList();
    }

    /** Snapshot of all appointments; used by tests to assert the store is unchanged. */
    public List<Appointment> findAll() {
        return List.copyOf(appointments.values());
    }
}
