CREATE TABLE sla_policies (
 id UUID PRIMARY KEY, tenant_id UUID NOT NULL REFERENCES tenants, name VARCHAR(100) NOT NULL,
 version BIGINT NOT NULL DEFAULT 0, calendar JSONB NOT NULL, targets JSONB NOT NULL,
 pause_on_pending BOOLEAN NOT NULL DEFAULT true, warning_ratio INTEGER NOT NULL DEFAULT 20 CHECK(warning_ratio BETWEEN 1 AND 99),
 UNIQUE(tenant_id,id), UNIQUE(tenant_id,name)
);
CREATE TABLE ticket_sla_cycles (
 id UUID PRIMARY KEY, tenant_id UUID NOT NULL, ticket_id UUID NOT NULL, cycle_number INTEGER NOT NULL CHECK(cycle_number>0),
 policy_snapshot JSONB NOT NULL, cycle_started_at TIMESTAMPTZ NOT NULL,
 first_response_due_at TIMESTAMPTZ, first_response_at TIMESTAMPTZ,
 resolution_due_at TIMESTAMPTZ NOT NULL, completed_at TIMESTAMPTZ, paused_at TIMESTAMPTZ,
 consumed_seconds BIGINT NOT NULL DEFAULT 0 CHECK(consumed_seconds>=0),
 resolution_started_at TIMESTAMPTZ NOT NULL,
 UNIQUE(tenant_id,id), UNIQUE(tenant_id,ticket_id,cycle_number),
 FOREIGN KEY(tenant_id,ticket_id) REFERENCES tickets(tenant_id,id)
);
CREATE INDEX sla_active_response ON ticket_sla_cycles(first_response_due_at) WHERE first_response_at IS NULL;
CREATE INDEX sla_active_resolution ON ticket_sla_cycles(resolution_due_at) WHERE completed_at IS NULL AND paused_at IS NULL;
CREATE TABLE sla_pause_intervals (
 id UUID PRIMARY KEY, tenant_id UUID NOT NULL, cycle_id UUID NOT NULL, started_at TIMESTAMPTZ NOT NULL, ended_at TIMESTAMPTZ,
 FOREIGN KEY(tenant_id,cycle_id) REFERENCES ticket_sla_cycles(tenant_id,id), CHECK(ended_at IS NULL OR ended_at>=started_at)
);
CREATE TABLE sla_breaches (
 id UUID PRIMARY KEY, tenant_id UUID NOT NULL, ticket_id UUID NOT NULL, cycle_id UUID NOT NULL,
 metric VARCHAR(20) NOT NULL CHECK(metric IN ('FIRST_RESPONSE','RESOLUTION')), breached_at TIMESTAMPTZ NOT NULL,
 UNIQUE(tenant_id,cycle_id,metric),
 FOREIGN KEY(tenant_id,ticket_id) REFERENCES tickets(tenant_id,id),
 FOREIGN KEY(tenant_id,cycle_id) REFERENCES ticket_sla_cycles(tenant_id,id)
);
