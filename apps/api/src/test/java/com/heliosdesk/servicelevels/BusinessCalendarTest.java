package com.heliosdesk.servicelevels;

import static org.assertj.core.api.Assertions.*;

import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class BusinessCalendarTest {
  final BusinessCalendar calendar = BusinessCalendar.standard("Asia/Ho_Chi_Minh");

  Instant at(String local) {
    return LocalDateTime.parse(local).atZone(ZoneId.of(calendar.timezone())).toInstant();
  }

  @Test
  void lunch() {
    assertThat(calendar.add(at("2026-11-16T11:30"), 3600)).isEqualTo(at("2026-11-16T13:30"));
  }

  @Test
  void weekend() {
    assertThat(calendar.add(at("2026-11-20T17:30"), 3600)).isEqualTo(at("2026-11-23T09:30"));
  }

  @Test
  void holiday() {
    var c =
        new BusinessCalendar(
            calendar.timezone(), calendar.weekly(), Set.of(LocalDate.of(2026, 11, 23)));
    assertThat(c.add(at("2026-11-20T17:30"), 3600)).isEqualTo(at("2026-11-24T09:30"));
  }

  @Test
  void pendingPause() {
    long consumed = calendar.between(at("2026-11-16T10:00"), at("2026-11-16T11:00"));
    assertThat(consumed).isEqualTo(3600);
    assertThat(calendar.add(at("2026-11-16T14:00"), 14400 - consumed))
        .isEqualTo(at("2026-11-16T17:00"));
  }

  @Test
  void lateReply() {
    assertThat(calendar.between(at("2026-11-16T11:30"), at("2026-11-16T13:40"))).isEqualTo(4200);
  }

  @Test
  void outsideHours() {
    assertThat(calendar.add(at("2026-11-21T22:00"), 1800)).isEqualTo(at("2026-11-23T09:30"));
  }

  @Test
  void dstSpring() {
    var c =
        new BusinessCalendar(
            "America/New_York",
            Map.of(
                7, List.of(new BusinessCalendar.Interval(LocalTime.of(1, 0), LocalTime.of(4, 0)))),
            Set.of());
    Instant start = Instant.parse("2026-03-08T06:00:00Z");
    assertThat(c.add(start, 7200)).isEqualTo(Instant.parse("2026-03-08T08:00:00Z"));
    assertThat(c.between(start, Instant.parse("2026-03-08T08:00:00Z"))).isEqualTo(7200);
  }

  @Test
  void dstFall() {
    var c =
        new BusinessCalendar(
            "America/New_York",
            Map.of(
                7, List.of(new BusinessCalendar.Interval(LocalTime.of(1, 0), LocalTime.of(3, 0)))),
            Set.of());
    assertThat(c.add(Instant.parse("2026-11-01T05:00:00Z"), 10800))
        .isEqualTo(Instant.parse("2026-11-01T08:00:00Z"));
  }

  @Test
  void invalidIntervals() {
    assertThatThrownBy(
            () ->
                new BusinessCalendar(
                    "UTC",
                    Map.of(
                        1, List.of(new BusinessCalendar.Interval(LocalTime.NOON, LocalTime.NOON))),
                    Set.of()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new BusinessCalendar(
                    "UTC",
                    Map.of(
                        1,
                        List.of(
                            new BusinessCalendar.Interval(LocalTime.of(9, 0), LocalTime.NOON),
                            new BusinessCalendar.Interval(
                                LocalTime.of(11, 0), LocalTime.of(13, 0)))),
                    Set.of()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void invalidZoneAndNegativeDuration() {
    assertThatThrownBy(() -> BusinessCalendar.standard("Mars/Orbit"))
        .isInstanceOf(DateTimeException.class);
    assertThatThrownBy(() -> calendar.add(Instant.now(), -1))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
