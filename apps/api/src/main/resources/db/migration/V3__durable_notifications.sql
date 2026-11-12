ALTER TABLE outbox_events ADD COLUMN claim_token UUID;
ALTER TABLE outbox_events ADD CONSTRAINT outbox_tenant_id UNIQUE(tenant_id,id);
CREATE TABLE notification_counters (
 tenant_id UUID NOT NULL, recipient_membership_id UUID NOT NULL, next_value BIGINT NOT NULL DEFAULT 1 CHECK(next_value>=1),
 PRIMARY KEY(tenant_id,recipient_membership_id),
 FOREIGN KEY(tenant_id,recipient_membership_id) REFERENCES tenant_memberships(tenant_id,id)
);
CREATE TABLE notifications (
 id UUID PRIMARY KEY, tenant_id UUID NOT NULL, recipient_membership_id UUID NOT NULL,
 recipient_sequence BIGINT NOT NULL CHECK(recipient_sequence>0), outbox_event_id UUID NOT NULL,
 ticket_id UUID NOT NULL, type VARCHAR(60) NOT NULL, ticket_version BIGINT NOT NULL,
 created_at TIMESTAMPTZ NOT NULL, read_at TIMESTAMPTZ,
 UNIQUE(tenant_id,recipient_membership_id,recipient_sequence), UNIQUE(tenant_id,recipient_membership_id,outbox_event_id),
 FOREIGN KEY(tenant_id,recipient_membership_id) REFERENCES tenant_memberships(tenant_id,id),
 FOREIGN KEY(tenant_id,ticket_id) REFERENCES tickets(tenant_id,id),
 FOREIGN KEY(tenant_id,outbox_event_id) REFERENCES outbox_events(tenant_id,id)
);
CREATE INDEX notification_feed ON notifications(tenant_id,recipient_membership_id,recipient_sequence);
CREATE TABLE sla_warnings (
 tenant_id UUID NOT NULL, cycle_id UUID NOT NULL, metric VARCHAR(20) NOT NULL CHECK(metric IN ('FIRST_RESPONSE','RESOLUTION')),
 created_at TIMESTAMPTZ NOT NULL, PRIMARY KEY(tenant_id,cycle_id,metric),
 FOREIGN KEY(tenant_id,cycle_id) REFERENCES ticket_sla_cycles(tenant_id,id)
);

ALTER TABLE ticket_sla_cycles ADD COLUMN first_response_warning_at TIMESTAMPTZ;
ALTER TABLE ticket_sla_cycles ADD COLUMN resolution_warning_at TIMESTAMPTZ;
CREATE INDEX sla_response_warning ON ticket_sla_cycles(first_response_warning_at) WHERE first_response_at IS NULL;
CREATE INDEX sla_resolution_warning ON ticket_sla_cycles(resolution_warning_at) WHERE completed_at IS NULL AND paused_at IS NULL;

ALTER TABLE ticket_events ALTER COLUMN actor_id DROP NOT NULL;
ALTER TABLE audit_logs ALTER COLUMN actor_membership_id DROP NOT NULL;
