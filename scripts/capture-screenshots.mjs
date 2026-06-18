#!/usr/bin/env node
/**
 * Auto-captures dashboard screenshots from a real fixture run.
 *
 * Workflow:
 *   1. cd into a fixture, run `mvn test-order:auto test test-order:dashboard`
 *      to produce target/test-order-dashboard/index.html with realistic data.
 *   2. Launch headless Chromium, set viewport, point at file:// URL.
 *   3. For each tab in TABS, click the tab button (`.tab-btn` matching the
 *      label fragment, mirroring DashboardUiIT.clickTab in the Java tests),
 *      wait for render, screenshot fullPage to docs/assets/screenshots/.
 *
 * Outputs:
 *   docs/assets/screenshots/dashboard-<slug>.png        (one per tab)
 *
 * The CI workflow (.github/workflows/docs.yml) runs this before the
 * Docusaurus build, then the build step copies screenshots into
 * website/static/img/screenshots/.
 *
 * Usage:
 *   node scripts/capture-screenshots.mjs [--fixture=<path>] [--out=<dir>]
 */

import { execSync } from 'node:child_process';
import { existsSync, mkdirSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

import { chromium } from 'playwright';

const __dirname = dirname(fileURLToPath(import.meta.url));
const REPO_ROOT = resolve(__dirname, '..');

const args = Object.fromEntries(
	process.argv.slice(2)
		.filter(a => a.startsWith('--'))
		.map(a => {
			const [k, ...v] = a.replace(/^--/, '').split('=');
			return [k, v.join('=') || true];
		}),
);

const FIXTURE = resolve(
	REPO_ROOT,
	args.fixture || 'test-fixtures/fixture-spring-boot-slices',
);
const OUT_DIR = resolve(REPO_ROOT, args.out || 'docs/assets/screenshots');
const VIEWPORT = { width: 1440, height: 900 };

const TABS = [
	{ label: 'Tests', file: 'dashboard-tests' },
	{ label: 'Analytics', file: 'dashboard-analytics' },
	{ label: 'Weights', file: 'dashboard-weights' },
	{ label: 'Coverage', file: 'dashboard-coverage' },
	{ label: 'Static', file: 'dashboard-static-analysis' },
	{ label: 'ML', file: 'dashboard-ml' },
];

function sh(cmd, opts = {}) {
	console.log(`$ ${cmd}`);
	return execSync(cmd, { stdio: 'inherit', ...opts });
}

function buildFixtureDashboard() {
	if (!existsSync(FIXTURE)) {
		throw new Error(`fixture not found: ${FIXTURE}`);
	}
	const dashboard = join(FIXTURE, 'target/test-order-dashboard/index.html');
	if (existsSync(dashboard) && process.env.SKIP_FIXTURE_BUILD === '1') {
		console.log(`[skip-fixture-build] reusing ${dashboard}`);
		return dashboard;
	}
	// Quiet Maven build — produces a real dashboard with timings + scores.
	sh(`mvn -q -f ${join(FIXTURE, 'pom.xml')} test-order:auto test test-order:dashboard -DskipTests=false || true`);
	if (!existsSync(dashboard)) {
		throw new Error(`dashboard not generated at ${dashboard}`);
	}
	return dashboard;
}

async function captureTab(page, label, file) {
	// Click the tab whose visible text matches the label (mirrors
	// DashboardUiIT.clickTab in the Java suite).
	const btn = page.locator('.tab-btn').filter({ hasText: label }).first();
	if (await btn.count() === 0) {
		console.warn(`[skip] tab button "${label}" not present in this dashboard`);
		return false;
	}
	await btn.click();
	await page.waitForTimeout(800); // let charts settle
	try {
		await page.waitForLoadState('networkidle', { timeout: 3000 });
	} catch { /* file:// often has no network — fine */ }
	const out = join(OUT_DIR, `${file}.png`);
	await page.screenshot({ path: out, fullPage: true });
	console.log(`✓ ${out}`);
	return true;
}

async function captureOverview(page) {
	// "Overview" = the default landing view (Tests tab is the default but
	// it's also the most-recognized "main" shot). Capture it first as
	// dashboard-overview.png so existing markdown references stay valid.
	const out = join(OUT_DIR, 'dashboard-overview.png');
	await page.screenshot({ path: out, fullPage: true });
	console.log(`✓ ${out}`);
}

async function main() {
	mkdirSync(OUT_DIR, { recursive: true });
	const dashboard = buildFixtureDashboard();
	const url = pathToFileURL(dashboard).toString();

	const browser = await chromium.launch({ headless: true });
	const ctx = await browser.newContext({ viewport: VIEWPORT });
	const page = await ctx.newPage();
	page.on('pageerror', err => console.warn(`[page error] ${err}`));

	await page.goto(url, { waitUntil: 'domcontentloaded' });
	await page.waitForSelector('header', { timeout: 10_000 });
	await page.waitForTimeout(800); // initial Vue mount + chart render

	await captureOverview(page);
	for (const t of TABS) {
		await captureTab(page, t.label, t.file);
	}

	await browser.close();
	console.log(`\n${TABS.length + 1} screenshots written to ${OUT_DIR}`);
}

main().catch(err => {
	console.error(err);
	process.exit(1);
});
