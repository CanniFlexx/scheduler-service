package com.anish.schedulerservice.appointment;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import com.anish.schedulerservice.error.AppointmentAlreadyCancelledException;
import com.anish.schedulerservice.error.AppointmentConflictException;

/**
 * Releases many threads at the same instant against one service to provoke the
 * check-then-save race. Without the per-provider lock these tests can observe double bookings.
 */
class AppointmentConcurrencyTest {

    private static final int THREADS = 20;
    private static final Instant T10 = Instant.parse("2026-12-01T10:00:00Z");
    private static final Instant T11 = Instant.parse("2026-12-01T11:00:00Z");

    private InMemoryAppointmentRepository repository;
    private AppointmentService service;

    @BeforeEach
    void setUp() {
        repository = new InMemoryAppointmentRepository();
        service = new AppointmentService(repository);
    }

    @RepeatedTest(25)
    void concurrentBookingsForSameSlotAllowExactlyOneWinner() throws Exception {
        List<Outcome> outcomes = runConcurrently(THREADS, i -> () -> service.book("P001", T10, T11));

        assertThat(outcomes).filteredOn(Outcome::succeeded).hasSize(1);
        assertThat(outcomes).filteredOn(o -> o.error() instanceof AppointmentConflictException).hasSize(THREADS - 1);
        assertThat(repository.findActiveByProviderId("P001")).hasSize(1);
    }

    @RepeatedTest(25)
    void concurrentOverlappingButDifferentIntervalsNeverLeaveOverlapsStored() throws Exception {
        // Each thread asks for a 60-minute slot shifted by i*5 minutes: every pair within 55 minutes overlaps.
        List<Outcome> outcomes = runConcurrently(THREADS, i -> () -> {
            Instant start = T10.plus(Duration.ofMinutes(5L * i));
            return service.book("P001", start, start.plus(Duration.ofHours(1)));
        });

        assertThat(outcomes).allSatisfy(o ->
                assertThat(o.succeeded() || o.error() instanceof AppointmentConflictException).isTrue());
        assertNoActiveOverlaps(repository.findActiveByProviderId("P001"));
    }

    @Test
    void concurrentBookingsForDifferentProvidersAllSucceed() throws Exception {
        List<Outcome> outcomes = runConcurrently(THREADS,
                i -> () -> service.book("P%03d".formatted(i + 1), T10, T11));

        assertThat(outcomes).allSatisfy(o -> assertThat(o.succeeded()).isTrue());
        assertThat(repository.findAll()).hasSize(THREADS);
    }

    @RepeatedTest(25)
    void concurrentCancelsOfSameAppointmentAllowExactlyOneSuccess() throws Exception {
        Appointment booked = service.book("P001", T10, T11);

        List<Outcome> outcomes = runConcurrently(THREADS, i -> () -> service.cancel(booked.id()));

        assertThat(outcomes).filteredOn(Outcome::succeeded).hasSize(1);
        assertThat(outcomes).filteredOn(o -> o.error() instanceof AppointmentAlreadyCancelledException)
                .hasSize(THREADS - 1);
        assertThat(repository.findById(booked.id()).orElseThrow().status()).isEqualTo(AppointmentStatus.CANCELLED);
    }

    private static void assertNoActiveOverlaps(List<Appointment> active) {
        for (int i = 0; i < active.size(); i++) {
            for (int j = i + 1; j < active.size(); j++) {
                Appointment a = active.get(i);
                Appointment b = active.get(j);
                assertThat(a.overlaps(b.start(), b.end()))
                        .as("active appointments %s and %s overlap", a, b)
                        .isFalse();
            }
        }
    }

    /**
     * Runs {@code threads} tasks so they all start at the same moment: each worker signals it is
     * ready, then waits on a shared start gate that is opened once every worker is waiting.
     */
    private static List<Outcome> runConcurrently(int threads, IntFunction<Callable<?>> taskFactory) throws Exception {
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch startGate = new CountDownLatch(1);
        List<Future<Outcome>> futures = new ArrayList<>();

        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            for (int i = 0; i < threads; i++) {
                Callable<?> task = taskFactory.apply(i);
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    startGate.await();
                    try {
                        return new Outcome(task.call(), null);
                    } catch (Exception e) {
                        return new Outcome(null, e);
                    }
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).as("all workers ready").isTrue();
            startGate.countDown();

            List<Outcome> outcomes = new ArrayList<>();
            for (Future<Outcome> f : futures) {
                outcomes.add(f.get(10, TimeUnit.SECONDS));
            }
            return outcomes;
        }
    }

    private record Outcome(Object value, Throwable error) {
        boolean succeeded() {
            return error == null;
        }
    }
}
