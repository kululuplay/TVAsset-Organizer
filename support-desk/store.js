"use strict";
const crypto = require("node:crypto");
const { equal, redact } = require("./security");
class PgStore {
  constructor(pool, caps = {}) { this.pool = pool; this.caps = { installations: 100000, tickets: 100000, logs: 10000, ...caps }; }
  async transaction(action) {
    const client = await this.pool.connect();
    try {
      await client.query("BEGIN");
      // One local database lock makes capacity checks atomic, including across workers.
      await client.query("SELECT pg_advisory_xact_lock(584027163)");
      const result = await action(client);
      await client.query("COMMIT"); return result;
    } catch (error) { await client.query("ROLLBACK"); throw error; }
    finally { client.release(); }
  }
  capacity() { const error = new Error("Support capacity reached"); error.code = "support_capacity"; throw error; }
  async healthy() { await this.pool.query("SELECT 1"); }
  async register(id, secretHash) {
    return this.transaction(async client => {
      const { rows } = await client.query("SELECT secret_hash FROM support_installations WHERE id=$1", [id]);
      if (rows[0]) return equal(rows[0].secret_hash, secretHash);
      const count = await client.query("SELECT count(*)::int AS n FROM support_installations");
      if (count.rows[0].n >= this.caps.installations) this.capacity();
      await client.query("INSERT INTO support_installations(id,secret_hash) VALUES($1,$2)", [id, secretHash]);
      return true;
    });
  }
  async authenticate(id, secretHash) {
    const { rows } = await this.pool.query("SELECT secret_hash FROM support_installations WHERE id=$1", [id]);
    return Boolean(rows[0] && equal(rows[0].secret_hash, secretHash));
  }
  async create(installationId, ticket) {
    return this.transaction(async client => {
      const existing = await client.query("SELECT id,code,status,created_at FROM support_tickets WHERE installation_id=$1 AND request_id=$2", [installationId, ticket.requestId]);
      if (existing.rows[0]) return existing.rows[0];
      const usage = await client.query("SELECT count(*)::int AS tickets, count(*) FILTER (WHERE log<>'')::int AS logs FROM support_tickets");
      if (usage.rows[0].tickets >= this.caps.tickets || (ticket.log && usage.rows[0].logs >= this.caps.logs)) this.capacity();
      const code = "K-" + crypto.randomBytes(8).toString("hex").toUpperCase();
      const { rows } = await client.query(`INSERT INTO support_tickets(code,installation_id,request_id,type,message,log,metadata)
        VALUES($1,$2,$3,$4,$5,$6,$7) RETURNING id,code,status,created_at`, [code, installationId, ticket.requestId, ticket.type, ticket.message, ticket.log, ticket.metadata]);
      return rows[0];
    });
  }
  async maintain() {
    // Keep customer requests and ticket history; expire only diagnostic attachments.
    await this.pool.query("UPDATE support_tickets SET log='' WHERE log<>'' AND created_at < now()-interval '90 days'");
    await this.pool.query("DELETE FROM support_installations i WHERE created_at < now()-interval '30 days' AND NOT EXISTS (SELECT 1 FROM support_tickets t WHERE t.installation_id=i.id)");
  }
  async mine(id) {
    const { rows } = await this.pool.query(`SELECT id,code,type,message,status,created_at AS "createdAt" FROM support_tickets
      WHERE installation_id=$1 AND type<>'diagnostic' ORDER BY id DESC LIMIT 20`, [id]);
    return rows.map(row => ({ ...row, message: redact(row.message) }));
  }
  async ack(id, ids) { await this.pool.query("UPDATE support_tickets SET notified=true WHERE installation_id=$1 AND id=ANY($2::bigint[])", [id, ids]); }
  async list({ status, type, before, query, scope }) {
    const values = []; const where = [];
    const add = (sql, value) => { values.push(value); where.push(sql.replace("?", `$${values.length}`)); };
    if (status) add("status=?", status);
    if (type) add("type=?", type);
    if (scope === "requests") where.push("type<>'diagnostic'");
    if (before) add("id<?", before);
    if (query) add("(code || ' ' || message || ' ' || COALESCE(metadata->>'model','') || ' ' || COALESCE(metadata->>'appVersion','')) ILIKE ?", `%${query.replace(/[\\%_]/g, "\\$&")}%`);
    const { rows } = await this.pool.query(`SELECT id,code,type,message,status,metadata,created_at,updated_at FROM support_tickets
      ${where.length ? "WHERE " + where.join(" AND ") : ""} ORDER BY id DESC LIMIT 31`, values);
    const more = rows.length > 30; const items = rows.slice(0, 30).map(row => ({ ...row, message: redact(row.message) }));
    return { items, nextCursor: more ? items.at(-1).id : null };
  }
  async detail(id) {
    const { rows } = await this.pool.query("SELECT id,code,type,message,log,status,metadata,created_at,updated_at FROM support_tickets WHERE id=$1", [id]);
    return rows[0] ? { ...rows[0], message: redact(rows[0].message), log: redact(rows[0].log) } : null;
  }
  async status(id, status, actor) {
    const { rows } = await this.pool.query(`WITH changed AS (
      UPDATE support_tickets SET status=$2,updated_at=now(),notified=false WHERE id=$1 AND status<>$2 RETURNING id
    ) INSERT INTO support_audit(ticket_id,actor,status) SELECT id,$3,$2 FROM changed RETURNING ticket_id`, [id, status, actor]);
    return rows.length > 0 || Boolean(await this.detail(id));
  }
  async stats() {
    const { rows } = await this.pool.query(`SELECT count(*)::int AS total,
      count(*) FILTER(WHERE status<>'done')::int AS open,
      count(*) FILTER(WHERE type='diagnostic')::int AS reports,
      count(DISTINCT installation_id)::int AS devices FROM support_tickets`);
    return rows[0];
  }
}
module.exports = { PgStore };
