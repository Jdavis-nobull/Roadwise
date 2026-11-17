// Run: node test/server-test.js   (uses a throwaway data dir and port 48199)
'use strict';
const { spawn } = require('child_process');
const path = require('path');
const os = require('os');
const fs = require('fs');
const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'roadwise-'));
const PORT = 48199, B = `http://127.0.0.1:${PORT}`;
const srv = spawn(process.execPath, [path.join(__dirname, '..', 'server.js')], { env: { ...process.env, ROADWISE_DATA: dir, ROADWISE_PORT: PORT } });
let pass = 0, fail = 0;
const ok = (c, m) => { if (c) { pass++; } else { fail++; console.log('FAIL', m); } };
const post = (p, b) => fetch(B + p, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(b) });
setTimeout(async () => {
  try {
    ok((await (await fetch(B + '/api/health')).json()).ok, 'health');
    let r = await post('/api/reports', { type: 'police', lat: 39.96, lon: -83.0, by: 'A', clientId: 'c1' });
    const rep = await r.json(); ok(r.status === 200 && rep.id, 'create report');
    r = await post('/api/reports', { type: 'police', lat: 39.9601, lon: -83.0001, clientId: 'c2' });
    ok((await r.json()).id === rep.id, 'nearby duplicate merges');
    ok((await post('/api/reports', { type: 'bogus', lat: 1, lon: 1 })).status === 400, 'bad type rejected');
    ok((await post('/api/reports', { type: 'jam', lat: 200, lon: 1 })).status === 400, 'bad lat rejected');
    let list = await (await fetch(B + '/api/reports?bbox=-84,39,-82,41')).json();
    ok(list.reports.length === 1 && list.reports[0].up === 1, 'list in bbox');
    list = await (await fetch(B + '/api/reports?bbox=0,0,1,1')).json();
    ok(list.reports.length === 0, 'bbox filter');
    await post(`/api/reports/${rep.id}/vote`, { vote: 'down', clientId: 'x1' });
    await post(`/api/reports/${rep.id}/vote`, { vote: 'down', clientId: 'x1' });
    list = await (await fetch(B + '/api/reports')).json();
    ok(list.reports.length === 1 && list.reports[0].down === 1, 'one vote per client');
    await post(`/api/reports/${rep.id}/vote`, { vote: 'down', clientId: 'x2' });
    await post(`/api/reports/${rep.id}/vote`, { vote: 'down', clientId: 'x3' });
    list = await (await fetch(B + '/api/reports')).json();
    ok(list.reports.length === 0, 'removed after downvotes');

    const s = await (await post('/api/live', { name: 'Jake', minutes: 15 })).json();
    ok(s.id && s.token, 'live start');
    ok((await post('/api/live/' + s.id, { token: 'wrong', lat: 1, lon: 1 })).status === 403, 'live bad token');
    ok((await post('/api/live/' + s.id, { token: s.token, lat: 39.9, lon: -83, speed: 20, eta: Date.now() + 600000, destName: 'Home', destLat: 40, destLon: -83 })).status === 200, 'live update');
    let v = await (await fetch(B + '/api/live/' + s.id)).json();
    ok(v.active && v.pos.lat === 39.9 && v.dest.name === 'Home' && v.name === 'Jake' && !('token' in v), 'live get (no token leak)');
    const page = await fetch(B + '/live/' + s.id);
    ok(page.status === 200 && (await page.text()).includes('leaflet'), 'viewer page');
    ok((await fetch(B + '/roadwise/api/live/' + s.id)).status === 200, 'prefix mount');
    ok((await post('/api/live/' + s.id + '/stop', { token: s.token })).status === 200, 'live stop');
    v = await (await fetch(B + '/api/live/' + s.id)).json();
    ok(!v.active && v.pos === null, 'stopped hides position');
    ok((await post('/api/live/' + s.id, { token: s.token, lat: 1, lon: 1 })).status === 410, 'no updates after stop');
    ok((await fetch(B + '/api/live/nope')).status === 404, 'unknown live 404');
  } catch (e) { fail++; console.log('ERROR', e); }
  console.log(`${pass} passed, ${fail} failed`);
  srv.kill(); fs.rmSync(dir, { recursive: true, force: true });
  process.exit(fail ? 1 : 0);
}, 700);
