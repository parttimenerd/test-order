package org.example.petclinic;

import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import static org.junit.jupiter.api.Assertions.*;

class AppointmentSchedulerTest {

    private final Vet   drAdams = new Vet("Dr. Adams", "dog", "cat");
    private final Pet   max     = new Pet("Max",   "dog", LocalDate.of(2020, 1, 1));
    private final Pet   basil   = new Pet("Basil", "cat", LocalDate.of(2019, 6, 1));
    private final LocalDate today = LocalDate.now();

    @Test
    void bookCreatesVisitForEligibleVet() {
        var scheduler = new AppointmentScheduler();
        var visit = scheduler.book(max, drAdams, today, "vaccination");
        assertNotNull(visit);
        assertSame(max, visit.getPet());
    }

    @Test
    void bookRejectsIncompatibleVet() {
        var drFish  = new Vet("Dr. Fish", "fish");
        var scheduler = new AppointmentScheduler();
        assertThrows(IllegalStateException.class,
            () -> scheduler.book(max, drFish, today, "checkup"));
    }

    @Test
    void getVisitsForReturnsPetVisitsOnly() {
        var scheduler = new AppointmentScheduler();
        scheduler.book(max,   drAdams, today,            "vaccination");
        scheduler.book(basil, drAdams, today.plusDays(1), "checkup");
        assertEquals(1, scheduler.getVisitsFor(max).size());
        assertEquals(1, scheduler.getVisitsFor(basil).size());
    }

    @Test
    void getUpcomingReturnsSortedFutureVisits() {
        var scheduler = new AppointmentScheduler();
        scheduler.book(max,   drAdams, today.plusDays(5), "follow-up");
        scheduler.book(basil, drAdams, today.plusDays(2), "dental");
        var upcoming = scheduler.getUpcoming(today);
        assertEquals(2, upcoming.size());
        assertTrue(upcoming.get(0).getDate().isBefore(upcoming.get(1).getDate()));
    }
}
