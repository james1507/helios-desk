package com.heliosdesk.platform;

import com.heliosdesk.identity.Actor;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
public class Idempotency {
  private final JdbcTemplate db;
  private final Json json;

  public Idempotency(JdbcTemplate db, Json json) {
    this.db = db;
    this.json = json;
  }

  public <T> T run(
      Actor a, String operation, String key, Object input, Class<T> type, Supplier<T> work) {
    if (!TransactionSynchronizationManager.isActualTransactionActive())
      throw new IllegalStateException("Idempotency requires transaction");
    try {
      if (!UUID.fromString(key).toString().equals(key.toLowerCase(java.util.Locale.ROOT)))
        throw new IllegalArgumentException();
    } catch (IllegalArgumentException | NullPointerException e) {
      throw new ApiException(400, "IDEMPOTENCY_KEY_REQUIRED", "Use a random UUID Idempotency-Key.");
    }
    String keyHash = Json.hash(key), fingerprint = Json.hash(json.write(input));
    String scope = a.tenantId() + ":" + a.membershipId() + ":" + operation + ":" + keyHash;
    db.query("SELECT pg_advisory_xact_lock(hashtextextended(?,0))", r -> {}, scope);
    var old =
        db.queryForList(
            "SELECT request_hash,safe_response::text FROM idempotency_records WHERE tenant_id=? AND actor_scope=? AND operation=? AND key_hash=?",
            a.tenantId(),
            a.membershipId(),
            operation,
            keyHash);
    if (!old.isEmpty()) {
      if (!old.getFirst().get("request_hash").equals(fingerprint))
        throw new ApiException(
            409, "IDEMPOTENCY_CONFLICT", "This key was already used with a different request.");
      return json.read((String) old.getFirst().get("safe_response"), type);
    }
    T result = work.get();
    db.update(
        "INSERT INTO idempotency_records(id,tenant_id,actor_scope,operation,key_hash,request_hash,safe_response,expires_at) VALUES (?,?,?,?,?,?,?::jsonb,now()+interval '24 hours')",
        UUID.randomUUID(),
        a.tenantId(),
        a.membershipId(),
        operation,
        keyHash,
        fingerprint,
        json.write(result));
    return result;
  }
}
