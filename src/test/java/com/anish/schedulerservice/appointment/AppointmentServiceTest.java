package com.anish.schedulerservice.appointment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.anish.schedulerservice.error.AppointmentAlreadyCancelledException;
import com.anish.schedulerservice.error.AppointmentConflictException;
import com.anish.schedulerservice.error.AppointmentNotFoundException;
import com.anish.schedulerservice.error.InvalidAppointmentException;

class AppointmentServiceTest {

    private static final Instant T09 = Instant.parse("2026-12-01T09:00:00Z");
    private static final Instant T10 = Instant.parse("2026-12-01T10:00:00Z");
    private static final Instant T1030 = Instant.parse("2026-12-01T10:30:00Z");
    private static final Instant T11 = Instant.parse("2026-12-01T11:00:00Z");
    private static final Instant T12 = Instant.parse("2026-12-01T12:00:00Z");

    private InMemoryAppointmentRepository repository;
    private AppointmentService service;

    @BeforeEach
    void setUp() {
        repository = new InMemoryAppointmentRepository();
        service = new AppointmentService(repository);
    }

    @Nested
    class Booking {

        @Test
        void validBookingIsStoredAsActive() {
            Appointment booked = service.book("P001", T10, T11);

            assertThat(booked.id()).isNotNull();
            assertThat(booked.providerId()).isEqualTo("P001");
            assertThat(booked.start()).isEqualTo(T10);
            assertThat(booked.end()).isEqualTo(T11);
            assertThat(booked.status()).isEqualTo(AppointmentStatus.ACTIVE);
            assertThat(repository.findById(booked.id())).contains(booked);
        }

        @Test
        void providerIdBoundariesAreAccepted() {
            assertThat(service.book("P001", T10, T11).isActive()).isTrue();
            assertThat(service.book("P999", T10, T11).isActive()).isTrue();
        }
    }

    @Nested
    class Overlap {

        static Stream<Arguments> overlappingIntervals() {
            return Stream.of(
                    Arguments.of("exact duplicate", T10, T11),
                    Arguments.of("overlaps start", T09, Instant.parse("2026-12-01T10:30:00Z")),
                    Arguments.of("overlaps end", T1030, T12),
                    Arguments.of("contained within", Instant.parse("2026-12-01T10:15:00Z"), T1030),
                    Arguments.of("contains existing", T09, T12),
                    Arguments.of("overlaps by one nanosecond", T09, T10.plusNanos(1)));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("overlappingIntervals")
        void sameProviderOverlapIsRejectedAndStoreUnchanged(String description, Instant start, Instant end) {
            Appointment existing = service.book("P001", T10, T11);
            List<Appointment> before = repository.findAll();

            assertThatThrownBy(() -> service.book("P001", start, end))
                    .isInstanceOf(AppointmentConflictException.class)
                    .satisfies(e -> assertThat(((AppointmentConflictException) e).getConflictingAppointmentId())
                            .isEqualTo(existing.id()));
            assertThat(repository.findAll()).containsExactlyInAnyOrderElementsOf(before);
        }

        @Test
        void differentProvidersDoNotConflict() {
            Appointment a = service.book("P001", T10, T11);
            Appointment b = service.book("P002", T10, T11);

            assertThat(a.isActive()).isTrue();
            assertThat(b.isActive()).isTrue();
            assertThat(repository.findAll()).hasSize(2);
        }
    }

    @Nested
    class Adjacency {

        @Test
        void bookingThatStartsWhenAnotherEndsIsAllowed() {
            service.book("P001", T10, T11);

            assertThat(service.book("P001", T11, T12).isActive()).isTrue();
        }

        @Test
        void bookingThatEndsWhenAnotherStartsIsAllowed() {
            service.book("P001", T10, T11);

            assertThat(service.book("P001", T09, T10).isActive()).isTrue();
        }
    }

    @Nested
    class Cancellation {

        @Test
        void cancelMarksAppointmentCancelled() {
            Appointment booked = service.book("P001", T10, T11);

            Appointment cancelled = service.cancel(booked.id());

            assertThat(cancelled.status()).isEqualTo(AppointmentStatus.CANCELLED);
            assertThat(cancelled.id()).isEqualTo(booked.id());
            assertThat(repository.findById(booked.id())).contains(cancelled);
        }

        @Test
        void cancelledAppointmentNoLongerBlocksItsTime() {
            Appointment booked = service.book("P001", T10, T11);
            service.cancel(booked.id());

            Appointment rebooked = service.book("P001", T10, T11);

            assertThat(rebooked.isActive()).isTrue();
            assertThat(rebooked.id()).isNotEqualTo(booked.id());
        }

        @Test
        void cancellingMissingAppointmentIsNotFound() {
            UUID unknown = UUID.randomUUID();

            assertThatThrownBy(() -> service.cancel(unknown))
                    .isInstanceOf(AppointmentNotFoundException.class)
                    .hasMessageContaining(unknown.toString());
        }

        @Test
        void cancellingTwiceIsRejectedAndStateUnchanged() {
            Appointment booked = service.book("P001", T10, T11);
            Appointment cancelled = service.cancel(booked.id());

            assertThatThrownBy(() -> service.cancel(booked.id()))
                    .isInstanceOf(AppointmentAlreadyCancelledException.class);
            assertThat(repository.findById(booked.id())).contains(cancelled);
        }

        @Test
        void cancellingOldIdAfterRebookDoesNotAffectNewBooking() {
            Appointment original = service.book("P001", T10, T11);
            service.cancel(original.id());
            Appointment rebooked = service.book("P001", T10, T11);

            assertThatThrownBy(() -> service.cancel(original.id()))
                    .isInstanceOf(AppointmentAlreadyCancelledException.class);
            assertThat(repository.findById(rebooked.id()).orElseThrow().isActive()).isTrue();
        }

        @Test
        void cancellingWithNullIdIsInvalid() {
            assertThatThrownBy(() -> service.cancel(null)).isInstanceOf(InvalidAppointmentException.class);
        }
    }

    @Nested
    class InvalidInput {

        @ParameterizedTest(name = "providerId=\"{0}\"")
        @ValueSource(strings = {"", " ", "P000", "P1", "P01", "P0001", "p001", "P", "001", "PABC", " P001", "P001 ", "C001"})
        void malformedProviderIdIsRejectedAndStoreUnchanged(String providerId) {
            service.book("P001", T10, T11);
            List<Appointment> before = repository.findAll();

            assertThatThrownBy(() -> service.book(providerId, T12, T12.plusSeconds(3600)))
                    .isInstanceOf(InvalidAppointmentException.class);
            assertThat(repository.findAll()).containsExactlyInAnyOrderElementsOf(before);
        }

        static Stream<Arguments> invalidBookings() {
            return Stream.of(
                    Arguments.of("null providerId", null, T10, T11),
                    Arguments.of("null start", "P001", null, T11),
                    Arguments.of("null end", "P001", T10, null),
                    Arguments.of("zero duration", "P001", T10, T10),
                    Arguments.of("negative duration", "P001", T11, T10));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("invalidBookings")
        void invalidBookingIsRejectedAndStoreUnchanged(String description, String providerId, Instant start, Instant end) {
            service.book("P001", T09, T10);
            List<Appointment> before = repository.findAll();

            assertThatThrownBy(() -> service.book(providerId, start, end))
                    .isInstanceOf(InvalidAppointmentException.class);
            assertThat(repository.findAll()).containsExactlyInAnyOrderElementsOf(before);
        }
    }
}
