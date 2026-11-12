package com.heliosdesk.servicelevels;

import java.time.*;
import java.util.*;

public record BusinessCalendar(
    String timezone, Map<Integer, List<Interval>> weekly, Set<LocalDate> holidays) {
  public record Interval(LocalTime start, LocalTime end) {}

  public BusinessCalendar {
    ZoneId.of(timezone);
    weekly = Map.copyOf(weekly);
    holidays = Set.copyOf(holidays);
    if (weekly.isEmpty()
        || weekly.size() > 7
        || weekly.values().stream().allMatch(List::isEmpty)
        || holidays.size() > 3660)
      throw new IllegalArgumentException("Calendar must have working hours");
    for (var day : weekly.entrySet()) {
      if (day.getKey() < 1 || day.getKey() > 7)
        throw new IllegalArgumentException("Invalid weekday");
      if (day.getValue().size() > 16)
        throw new IllegalArgumentException("Too many daily intervals");
      LocalTime last = null;
      for (var i : day.getValue().stream().sorted(Comparator.comparing(Interval::start)).toList()) {
        if (!i.start().isBefore(i.end()) || (last != null && i.start().isBefore(last)))
          throw new IllegalArgumentException("Invalid or overlapping intervals");
        last = i.end();
      }
    }
  }

  public Instant add(Instant start, long seconds) {
    if (seconds < 0) throw new IllegalArgumentException("Negative duration");
    if (seconds == 0) return start;
    ZoneId zone = ZoneId.of(timezone);
    LocalDate date = start.atZone(zone).toLocalDate();
    for (int day = 0; day < 3660; day++, date = date.plusDays(1)) {
      for (var i : intervals(date)) {
        Instant left = date.atTime(i.start()).atZone(zone).withEarlierOffsetAtOverlap().toInstant();
        Instant right = date.atTime(i.end()).atZone(zone).withLaterOffsetAtOverlap().toInstant();
        if (left.isBefore(start)) left = start;
        if (!left.isBefore(right)) continue;
        long available = Duration.between(left, right).getSeconds();
        if (seconds <= available) return left.plusSeconds(seconds);
        seconds -= available;
      }
    }
    throw new IllegalArgumentException("Calendar horizon exceeded");
  }

  public long between(Instant from, Instant to) {
    if (to.isBefore(from)) throw new IllegalArgumentException("Negative interval");
    ZoneId zone = ZoneId.of(timezone);
    long seconds = 0;
    LocalDate date = from.atZone(zone).toLocalDate(), end = to.atZone(zone).toLocalDate();
    if (end.isAfter(date.plusYears(10)))
      throw new IllegalArgumentException("Calendar horizon exceeded");
    for (; !date.isAfter(end); date = date.plusDays(1))
      for (var i : intervals(date)) {
        Instant left = date.atTime(i.start()).atZone(zone).withEarlierOffsetAtOverlap().toInstant();
        Instant right = date.atTime(i.end()).atZone(zone).withLaterOffsetAtOverlap().toInstant();
        if (left.isBefore(from)) left = from;
        if (right.isAfter(to)) right = to;
        if (left.isBefore(right)) seconds += Duration.between(left, right).getSeconds();
      }
    return seconds;
  }

  private List<Interval> intervals(LocalDate date) {
    return holidays.contains(date)
        ? List.of()
        : weekly.getOrDefault(date.getDayOfWeek().getValue(), List.of()).stream()
            .sorted(Comparator.comparing(Interval::start))
            .toList();
  }

  public static BusinessCalendar standard(String zone) {
    var days = new HashMap<Integer, List<Interval>>();
    for (int day = 1; day <= 5; day++)
      days.put(
          day,
          List.of(
              new Interval(LocalTime.of(9, 0), LocalTime.NOON),
              new Interval(LocalTime.of(13, 0), LocalTime.of(18, 0))));
    return new BusinessCalendar(zone, days, Set.of(LocalDate.of(2026, 12, 25)));
  }
}
