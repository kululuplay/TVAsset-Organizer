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

-- Structured, credential-free measurements; independent from user-authored tickets.
CREATE TABLE IF NOT EXISTS support_playback_devices (
  id BIGSERIAL PRIMARY KEY,
  installation_id UUID NOT NULL UNIQUE REFERENCES support_installations(id),
  sampled_at_ms BIGINT NOT NULL,
  status_sampled_at_ms BIGINT NOT NULL DEFAULT 0,
  last_seen_at TIMESTAMPTZ NOT NULL,
  device JSONB NOT NULL,
  sessions JSONB NOT NULL,
  status TEXT NOT NULL CHECK (status IN ('problem','healthy','unknown')),
  history_limited BOOLEAN NOT NULL DEFAULT false
);
ALTER TABLE support_playback_devices ADD COLUMN IF NOT EXISTS history_limited BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE support_playback_devices ADD COLUMN IF NOT EXISTS status_sampled_at_ms BIGINT NOT NULL DEFAULT 0;
-- Small receipts preserve retry idempotency even after bounded history is pruned.
CREATE TABLE IF NOT EXISTS support_playback_receipts (
  installation_id UUID NOT NULL REFERENCES support_installations(id),
  sample_id UUID NOT NULL,
  received_at TIMESTAMPTZ NOT NULL,
  PRIMARY KEY (installation_id, sample_id)
);
CREATE INDEX IF NOT EXISTS support_playback_receipts_retention ON support_playback_receipts(received_at);
CREATE TABLE IF NOT EXISTS support_playback_samples (
  id BIGSERIAL PRIMARY KEY,
  installation_id UUID NOT NULL REFERENCES support_installations(id),
  sample_id UUID NOT NULL,
  sampled_at_ms BIGINT NOT NULL,
  received_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  payload JSONB NOT NULL,
  UNIQUE (installation_id, sample_id)
);
CREATE INDEX IF NOT EXISTS support_playback_samples_device ON support_playback_samples(installation_id, id DESC);
CREATE INDEX IF NOT EXISTS support_playback_samples_retention ON support_playback_samples(received_at);
CREATE INDEX IF NOT EXISTS support_playback_devices_problem ON support_playback_devices(status, last_seen_at DESC);
