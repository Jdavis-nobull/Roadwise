// Roadwise server: community hazard reports + live location sharing.
// Dependency-free Node (18+). Data lives in ./data (or ROADWISE_DATA).
'use strict';
const http = require('http');
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');

const PORT = parseInt(process.env.ROADWISE_PORT || '48120', 10);
const HOST = process.env.ROADWISE_HOST || '127.0.0.1';
const DATA = process.env.ROADWISE_DATA || path.join(__dirname, 'data');
fs.mkdirSync(DATA, { recursive: true });
const REPORTS_FILE = path.join(DATA, 'reports.json');
const LIVE_FILE = path.join(DATA, 'live.json');

// type -> minutes to live
const TYPES = {
  police: 120, accident: 180, hazard: 240, jam: 60, closure: 1440,
  camera: 240, construction: 10080, weather: 180,
};
const MERGE_RADIUS_M = 150;
const MAX_LIVE_MIN = 24 * 60;

function load(file, fallback) {
  try { return JSON.parse(fs.readFileSync(file, 'utf8')); } catch { return fallback; }
}
let reports = load(REPORTS_FILE, []);
let live = load(LIVE_FILE, {});
let dirty = false;
function save() {
  if (!dirty) return;
  dirty = false;
  const write = (f, v) => { fs.writeFileSync(f + '.tmp', JSON.stringify(v)); fs.renameSync(f + '.tmp', f); };
  try { write(REPORTS_FILE, reports); write(LIVE_FILE, live); } catch (e) { console.error('save failed', e); }
}
setInterval(save, 5000).unref();

function prune() {
  const now = Date.now();
  const before = reports.length;
  reports = reports.filter(r => r.expires > now && !(r.down >= 2 && r.down > r.up));
  for (const [id, s] of Object.entries(live)) {
    if (now > s.expires + 3600_000) delete live[id]; // keep "ended" pages for an hour
  }
  if (reports.length !== before) dirty = true;
}
setInterval(prune, 60_000).unref();

function dist(aLat, aLon, bLat, bLon) {
  const R = 6371008.8, toR = Math.PI / 180;
  const dLat = (bLat - aLat) * toR, dLon = (bLon - aLon) * toR;
  const h = Math.sin(dLat / 2) ** 2 + Math.cos(aLat * toR) * Math.cos(bLat * toR) * Math.sin(dLon / 2) ** 2;
  return 2 * R * Math.asin(Math.min(1, Math.sqrt(h)));
}

const num = (v, lo, hi) => (typeof v === 'number' && Number.isFinite(v) && v >= lo && v <= hi) ? v : null;
const str = (v, max) => (typeof v === 'string' ? v.replace(/[\u0000-\u001f]/g, '').trim().slice(0, max) : '');
const rid = (n) => crypto.randomBytes(n).toString('base64url').slice(0, n);

// --- tiny rate limiter (per client address) ---
const hits = new Map();
function limited(req, max = 90) {
  const ip = (req.headers['x-forwarded-for'] || req.socket.remoteAddress || '').split(',')[0].trim();
  const now = Date.now();
  const h = hits.get(ip) || { t: now, n: 0 };
  if (now - h.t > 60_000) { h.t = now; h.n = 0; }
  h.n++;
  hits.set(ip, h);
  return h.n > max;
}
setInterval(() => hits.clear(), 10 * 60_000).unref();

function send(res, code, obj, headers = {}) {
  const body = typeof obj === 'string' ? obj : JSON.stringify(obj);
  res.writeHead(code, {
    'Content-Type': typeof obj === 'string' ? 'text/html; charset=utf-8' : 'application/json',
    'Cache-Control': 'no-store',
    'X-Content-Type-Options': 'nosniff',
    'Referrer-Policy': 'no-referrer',
    'Access-Control-Allow-Origin': '*',
    ...headers,
  });
  res.end(body);
}

function readBody(req) {
  return new Promise((resolve, reject) => {
    let size = 0; const chunks = [];
    req.on('data', c => {
      size += c.length;
      if (size > 16 * 1024) { reject(new Error('too large')); req.destroy(); return; }
      chunks.push(c);
    });
    req.on('end', () => {
      try { resolve(chunks.length ? JSON.parse(Buffer.concat(chunks).toString('utf8')) : {}); }
      catch { reject(new Error('bad json')); }
    });
    req.on('error', reject);
  });
}

