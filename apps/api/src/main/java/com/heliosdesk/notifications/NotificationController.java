package com.heliosdesk.notifications;

import com.heliosdesk.identity.*;
import com.heliosdesk.platform.ApiException;
import com.heliosdesk.tickets.TicketDtos.Page;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/v1")
public class NotificationController {
  private final IdentityService identity;
  private final NotificationService notifications;
  private final org.springframework.session.SessionRepository<
          ? extends org.springframework.session.Session>
      sessions;

  public NotificationController(
      IdentityService identity,
      NotificationService notifications,
      org.springframework.session.SessionRepository<? extends org.springframework.session.Session>
          sessions) {
    this.identity = identity;
    this.notifications = notifications;
    this.sessions = sessions;
  }

  @GetMapping("/notifications")
  Page<NotificationService.Notice> list(
      @RequestParam(defaultValue = "-1") long after,
      @RequestParam(defaultValue = "50") int limit,
      HttpServletRequest req) {
    Actor a = identity.current(req);
    return notifications.feed(
        a, after == -1 ? Math.max(0, notifications.latest(a) - limit) : after, limit);
  }

  @GetMapping("/notifications/unread-count")
  Map<String, Long> unread(HttpServletRequest req) {
    return Map.of("count", notifications.unread(identity.current(req)));
  }

  @PatchMapping("/notifications/{id}/read")
  void read(@PathVariable UUID id, HttpServletRequest req) {
    notifications.read(identity.current(req), id);
  }

  @GetMapping(value = "/events/stream", produces = "text/event-stream")
  SseEmitter stream(
      @RequestHeader(value = "Last-Event-ID", required = false) String header,
      @RequestParam(defaultValue = "0") long after,
      HttpServletRequest req,
      HttpServletResponse response) {
    Actor a = identity.current(req);
    response.setHeader("Cache-Control", "no-cache, no-transform");
    response.setHeader("X-Accel-Buffering", "no");
    long cursor = after;
    try {
      if (header != null) cursor = Long.parseLong(header);
      if (cursor < 0) throw new NumberFormatException();
    } catch (NumberFormatException e) {
      throw new ApiException(400, "INVALID_CURSOR", "Invalid event cursor.");
    }
    var emitter = new SseEmitter(25_000L);
    var closed = new AtomicBoolean();
    emitter.onCompletion(() -> closed.set(true));
    emitter.onTimeout(() -> closed.set(true));
    emitter.onError(error -> closed.set(true));
    final long initial = cursor;
    final String sessionId = req.getSession().getId();
    Thread.startVirtualThread(
        () -> {
          long last = initial;
          long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(24);
          try {
            while (!closed.get() && System.nanoTime() < end) {
              if (!notifications.active(a)) {
                emitter.complete();
                return;
              }
              var session = sessions.findById(sessionId);
              if (session == null) {
                emitter.complete();
                return;
              }
              Object tenant = session.getAttribute("activeTenant");
              if (!a.tenantId().equals(tenant)) {
                emitter.complete();
                return;
              }
              var page = notifications.feed(a, last, 100);
              for (var notice : page.items()) {
                emitter.send(
                    SseEmitter.event()
                        .name("ticket.updated")
                        .id(Long.toString(notice.sequence()))
                        .data(notice));
                last = notice.sequence();
              }
              emitter.send(SseEmitter.event().comment("heartbeat"));
              Thread.sleep(1500);
            }
            if (!closed.get()) emitter.complete();
          } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            if (!closed.get() && !(e instanceof java.io.IOException)) emitter.completeWithError(e);
          }
        });
    return emitter;
  }
}
