package com.heliosdesk.tickets;

import com.heliosdesk.identity.*;
import com.heliosdesk.platform.ApiException;
import com.heliosdesk.tickets.TicketDtos.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/tickets")
public class TicketController {
  private final IdentityService identity;
  private final TicketService service;
  private final TicketQuery query;

  public TicketController(IdentityService identity, TicketService service, TicketQuery query) {
    this.identity = identity;
    this.service = service;
    this.query = query;
  }

  @GetMapping
  Page<View> list(
      HttpServletRequest req,
      @RequestParam(required = false) String q,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String priority,
      @RequestParam(required = false) String queue,
      @RequestParam(required = false) String source,
      @RequestParam(required = false) UUID teamId,
      @RequestParam(required = false) UUID assigneeId,
      @RequestParam(required = false) String cursor,
      @RequestParam(defaultValue = "25") int limit) {
    return query.list(
        identity.current(req),
        q,
        status,
        priority,
        queue,
        source,
        teamId,
        assigneeId,
        cursor,
        limit);
  }

  @GetMapping("/{id}")
  ResponseEntity<View> detail(@PathVariable UUID id, HttpServletRequest req) {
    return response(query.detail(identity.current(req), id), 200);
  }

  @PostMapping
  ResponseEntity<View> create(
      @Valid @RequestBody Create input,
      @RequestHeader("Idempotency-Key") String key,
      HttpServletRequest req) {
    return response(service.create(identity.current(req), input, key), 201);
  }

  @PostMapping("/{id}/messages")
  ResponseEntity<View> reply(
      @PathVariable UUID id,
      @Valid @RequestBody Reply input,
      @RequestHeader("If-Match") String match,
      @RequestHeader("Idempotency-Key") String key,
      HttpServletRequest req) {
    return response(service.reply(identity.current(req), id, input, version(match), key), 200);
  }

  @PostMapping("/{id}/claim")
  ResponseEntity<View> claim(
      @PathVariable UUID id,
      @RequestHeader("If-Match") String match,
      @RequestHeader("Idempotency-Key") String key,
      HttpServletRequest req) {
    return response(service.claim(identity.current(req), id, version(match), key), 200);
  }

  @PostMapping("/{id}/assign")
  ResponseEntity<View> assign(
      @PathVariable UUID id,
      @Valid @RequestBody Assign input,
      @RequestHeader("If-Match") String match,
      HttpServletRequest req) {
    return response(service.assign(identity.current(req), id, input, version(match)), 200);
  }

  @PostMapping("/{id}/transition")
  ResponseEntity<View> transition(
      @PathVariable UUID id,
      @Valid @RequestBody Transition input,
      @RequestHeader("If-Match") String match,
      HttpServletRequest req) {
    return response(service.transition(identity.current(req), id, input, version(match)), 200);
  }

  @PatchMapping("/{id}")
  ResponseEntity<View> edit(
      @PathVariable UUID id,
      @Valid @RequestBody Edit input,
      @RequestHeader("If-Match") String match,
      HttpServletRequest req) {
    return response(service.edit(identity.current(req), id, input, version(match)), 200);
  }

  @GetMapping("/{id}/messages")
  Page<Message> messages(
      @PathVariable UUID id,
      @RequestParam(required = false) String cursor,
      @RequestParam(defaultValue = "100") int limit,
      HttpServletRequest req) {
    return query.messages(identity.current(req), id, cursor, limit);
  }

  @GetMapping("/{id}/timeline")
  Page<Event> timeline(
      @PathVariable UUID id,
      @RequestParam(required = false) String cursor,
      @RequestParam(defaultValue = "100") int limit,
      HttpServletRequest req) {
    return query.timeline(identity.current(req), id, cursor, limit);
  }

  static long version(String match) {
    if (!match.matches("\"v[0-9]+\""))
      throw new ApiException(400, "INVALID_VERSION", "If-Match must use canonical format: \"v3\".");
    try {
      return Long.parseLong(match.substring(2, match.length() - 1));
    } catch (NumberFormatException e) {
      throw new ApiException(400, "INVALID_VERSION", "Version is too large.");
    }
  }

  private static ResponseEntity<View> response(View view, int status) {
    return ResponseEntity.status(status).eTag("\"v" + view.version() + "\"").body(view);
  }
}