const publicReport = r => ({
  id: r.id, type: r.type, lat: r.lat, lon: r.lon, heading: r.heading, note: r.note,
  created: r.created, expires: r.expires, up: r.up, down: r.down, by: r.by, clientId: r.clientId,
});

// ---------------- reports ----------------
async function addReport(req, res) {
  const b = await readBody(req);
  const type = str(b.type, 20);
  const lat = num(b.lat, -90, 90), lon = num(b.lon, -180, 180);
  if (!TYPES[type] || lat === null || lon === null) return send(res, 400, { error: 'bad report' });
  const now = Date.now();
  // Merge with a nearby active report of the same type (counts as a confirmation).
  const near = reports.find(r => r.type === type && r.expires > now && dist(r.lat, r.lon, lat, lon) < MERGE_RADIUS_M);
  if (near) {
    near.up++;
    near.expires = Math.max(near.expires, now + TYPES[type] * 60_000);
    dirty = true;
    return send(res, 200, publicReport(near));
  }
  const r = {
    id: rid(12), type, lat, lon,
    heading: num(b.heading, 0, 360) ?? 0,
    note: str(b.note, 140), by: str(b.by, 40) || 'Driver', clientId: str(b.clientId, 64),
    created: now, expires: now + TYPES[type] * 60_000, up: 0, down: 0, voters: [],
  };
  reports.push(r);
  dirty = true;
  send(res, 200, publicReport(r));
}

function listReports(url, res) {
  const now = Date.now();
  const bbox = (url.searchParams.get('bbox') || '').split(',').map(Number);
  let out = reports.filter(r => r.expires > now);
  if (bbox.length === 4 && bbox.every(Number.isFinite)) {
    const [w, s, e, n] = bbox;
    out = out.filter(r => r.lon >= w && r.lon <= e && r.lat >= s && r.lat <= n);
  }
  send(res, 200, { reports: out.slice(0, 2000).map(publicReport) });
}

async function vote(req, res, id) {
  const b = await readBody(req);
  const r = reports.find(x => x.id === id);
  if (!r) return send(res, 404, { error: 'not found' });
  const who = str(b.clientId, 64) || 'anon';
  if (r.voters.includes(who)) return send(res, 200, publicReport(r));
  r.voters.push(who);
  if (b.vote === 'up') { r.up++; r.expires = Math.max(r.expires, Date.now() + 30 * 60_000); }
  else if (b.vote === 'down') {
    r.down++;
    if (who === r.clientId) r.expires = 0; // reporter says it's gone
  }
  dirty = true;
  prune();
  send(res, 200, publicReport(r));
}

// ---------------- live location ----------------
async function startLive(req, res) {
  const b = await readBody(req);
  const minutes = Math.min(MAX_LIVE_MIN, Math.max(1, Math.round(num(b.minutes, 1, MAX_LIVE_MIN) ?? 60)));
  const id = rid(12), token = rid(24);
  const now = Date.now();
  live[id] = {
    token, name: str(b.name, 40) || 'Someone', created: now, expires: now + minutes * 60_000,
    stopped: false, trail: [], pos: null, eta: null, dest: null, arrived: false, imperial: true,
  };
  dirty = true;
  send(res, 200, { id, token, expires: live[id].expires });
}

async function updateLive(req, res, id) {
  const b = await readBody(req);
  const s = live[id];
  if (!s || s.token !== b.token) return send(res, 403, { error: 'forbidden' });
  if (s.stopped || Date.now() > s.expires) return send(res, 410, { error: 'ended' });
  const lat = num(b.lat, -90, 90), lon = num(b.lon, -180, 180);
  if (lat !== null && lon !== null) {
    s.pos = {
      lat, lon, speed: num(b.speed, 0, 150) ?? 0, heading: num(b.heading, 0, 360) ?? 0,
      accuracy: num(b.accuracy, 0, 10000) ?? 0, t: Date.now(),
    };
    s.trail.push([+lat.toFixed(6), +lon.toFixed(6)]);
    if (s.trail.length > 400) s.trail.splice(0, s.trail.length - 400);
  }
  s.eta = num(b.eta, 0, 1e14);
  s.remainingM = num(b.remainingM, 0, 1e8);
  const dLat = num(b.destLat, -90, 90), dLon = num(b.destLon, -180, 180);
  s.dest = dLat !== null && dLon !== null ? { name: str(b.destName, 80), lat: dLat, lon: dLon } : null;
  s.arrived = b.arrived === true;
  s.imperial = b.imperial !== false;
  dirty = true;
  send(res, 200, { ok: true });
}

