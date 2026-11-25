// streammaster-crash-reporter — Cloudflare Worker + D1.
//
// 1) Crash reports (existing, unchanged): POST / with the 7 report fields
//    -> INSERT INTO crash_reports. Used by StreamMaster + MH Player.
// 2) MH Player remote test mode (dev only, removed in the final release):
//    - POST /remote/commands {action, payload}        -> create a command, {ok, id}
//    - GET  /remote/commands?pending=1               -> pending commands (oldest first),
//                                                       marks them 'delivered'
//    - GET  /remote/commands                         -> latest 20 commands (any status)
//    - POST /remote/heartbeat {device_model, app_version, screen, screenshot, logs}
//                                                     -> store latest heartbeat (keeps last 10)
//    - GET  /remote/heartbeat?latest=1               -> newest heartbeat
//    - POST /remote/results {command_id, status, screen, screenshot, logs}
//                                                     -> store result, mark command 'done'
//    - GET  /remote/results?command_id=N | latest    -> results
//
// Screenshots are small JPEGs (base64, client-capped ~100KB). Logs are
// capped server-side too. No auth (same as crash reports): the worker URL
// is not publicised, the feature is dev-only and is removed in the final
// app release.
export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    const path = url.pathname;
    const now = new Date().toISOString();

    const json = (obj, status = 200) =>
      new Response(JSON.stringify(obj), {
        status,
        headers: { 'Content-Type': 'application/json' },
      });

    // Health check.
    if (request.method === 'GET' && path === '/') {
      return new Response('streammaster-crash-reporter ok', { status: 200 });
    }

    // ---- 1) Crash reports (unchanged) -------------------------------------
    if (request.method === 'POST' && path === '/') {
      let r;
      try { r = await request.json(); } catch { return json({ ok: false, error: 'bad json' }, 400); }
      try {
        await env.DB.prepare(
          'INSERT INTO crash_reports (app_version, version_code, device_model, android_version, error_type, message, stack_trace, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)'
        ).bind(
          String(r.app_version ?? ''), Number(r.version_code ?? 0), String(r.device_model ?? ''),
          String(r.android_version ?? ''), String(r.error_type ?? ''), String(r.message ?? ''),
          String(r.stack_trace ?? ''), now
        ).run();
        return json({ ok: true });
      } catch (e) {
        return json({ ok: false, error: String(e) }, 500);
      }
    }

    // ---- 2) Remote test mode ----------------------------------------------
    try {
      // Create a command (from the tester).
      if (request.method === 'POST' && path === '/remote/commands') {
        let r;
        try { r = await request.json(); } catch { return json({ ok: false, error: 'bad json' }, 400); }
        const action = String(r.action ?? '').trim();
        if (!action) return json({ ok: false, error: 'missing action' }, 400);
        const payload = typeof r.payload === 'string' ? r.payload : JSON.stringify(r.payload ?? {});
        const res = await env.DB.prepare(
          'INSERT INTO remote_commands (action, payload, status, created_at, updated_at) VALUES (?, ?, ?, ?, ?)'
        ).bind(action, payload.slice(0, 4000), 'pending', now, now).run();
        // Light prune: drop done commands older than 7 days.
        await env.DB.prepare(
          "DELETE FROM remote_commands WHERE status = 'done' AND created_at < datetime('now', '-7 days')"
        ).run().catch(() => {});
        return json({ ok: true, id: res.meta.last_row_id });
      }

      // Poll pending commands (from the app) or list recent ones (tester).
      if (request.method === 'GET' && path === '/remote/commands') {
        if (url.searchParams.get('pending') === '1') {
          const { results } = await env.DB.prepare(
            "SELECT id, action, payload FROM remote_commands WHERE status = 'pending' ORDER BY id ASC LIMIT 20"
          ).all();
          if (results.length > 0) {
            const ids = results.map((c) => c.id);
            await env.DB.prepare(
              `UPDATE remote_commands SET status = 'delivered', updated_at = ? WHERE id IN (${ids.map(() => '?').join(',')})`
            ).bind(now, ...ids).run();
          }
          return json({ ok: true, commands: results });
        }
        const { results } = await env.DB.prepare(
          'SELECT id, action, payload, status, created_at, updated_at FROM remote_commands ORDER BY id DESC LIMIT 20'
        ).all();
        return json({ ok: true, commands: results });
      }

      // Heartbeat from the app (current screen + screenshot + logs).
      if (request.method === 'POST' && path === '/remote/heartbeat') {
        let r;
        try { r = await request.json(); } catch { return json({ ok: false, error: 'bad json' }, 400); }
        await env.DB.prepare(
          'INSERT INTO remote_heartbeats (device_model, app_version, screen, screenshot, logs, created_at) VALUES (?, ?, ?, ?, ?, ?)'
        ).bind(
          String(r.device_model ?? '').slice(0, 200),
          String(r.app_version ?? '').slice(0, 50),
          String(r.screen ?? '').slice(0, 200),
          String(r.screenshot ?? '').slice(0, 150000),
          String(r.logs ?? '').slice(0, 4000),
          now
        ).run();
        await env.DB.prepare(
          'DELETE FROM remote_heartbeats WHERE id NOT IN (SELECT id FROM remote_heartbeats ORDER BY id DESC LIMIT 10)'
        ).run();
        return json({ ok: true });
      }

      // Latest heartbeat (tester view).
      if (request.method === 'GET' && path === '/remote/heartbeat') {
        const { results } = await env.DB.prepare(
          'SELECT id, device_model, app_version, screen, screenshot, logs, created_at FROM remote_heartbeats ORDER BY id DESC LIMIT 1'
        ).all();
        return json({ ok: true, heartbeat: results[0] ?? null });
      }

      // Result of an executed command (from the app).
      if (request.method === 'POST' && path === '/remote/results') {
        let r;
        try { r = await request.json(); } catch { return json({ ok: false, error: 'bad json' }, 400); }
        const commandId = Number(r.command_id ?? 0);
        if (!commandId) return json({ ok: false, error: 'missing command_id' }, 400);
        await env.DB.prepare(
          'INSERT INTO remote_results (command_id, status, screen, screenshot, logs, created_at) VALUES (?, ?, ?, ?, ?, ?)'
        ).bind(
          commandId,
          String(r.status ?? 'done').slice(0, 50),
          String(r.screen ?? '').slice(0, 200),
          String(r.screenshot ?? '').slice(0, 150000),
          String(r.logs ?? '').slice(0, 4000),
          now
        ).run();
        await env.DB.prepare(
          "UPDATE remote_commands SET status = 'done', updated_at = ? WHERE id = ?"
        ).bind(now, commandId).run();
        return json({ ok: true });
      }

      // Results (tester view): by command, or latest 20.
      if (request.method === 'GET' && path === '/remote/results') {
        const cid = url.searchParams.get('command_id');
        if (cid) {
          const { results } = await env.DB.prepare(
            'SELECT id, command_id, status, screen, screenshot, logs, created_at FROM remote_results WHERE command_id = ? ORDER BY id DESC LIMIT 1'
          ).bind(Number(cid)).all();
          return json({ ok: true, result: results[0] ?? null });
        }
        const { results } = await env.DB.prepare(
          'SELECT id, command_id, status, screen, screenshot, logs, created_at FROM remote_results ORDER BY id DESC LIMIT 20'
        ).all();
        return json({ ok: true, results });
      }
    } catch (e) {
      return json({ ok: false, error: String(e) }, 500);
    }

    return json({ ok: false, error: 'not found' }, 404);
  }
};
