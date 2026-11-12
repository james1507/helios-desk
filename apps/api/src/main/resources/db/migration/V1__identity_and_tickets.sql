CREATE TABLE users (
 id UUID PRIMARY KEY, email VARCHAR(254) NOT NULL UNIQUE CHECK(email=lower(email)),
 password_hash VARCHAR(255) NOT NULL, display_name VARCHAR(100) NOT NULL,
 disabled_at TIMESTAMPTZ, email_verified_at TIMESTAMPTZ, created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE tenants (
 id UUID PRIMARY KEY, slug VARCHAR(60) NOT NULL UNIQUE, name VARCHAR(120) NOT NULL,
 timezone VARCHAR(60) NOT NULL, status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' CHECK(status IN ('ACTIVE','DISABLED')),
 created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE tenant_memberships (
 id UUID PRIMARY KEY, tenant_id UUID NOT NULL REFERENCES tenants, user_id UUID NOT NULL REFERENCES users,
 role VARCHAR(20) NOT NULL CHECK(role IN ('OWNER','ADMIN','MANAGER','AGENT','REQUESTER','AUDITOR')),
 status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' CHECK(status IN ('ACTIVE','DISABLED')),
 joined_at TIMESTAMPTZ NOT NULL DEFAULT now(), UNIQUE(tenant_id,user_id), UNIQUE(tenant_id,id)
);
CREATE TABLE teams (
 id UUID PRIMARY KEY, tenant_id UUID NOT NULL REFERENCES tenants, name VARCHAR(100) NOT NULL,
 is_active BOOLEAN NOT NULL DEFAULT true, UNIQUE(tenant_id,id), UNIQUE(tenant_id,name)
);
CREATE TABLE team_members (
 tenant_id UUID NOT NULL, team_id UUID NOT NULL, membership_id UUID NOT NULL,
 PRIMARY KEY(tenant_id,team_id,membership_id),
 FOREIGN KEY(tenant_id,team_id) REFERENCES teams(tenant_id,id),
 FOREIGN KEY(tenant_id,membership_id) REFERENCES tenant_memberships(tenant_id,id)
);
CREATE TABLE ticket_counters (
 tenant_id UUID PRIMARY KEY REFERENCES tenants, next_value BIGINT NOT NULL DEFAULT 1 CHECK(next_value>=1)
);
CREATE TABLE ticket_categories (
 id UUID PRIMARY KEY, tenant_id UUID NOT NULL REFERENCES tenants, name VARCHAR(100) NOT NULL,
 is_active BOOLEAN NOT NULL DEFAULT true, UNIQUE(tenant_id,id), UNIQUE(tenant_id,name)
);
CREATE TABLE tags (
 id UUID PRIMARY KEY, tenant_id UUID NOT NULL REFERENCES tenants, slug VARCHAR(50) NOT NULL,
 label VARCHAR(50) NOT NULL, UNIQUE(tenant_id,id), UNIQUE(tenant_id,slug)
);
CREATE TABLE tickets (
 id UUID PRIMARY KEY, tenant_id UUID NOT NULL REFERENCES tenants, ticket_number BIGINT NOT NULL CHECK(ticket_number>0),
 subject VARCHAR(200) NOT NULL CHECK(length(trim(subject)) BETWEEN 5 AND 200),
 status VARCHAR(20) NOT NULL CHECK(status IN ('NEW','OPEN','PENDING_CUSTOMER','ON_HOLD','RESOLVED','CLOSED')),
 priority VARCHAR(10) NOT NULL CHECK(priority IN ('LOW','NORMAL','HIGH','URGENT')),
 source VARCHAR(10) NOT NULL CHECK(source IN ('PORTAL','AGENT','EMAIL')),
 requester_membership_id UUID NOT NULL, assignee_membership_id UUID, team_id UUID, category_id UUID,
 version BIGINT NOT NULL DEFAULT 0 CHECK(version>=0),
 created_at TIMESTAMPTZ NOT NULL, updated_at TIMESTAMPTZ NOT NULL, last_activity_at TIMESTAMPTZ NOT NULL,
 resolved_at TIMESTAMPTZ, closed_at TIMESTAMPTZ, last_public_reply_at TIMESTAMPTZ,
 UNIQUE(tenant_id,id), UNIQUE(tenant_id,ticket_number),
 FOREIGN KEY(tenant_id,requester_membership_id) REFERENCES tenant_memberships(tenant_id,id),
 FOREIGN KEY(tenant_id,assignee_membership_id) REFERENCES tenant_memberships(tenant_id,id),
 FOREIGN KEY(tenant_id,team_id) REFERENCES teams(tenant_id,id),
 FOREIGN KEY(tenant_id,category_id) REFERENCES ticket_categories(tenant_id,id),
 CHECK(status NOT IN ('RESOLVED','CLOSED') OR resolved_at IS NOT NULL),
 CHECK(status<>'CLOSED' OR closed_at IS NOT NULL)
);
CREATE INDEX tickets_queue ON tickets(tenant_id,status,last_activity_at DESC,id DESC);
CREATE INDEX tickets_my_queue ON tickets(tenant_id,assignee_membership_id,status,last_activity_at DESC,id DESC);
CREATE INDEX tickets_requester ON tickets(tenant_id,requester_membership_id,created_at DESC);
CREATE INDEX tickets_team ON tickets(tenant_id,team_id,status);
CREATE INDEX tickets_search ON tickets USING gin(to_tsvector('english',subject));
CREATE TABLE ticket_tags (
 tenant_id UUID NOT NULL, ticket_id UUID NOT NULL, tag_id UUID NOT NULL, PRIMARY KEY(tenant_id,ticket_id,tag_id),
 FOREIGN KEY(tenant_id,ticket_id) REFERENCES tickets(tenant_id,id), FOREIGN KEY(tenant_id,tag_id) REFERENCES tags(tenant_id,id)
);
CREATE TABLE ticket_messages (
 id UUID PRIMARY KEY, tenant_id UUID NOT NULL, ticket_id UUID NOT NULL, author_membership_id UUID NOT NULL,
 author_type VARCHAR(10) NOT NULL CHECK(author_type IN ('HUMAN','SYSTEM')),
 visibility VARCHAR(10) NOT NULL CHECK(visibility IN ('PUBLIC','PRIVATE')),
 channel VARCHAR(10) NOT NULL CHECK(channel IN ('PORTAL','AGENT','EMAIL')),
 body TEXT NOT NULL CHECK(length(trim(body)) BETWEEN 1 AND 20000),
 client_message_id UUID NOT NULL, created_at TIMESTAMPTZ NOT NULL,
 UNIQUE(tenant_id,id), UNIQUE(tenant_id,ticket_id,client_message_id),
 FOREIGN KEY(tenant_id,ticket_id) REFERENCES tickets(tenant_id,id),
 FOREIGN KEY(tenant_id,author_membership_id) REFERENCES tenant_memberships(tenant_id,id)
);
CREATE INDEX messages_ticket ON ticket_messages(tenant_id,ticket_id,created_at,id);
CREATE INDEX messages_public_search ON ticket_messages USING gin(to_tsvector('english',body)) WHERE visibility='PUBLIC';
CREATE TABLE ticket_events (
 id UUID PRIMARY KEY, tenant_id UUID NOT NULL, ticket_id UUID NOT NULL, event_type VARCHAR(60) NOT NULL,
 actor_id UUID NOT NULL, visibility VARCHAR(10) NOT NULL CHECK(visibility IN ('PUBLIC','PRIVATE')),
 correlation_id UUID NOT NULL, data JSONB NOT NULL DEFAULT '{}', created_at TIMESTAMPTZ NOT NULL,
 FOREIGN KEY(tenant_id,ticket_id) REFERENCES tickets(tenant_id,id),
 FOREIGN KEY(tenant_id,actor_id) REFERENCES tenant_memberships(tenant_id,id)
);
CREATE INDEX events_ticket ON ticket_events(tenant_id,ticket_id,created_at,id);
CREATE TABLE audit_logs (
 id UUID PRIMARY KEY, tenant_id UUID NOT NULL REFERENCES tenants, actor_membership_id UUID NOT NULL,
 action VARCHAR(60) NOT NULL, resource_type VARCHAR(40) NOT NULL, resource_id UUID NOT NULL,
 before_safe JSONB NOT NULL DEFAULT '{}', after_safe JSONB NOT NULL DEFAULT '{}', correlation_id UUID NOT NULL,
 created_at TIMESTAMPTZ NOT NULL,
 FOREIGN KEY(tenant_id,actor_membership_id) REFERENCES tenant_memberships(tenant_id,id)
);
CREATE INDEX audit_tenant ON audit_logs(tenant_id,created_at DESC,id DESC);
CREATE FUNCTION deny_history_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'History is append-only'; END; $$;
CREATE TRIGGER immutable_events BEFORE UPDATE OR DELETE ON ticket_events FOR EACH ROW EXECUTE FUNCTION deny_history_mutation();
CREATE TRIGGER immutable_messages BEFORE UPDATE OR DELETE ON ticket_messages FOR EACH ROW EXECUTE FUNCTION deny_history_mutation();
CREATE TRIGGER immutable_audit BEFORE UPDATE OR DELETE ON audit_logs FOR EACH ROW EXECUTE FUNCTION deny_history_mutation();
CREATE TABLE idempotency_records (
 id UUID PRIMARY KEY, tenant_id UUID NOT NULL REFERENCES tenants, actor_scope UUID NOT NULL, operation VARCHAR(160) NOT NULL,
 key_hash VARCHAR(64) NOT NULL, request_hash VARCHAR(64) NOT NULL, safe_response JSONB NOT NULL,
 expires_at TIMESTAMPTZ NOT NULL, UNIQUE(tenant_id,actor_scope,operation,key_hash),
 FOREIGN KEY(tenant_id,actor_scope) REFERENCES tenant_memberships(tenant_id,id)
);
CREATE TABLE outbox_events (
 id UUID PRIMARY KEY, tenant_id UUID NOT NULL REFERENCES tenants, event_type VARCHAR(60) NOT NULL,
 aggregate_id UUID NOT NULL, payload JSONB NOT NULL, created_at TIMESTAMPTZ NOT NULL,
 available_at TIMESTAMPTZ NOT NULL, claimed_until TIMESTAMPTZ, attempts INTEGER NOT NULL DEFAULT 0,
 status VARCHAR(20) NOT NULL DEFAULT 'PENDING' CHECK(status IN ('PENDING','PROCESSING','PROCESSED','RETRY','DEAD_LETTER')),
 last_error VARCHAR(300), dedup_key VARCHAR(180) NOT NULL, UNIQUE(tenant_id,dedup_key)
);
CREATE INDEX outbox_due ON outbox_events(status,available_at) WHERE status IN ('PENDING','RETRY','PROCESSING');
CREATE INDEX outbox_lease ON outbox_events(claimed_until) WHERE status='PROCESSING';
CREATE TABLE auth_attempts (scope VARCHAR(64) NOT NULL, attempted_at TIMESTAMPTZ NOT NULL DEFAULT now());
CREATE INDEX auth_attempts_recent ON auth_attempts(scope,attempted_at);
CREATE TABLE spring_session (
 primary_id CHAR(36) PRIMARY KEY, session_id CHAR(36) NOT NULL UNIQUE,
 creation_time BIGINT NOT NULL, last_access_time BIGINT NOT NULL, max_inactive_interval INTEGER NOT NULL,
 expiry_time BIGINT NOT NULL, principal_name VARCHAR(100)
);
CREATE INDEX spring_session_expiry ON spring_session(expiry_time);
CREATE INDEX spring_session_principal ON spring_session(principal_name);
CREATE TABLE spring_session_attributes (
 session_primary_id CHAR(36) NOT NULL REFERENCES spring_session(primary_id) ON DELETE CASCADE,
 attribute_name VARCHAR(200) NOT NULL, attribute_bytes BYTEA NOT NULL,
 PRIMARY KEY(session_primary_id,attribute_name)
);