async function stopLive(req, res, id) {
  const b = await readBody(req);
  const s = live[id];
  if (!s || s.token !== b.token) return send(res, 403, { error: 'forbidden' });
  s.stopped = true; s.expires = Math.min(s.expires, Date.now());
  dirty = true;
  send(res, 200, { ok: true });
}

function getLive(res, id) {
  const s = live[id];
  if (!s) return send(res, 404, { error: 'not found' });
  const active = !s.stopped && Date.now() <= s.expires;
  send(res, 200, {
    name: s.name, active, expires: s.expires, pos: active ? s.pos : (s.arrived ? s.pos : null),
    trail: active ? s.trail : [], eta: s.eta, remainingM: s.remainingM, dest: s.dest,
    arrived: s.arrived, imperial: s.imperial,
  });
}

const VIEWER = fs.readFileSync(path.join(__dirname, 'viewer.html'), 'utf8');

// ---------------- router ----------------
const server = http.createServer(async (req, res) => {
  try {
    const url = new URL(req.url, 'http://x');
    const p = url.pathname.replace(/^\/roadwise(?=\/)/, ''); // allow mounting under /roadwise
    if (req.method === 'OPTIONS') return send(res, 204, '', {
      'Access-Control-Allow-Methods': 'GET, POST', 'Access-Control-Allow-Headers': 'Content-Type',
    });
    if (req.method === 'POST' && limited(req)) return send(res, 429, { error: 'slow down' });

    let m;
    if (req.method === 'GET' && p === '/api/health') {
      return send(res, 200, { ok: true, active: reports.filter(r => r.expires > Date.now()).length, live: Object.keys(live).length });
    }
    if (p === '/api/reports' && req.method === 'GET') return listReports(url, res);
    if (p === '/api/reports' && req.method === 'POST') return await addReport(req, res);
    if ((m = p.match(/^\/api\/reports\/([\w-]{1,32})\/vote$/)) && req.method === 'POST') return await vote(req, res, m[1]);
    if (p === '/api/live' && req.method === 'POST') return await startLive(req, res);
    if ((m = p.match(/^\/api\/live\/([\w-]{1,32})$/))) {
      if (req.method === 'POST') return await updateLive(req, res, m[1]);
      if (req.method === 'GET') return getLive(res, m[1]);
    }
    if ((m = p.match(/^\/api\/live\/([\w-]{1,32})\/stop$/)) && req.method === 'POST') return await stopLive(req, res, m[1]);
    if ((m = p.match(/^\/live\/([\w-]{1,32})$/)) && req.method === 'GET') {
      return send(res, 200, VIEWER, {
        'Content-Security-Policy': "default-src 'self'; script-src 'self' 'unsafe-inline' https://unpkg.com; style-src 'self' 'unsafe-inline' https://unpkg.com; img-src 'self' data: https://*.tile.openstreetmap.org https://unpkg.com; connect-src 'self'",
      });
    }
    if (req.method === 'GET' && (p === '/' || p === '')) {
      return send(res, 200, '<!doctype html><meta name=viewport content="width=device-width"><title>Roadwise</title><body style="font-family:sans-serif;padding:24px">Roadwise server is running.</body>');
    }
    send(res, 404, { error: 'not found' });
  } catch (e) {
    send(res, 400, { error: e.message || 'bad request' });
  }
});

server.listen(PORT, HOST, () => console.log(`[${new Date().toISOString()}] Roadwise server on http://${HOST}:${PORT}`));
process.on('SIGINT', () => { dirty = true; save(); process.exit(0); });
process.on('SIGTERM', () => { dirty = true; save(); process.exit(0); });
