package com.heliosdesk.reporting;

import com.heliosdesk.identity.IdentityService;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/reports")
public class ReportingController {
  private final IdentityService identity;
  private final ReportingService reports;

  public ReportingController(IdentityService identity, ReportingService reports) {
    this.identity = identity;
    this.reports = reports;
  }

  @GetMapping("/overview")
  ReportingService.Overview overview(
      @RequestParam(required = false) Instant from,
      @RequestParam(required = false) Instant to,
      HttpServletRequest req) {
    return reports.overview(identity.current(req), from, to);
  }
}
