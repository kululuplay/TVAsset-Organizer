-- A dedicated database/role is used; no existing IPTV tables are touched.
CREATE TABLE IF NOT EXISTS support_installations (
  id UUID PRIMARY KEY,
  secret_hash TEXT NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE IF NOT EXISTS support_tickets (
  id BIGSERIAL PRIMARY KEY,
  code TEXT NOT NULL UNIQUE,
  installation_id UUID NOT NULL REFERENCES support_installations(id),
  request_id UUID NOT NULL,
  type TEXT NOT NULL CHECK (type IN ('diagnostic','channel','movie','series','complaint')),
  message TEXT NOT NULL,
  log TEXT NOT NULL DEFAULT '',
  metadata JSONB NOT NULL DEFAULT '{}',
  status TEXT NOT NULL DEFAULT 'new' CHECK (status IN ('new','reviewing','done')),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  notified BOOLEAN NOT NULL DEFAULT false,
  UNIQUE (installation_id, request_id)
);
CREATE INDEX IF NOT EXISTS support_tickets_inbox ON support_tickets(status, id DESC);
CREATE INDEX IF NOT EXISTS support_tickets_device ON support_tickets(installation_id, id DESC);
CREATE INDEX IF NOT EXISTS support_tickets_retention ON support_tickets(created_at) WHERE log<>'';
CREATE TABLE IF NOT EXISTS support_audit (
  id BIGSERIAL PRIMARY KEY,
  ticket_id BIGINT NOT NULL REFERENCES support_tickets(id),
  actor TEXT NOT NULL,
  status TEXT NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
