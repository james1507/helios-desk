package com.heliosdesk.identity;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.heliosdesk.platform.Problems;
import java.util.List;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.*;
import org.springframework.security.web.csrf.*;

@Configuration
public class SecurityConfig {
  @Bean
  PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder(12);
  }

  @Bean
  UserDetailsService users(JdbcTemplate db) {
    return email -> {
      var users =
          db.query(
              "SELECT id,email,password_hash FROM users WHERE email=? AND disabled_at IS NULL",
              (r, n) ->
                  User.withUsername(r.getString("id"))
                      .password(r.getString("password_hash"))
                      .authorities("USER")
                      .build(),
              email.toLowerCase(java.util.Locale.ROOT));
      if (users.isEmpty()) throw new UsernameNotFoundException("Invalid credentials");
      return users.getFirst();
    };
  }

  @Bean
  AuthenticationManager authenticationManager(UserDetailsService users, PasswordEncoder encoder) {
    var provider =
        new org.springframework.security.authentication.dao.DaoAuthenticationProvider(users);
    provider.setPasswordEncoder(encoder);
    return new ProviderManager(List.of(provider));
  }

  @Bean
  SecurityContextRepository contexts() {
    return new HttpSessionSecurityContextRepository();
  }

  @Bean
  SecurityFilterChain security(
      HttpSecurity http, ObjectMapper json, SecurityContextRepository contexts) throws Exception {
    var csrf = new HttpSessionCsrfTokenRepository();
    return http.securityContext(c -> c.securityContextRepository(contexts))
        .csrf(
            c ->
                c.csrfTokenRepository(csrf)
                    .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
        .authorizeHttpRequests(
            a ->
                a.dispatcherTypeMatchers(
                        jakarta.servlet.DispatcherType.ASYNC, jakarta.servlet.DispatcherType.ERROR)
                    .permitAll()
                    .requestMatchers(
                        "/api/v1/auth/csrf", "/api/v1/auth/login", "/actuator/health/**")
                    .permitAll()
                    .anyRequest()
                    .authenticated())
        .exceptionHandling(
            e ->
                e.authenticationEntryPoint(
                        (q, s, x) -> Problems.write(s, json, 401, "UNAUTHENTICATED"))
                    .accessDeniedHandler(
                        (q, s, x) ->
                            Problems.write(
                                s,
                                json,
                                403,
                                x instanceof CsrfException ? "CSRF_INVALID" : "FORBIDDEN")))
        .headers(
            h ->
                h.contentSecurityPolicy(
                        c -> c.policyDirectives("default-src 'none'; frame-ancestors 'none'"))
                    .frameOptions(f -> f.deny()))
        .requestCache(c -> c.disable())
        .formLogin(c -> c.disable())
        .httpBasic(c -> c.disable())
        .logout(c -> c.disable())
        .build();
  }
}
