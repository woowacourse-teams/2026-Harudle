import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';

// Runs actual Nginx proxies and a local stub; it never contacts dev or prod.
const root = fileURLToPath(new URL('../../', import.meta.url));
const docker = process.env.DOCKER_BIN || 'docker';
const image = 'nginx:1.29-alpine3.22';
const traceId = '0123456789abcdef0123456789abcdef';

function command(...args) {
  const result = spawnSync(docker, args, { encoding: 'utf8', timeout: 120_000 });
  assert.equal(result.status, 0, result.error?.message || result.stderr);
  return result.stdout.trim();
}

test('both proxies preserve backend trace IDs and write valid timing JSON without query secrets',
  { timeout: 180_000 }, async () => {
    const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'harudle-proxy-latency-'));
    const network = `harudle-latency-${process.pid}`;
    const backend = `${network}-backend`;
    const frontend = `${network}-frontend`;
    const host = `${network}-host`;
    try {
      const frontendSource = fs.readFileSync(path.join(root, 'frontend/nginx/default.conf'), 'utf8');
      const hostSource = fs.readFileSync(path.join(root, 'deploy/nginx/harudle.conf.example'), 'utf8');
      fs.writeFileSync(path.join(directory, 'frontend.conf'), frontendSource);
      fs.writeFileSync(path.join(directory, 'host.conf'), hostSource.replace('http://127.0.0.1:3000', 'http://frontend:80'));
      fs.writeFileSync(path.join(directory, 'backend.conf'), `server {
        listen 8080;
        add_header X-Trace-Id ${traceId} always;
        location = /api/fail { return 444; }
        location / { return 200 'ok'; }
      }`);
      command('network', 'create', network);
      function start(name, alias, configuration, publish = false) {
        command('run', '-d', '--name', name, '--network', network, '--network-alias', alias,
          ...(publish ? ['-p', '127.0.0.1::80'] : []),
          '-v', `${path.join(directory, configuration)}:/etc/nginx/conf.d/default.conf:ro`, image);
      }
      start(backend, 'backend', 'backend.conf');
      start(frontend, 'frontend', 'frontend.conf');
      start(host, 'host', 'host.conf', true);
      for (const name of [backend, frontend, host]) command('exec', name, 'nginx', '-t');
      const address = `http://${command('port', host, '80/tcp')}`;
      let response;
      for (let attempt = 0; attempt < 30; attempt++) {
        try {
          response = await fetch(`${address}/api/check?cursor=private-query`, {
            headers: { 'X-Trace-Id': 'untrusted-client-id', Authorization: 'Bearer private-token' },
            signal: AbortSignal.timeout(3000),
          });
          if (response.status === 200) break;
        } catch { /* Nginx may still be starting. */ }
        await new Promise((resolve) => setTimeout(resolve, 200));
      }
      assert.equal(response?.status, 200);
      assert.equal(response.headers.get('X-Trace-Id'), traceId);
      await response.text();
      const failure = await fetch(`${address}/api/fail`, { signal: AbortSignal.timeout(15_000) });
      assert.equal(failure.status, 502);
      await failure.text();
      for (const [name, stage] of [[frontend, 'frontend_proxy'], [host, 'host_proxy']]) {
        const text = command('logs', name);
        assert.ok(!text.includes('private-query'));
        assert.ok(!text.includes('private-token'));
        assert.ok(!text.includes('untrusted-client-id'));
        const rows = text.split('\n').filter((line) => line.startsWith('{')).map((line) => JSON.parse(line));
        const success = rows.find((row) => row.uri === '/api/check' && row.status === 200);
        const failed = rows.find((row) => row.uri === '/api/fail');
        assert.ok(success, `missing success log for ${stage}`);
        assert.equal(success.stage, stage);
        assert.equal(success.traceId, traceId);
        assert.match(success.proxyRequestId, /^[a-f0-9]{32}$/);
        assert.ok(success.requestSeconds >= 0);
        for (const field of ['upstreamConnectSeconds', 'upstreamHeaderSeconds', 'upstreamResponseSeconds']) {
          assert.ok(Number.isFinite(Number(success[field])), `${stage}: ${field}`);
        }
        assert.equal(failed.status, 502);
        assert.ok(['', '-'].includes(failed.traceId));
        assert.match(failed.proxyRequestId, /^[a-f0-9]{32}$/);
      }
    } finally {
      for (const name of [host, frontend, backend]) {
        spawnSync(docker, ['rm', '-f', name], { encoding: 'utf8', timeout: 30_000 });
      }
      spawnSync(docker, ['network', 'rm', network], { encoding: 'utf8', timeout: 30_000 });
      // Remove only the unique temporary directory created by this test.
      if (path.dirname(directory) === os.tmpdir() && path.basename(directory).startsWith('harudle-proxy-latency-')) {
        fs.rmSync(directory, { recursive: true, force: true });
      }
    }
  });
