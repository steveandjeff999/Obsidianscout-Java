#!/usr/bin/env node
/**
 * Regenerates the Feature Tour screenshots (docs/FEATURE_TOUR.md) as WebP files in
 * src/main/resources/static/assets/images/docs/.
 *
 * Setup (one time; installs into scripts/.doc-shots, which is git-ignored, and uses your installed Chrome):
 *   npm install --prefix scripts/.doc-shots playwright-core@1.55.0
 *
 * Run (with the server running on http://localhost:8080):
 *   node scripts/capture-doc-screenshots.js [accounts.json] [page,page,...]
 *
 *   accounts.json  JSON file with { username, teamNumber, program, password } for a local test admin.
 *                  Defaults to data/test-accounts.local.json.
 *   page list      Optional comma-separated subset, e.g. "dashboard,scout".
 *
 * Environment: DOCS_BASE_URL (default http://localhost:8080), DOCS_CHROME_CHANNEL (default "chrome"; use "msedge" for Edge).
 *
 * Screenshot-only tweaks, applied inside the headless browser and never saved to the server:
 * the "no API key" banner and toasts are hidden, the team's page restrictions are lifted, and the
 * Non-Scouting Tools, Local AI and Match Predictions toggles are switched on so those pages render.
 * (Match Predictions is also checked server-side, so it still shows "turned off" unless enabled for real.)
 * Service workers are blocked so those tweaks stick, except for SW_PAGES (Cache & History), which are shot
 * last with the service worker installed so they show the real offline status.
 */
const fs = require('fs');
const path = require('path');

function loadPlaywright() {
  const local = path.join(__dirname, '.doc-shots', 'node_modules', 'playwright-core');
  try { return require(local); } catch (_) { /* fall through */ }
  try { return require('playwright-core'); } catch (_) { /* fall through */ }
  console.error('playwright-core not found. Run: npm install --prefix scripts/.doc-shots playwright-core@1.55.0');
  process.exit(1);
}

const { chromium } = loadPlaywright();
const ROOT = path.resolve(__dirname, '..');
const BASE = (process.env.DOCS_BASE_URL || 'http://localhost:8080').replace(/\/$/, '');
const OUT_DIR = path.join(ROOT, 'src', 'main', 'resources', 'static', 'assets', 'images', 'docs');
const accountsPath = process.argv[2] || path.join(ROOT, 'data', 'test-accounts.local.json');
const only = process.argv[3] ? new Set(process.argv[3].split(',')) : null;

// Public pages are captured before signing in; '/' is saved as index.
const PUBLIC_PAGES = ['index', 'login'];
// Signed-in pages, in sidebar order (superadmin-only pages are left out).
const PAGES = [
  'dashboard', 'event-info',
  'cache-manager', 'match-predictions', 'my-assignments', 'pit-scout', 'prescout', 'prediction-leaderboard', 'qr-scanner', 'qual-scout', 'scout',
  'all-data', 'analytics', 'compare', 'custom-analytics', 'data-validation', 'graphs', 'match-data', 'matches', 'pit-data', 'projected-rankings', 'qual-data', 'qual-rankings', 'rankings', 'teams',
  'alliance-selection', 'alliances', 'event-predictor', 'match-planning', 'pick-list', 'predictor',
  'battery-tracker', 'match-checklist',
  'admin-settings', 'assistant', 'banners', 'chat', 'backup', 'docs', 'events', 'assignments', 'scouter-report', 'config', 'shared-links', 'tutorials', 'users',
  'public-data',
];
// Pages that show the service worker's offline status; shot with service workers allowed.
const SW_PAGES = ['cache-manager'];

// Picks the first option of <select> `sel` whose label matches `pattern` (waiting for the options to load),
// which fires the page's change handler. Returns false when no option matches.
async function pick(page, sel, pattern) {
  const re = pattern.source;
  const flags = pattern.flags;
  try {
    await page.waitForFunction(([s, r, f]) => {
      const el = document.querySelector(s);
      return el && [...el.options].some(o => o.value && new RegExp(r, f).test(o.textContent));
    }, [sel, re, flags], { timeout: 15000 });
  } catch (_) {
    console.warn(`no option matching ${pattern} in ${sel}`);
    return false;
  }
  const value = await page.$eval(sel, (el, [r, f]) =>
    [...el.options].find(o => o.value && new RegExp(r, f).test(o.textContent)).value, [re, flags]);
  await page.selectOption(sel, value);
  return true;
}

// Pages that open on an empty "choose something" prompt get a selection first, so the shot shows the feature.
// The picks suit the 2026cttd test event; on another event a missing option only logs a warning.
const MATCH = /\b(QM|Qual\w*)( Match)? ?3\b/i;
const SETUP = {
  predictor: p => pick(p, '#match-select', MATCH),
  'event-predictor': p => pick(p, '#event-select', /cttd|Cow Town/i),
  // Opened with URL parameters: picking three teams in a row fires overlapping requests, and an older
  // two-team response can land last.
  compare: p => p.goto(`${BASE}/compare?team1=935&team2=5454&team3=1825`, { waitUntil: 'networkidle' }).catch(() => {}),
  'match-planning': p => pick(p, '#match-select', MATCH),
};

