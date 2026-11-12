package com.heliosdesk.identity;

import com.heliosdesk.platform.*;
import jakarta.servlet.http.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.*;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class AuthController {
  private final AuthenticationManager auth;
  private final SecurityContextRepository contexts;
  private final IdentityService identity;
  private final JdbcTemplate db;

  public AuthController(
      AuthenticationManager auth,
      SecurityContextRepository contexts,
      IdentityService identity,
      JdbcTemplate db) {
    this.auth = auth;
    this.contexts = contexts;
    this.identity = identity;
    this.db = db;
  }

  public record Login(
      @NotBlank @Email @Size(max = 254) String email, @NotBlank @Size(max = 128) String password) {}

  public record Switch(@NotBlank @Size(max = 60) String slug) {}

  public record Session(Actor actor, List<Map<String, Object>> memberships) {}

  @GetMapping("/auth/csrf")
  Map<String, String> csrf(CsrfToken token) {
    return Map.of("token", token.getToken(), "headerName", token.getHeaderName());
  }

  @PostMapping("/auth/login")
  Session login(@Valid @RequestBody Login body, HttpServletRequest req, HttpServletResponse res) {
    String scope = Json.hash(body.email().toLowerCase(Locale.ROOT));
    int attempts =
        db.queryForObject(
            "SELECT count(*) FROM auth_attempts WHERE scope=? AND attempted_at>now()-interval '15 minutes'",
            Integer.class,
            scope);
    if (attempts >= 20)
      throw new ApiException(429, "RATE_LIMITED", "Too many attempts. Try again in 15 minutes.");
    db.update("INSERT INTO auth_attempts(scope) VALUES (?)", scope);
    try {
      var result =
          auth.authenticate(
              UsernamePasswordAuthenticationToken.unauthenticated(body.email(), body.password()));
      req.getSession();
      req.changeSessionId();
      req.getSession().removeAttribute("activeTenant");
      var context = SecurityContextHolder.createEmptyContext();
      context.setAuthentication(result);
      SecurityContextHolder.setContext(context);
      contexts.saveContext(context, req, res);
      new HttpSessionCsrfTokenRepository().saveToken(null, req, res);
      Actor actor = identity.current(req);
      return new Session(actor, identity.memberships(actor.userId()));
    } catch (AuthenticationException e) {
      throw new ApiException(401, "INVALID_CREDENTIALS", "Email or password incorrect.");
    }
  }

  @GetMapping("/auth/me")
  Session me(HttpServletRequest req) {
    Actor a = identity.current(req);
    return new Session(a, identity.memberships(a.userId()));
  }

  @PostMapping("/auth/logout")
  void logout(HttpServletRequest req) {
    req.getSession().invalidate();
    SecurityContextHolder.clearContext();
  }

  @GetMapping("/tenants")
  List<Map<String, Object>> tenants(HttpServletRequest req) {
    return identity.memberships(identity.current(req).userId());
  }

  @PostMapping("/tenants/switch")
  Session change(@Valid @RequestBody Switch body, HttpServletRequest req) {
    identity.switchTenant(identity.current(req), body.slug(), req);
    return me(req);
  }
}