async function patchJson(route, mutate) {
  if (route.request().method() !== 'GET') return route.continue();
  // Drop conditional headers so the server sends a body (a 304 would make the app reuse its cached copy).
  const headers = { ...route.request().headers() };
  delete headers['if-none-match'];
  delete headers['if-modified-since'];
  const resp = await route.fetch({ headers });
  let body = await resp.text();
  try {
    const json = JSON.parse(body);
    mutate(json);
    body = JSON.stringify(json);
  } catch (_) { /* pass through unchanged */ }
  return route.fulfill({ status: resp.status(), contentType: 'application/json', body });
}

(async () => {
  const acct = JSON.parse(fs.readFileSync(accountsPath, 'utf8'));
  fs.mkdirSync(OUT_DIR, { recursive: true });

  const browser = await chromium.launch({ channel: process.env.DOCS_CHROME_CHANNEL || 'chrome', headless: true });

  // Service workers are blocked so the patched API responses below aren't replaced by cached copies.
  // Pages that report on the service worker itself (SW_PAGES) are shot afterwards in a context that allows it.
  const newContext = async (serviceWorkers) => {
    const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 }, deviceScaleFactor: 1, serviceWorkers });
    await ctx.route('**/api/banners', r => r.fulfill({ status: 200, contentType: 'application/json', body: '[]' }));
    await ctx.addInitScript(() => {
      document.addEventListener('DOMContentLoaded', () => {
        const st = document.createElement('style');
        st.textContent = '.toast,.toast-container,#toast-container{display:none!important}';
        document.head.appendChild(st);
      });
    });
    if (serviceWorkers === 'block') {
      await ctx.route(/\/api\/settings(\?|$)/, r => patchJson(r, j => {
        const s = j.settings || j;
        for (const k of ['adminPages', 'analyticsPages', 'scoutPages']) delete s[k];
        s.matchPredictionsEnabled = true;
      }));
      await ctx.route(/\/api\/auth\/me(\?|$)/, r => patchJson(r, j => {
        for (const u of [j, j.user].filter(Boolean)) { u.nonScoutingToolsEnabled = true; u.localAiEnabled = true; }
      }));
    }
    return ctx;
  };

  const signIn = async (p) => {
    await p.goto(BASE + '/login', { waitUntil: 'networkidle' });
    await p.fill('#username', acct.username);
    await p.fill('#teamNumber', String(acct.teamNumber));
    if (acct.program) await p.selectOption('#login-program', acct.program).catch(() => {});
    await p.fill('#password', acct.password);
    await p.click('#login-submit');
    await p.waitForURL(u => !u.pathname.startsWith('/login'), { timeout: 20000 }).catch(() => {});
    const status = await p.evaluate(() => fetch('/api/auth/status', { credentials: 'same-origin' }).then(r => r.status));
    if (status === 401) {
      console.error('Sign-in failed; check the accounts file.');
      await browser.close();
      process.exit(1);
    }
  };

  const ctx = await newContext('block');
  let page = await ctx.newPage();
  let saved = 0;

  const shoot = async (name, url) => {
    if (only && !only.has(name)) return;
    try {
      await page.goto(BASE + url, { waitUntil: 'networkidle', timeout: 30000 });
    } catch (_) {
      console.warn(`slow load: ${name}`);
    }
    await page.waitForTimeout(2500);
    if (SETUP[name]) {
      await SETUP[name](page);
      await page.waitForLoadState('networkidle').catch(() => {});
      await page.waitForTimeout(2500);
    }
    const finalPath = new URL(page.url()).pathname;
    if (url !== '/' && finalPath !== url) console.warn(`${name}: redirected to ${finalPath} (page may be hidden for this account)`);
    const png = await page.screenshot({ type: 'png' });
    // Convert to WebP in the browser to keep the repo small.
    const webp = await page.evaluate(async (b64) => {
      const img = new Image();
      img.src = 'data:image/png;base64,' + b64;
      await img.decode();
      const c = document.createElement('canvas');
      c.width = img.width;
      c.height = img.height;
      c.getContext('2d').drawImage(img, 0, 0);
      return c.toDataURL('image/webp', 0.88).split(',')[1];
    }, png.toString('base64'));
    fs.writeFileSync(path.join(OUT_DIR, `${name}.webp`), Buffer.from(webp, 'base64'));
    saved++;
    console.log(`saved ${name}.webp`);
  };

  for (const p of PUBLIC_PAGES) await shoot(p, p === 'index' ? '/' : `/${p}`);

  await signIn(page);
  for (const p of PAGES) if (!SW_PAGES.includes(p)) await shoot(p, `/${p}`);

  // Let the service worker install and cache the app first, so these pages show the real offline status.
  if (SW_PAGES.some(p => !only || only.has(p))) {
    const swCtx = await newContext('allow');
    page = await swCtx.newPage();
    await signIn(page);
    await page.goto(BASE + '/dashboard', { waitUntil: 'networkidle' });
    await page.evaluate(() => navigator.serviceWorker && navigator.serviceWorker.ready).catch(() => {});
    await page.waitForTimeout(15000);
    for (const p of SW_PAGES) await shoot(p, `/${p}`);
  }

  await browser.close();
  console.log(`Done: ${saved} screenshot(s) in ${path.relative(ROOT, OUT_DIR)}`);
})().catch(err => {
  console.error(err);
  process.exit(1);
});
