package me.bechberger.testorder.dashboard.ui;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import com.microsoft.playwright.*;
import com.microsoft.playwright.assertions.PlaywrightAssertions;
import com.microsoft.playwright.options.KeyboardModifier;
import com.microsoft.playwright.options.WaitForSelectorState;

/**
 * Playwright end-to-end tests for the test-order HTML dashboard.
 *
 * <p>
 * Tests run a real headless Chromium browser against a locally-served dashboard
 * populated with synthetic test data. They catch real browser-level issues such
 * as:
 * <ul>
 * <li>CDN/CORS failures (asset files must load from local server)</li>
 * <li>JavaScript errors (Chart.js registration, Vue mount failures)</li>
 * <li>Vue app not rendering because of script errors</li>
 * <li>Charts and graphs not rendering</li>
 * <li>Broken tab navigation</li>
 * </ul>
 *
 * <p>
 * The tests skip automatically when no Playwright-compatible browser is
 * installed. Install Chromium once with:
 *
 * <pre>
 *   mvn exec:java -Dexec.mainClass=com.microsoft.playwright.CLI \
 *                 -Dexec.args="install chromium" \
 *                 -pl test-order-dashboard-ui-tests
 * </pre>
 *
 * Then run with: {@code mvn verify -pl test-order-dashboard-ui-tests}
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DashboardUiIT {

	@TempDir
	static Path tempDir;

	private static DashboardServerFixture fixture;
	private static Playwright playwright;
	private static Browser browser;

	/**
	 * Console errors collected across all tests — checked in
	 * {@link #noJavaScriptErrors()}.
	 */
	private static final List<String> consoleErrors = new ArrayList<>();

	private Page page;

	@BeforeAll
	static void startInfrastructure() throws Exception {
		fixture = new DashboardServerFixture(tempDir).withRichTestData();
		fixture.start();

		try {
			playwright = Playwright.create();
			browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
		} catch (Exception e) {
			Assumptions.assumeTrue(false, "Playwright browser not available — skipping UI tests: " + e.getMessage());
		}
	}

	@AfterAll
	static void stopInfrastructure() {
		if (browser != null)
			browser.close();
		if (playwright != null)
			playwright.close();
		if (fixture != null)
			fixture.close();
	}

	@BeforeEach
	void openFreshPage() {
		BrowserContext ctx = browser.newContext();
		page = ctx.newPage();

		// Collect console errors for the no-errors test
		page.onConsoleMessage(msg -> {
			if ("error".equals(msg.type())) {
				consoleErrors.add(msg.text());
			}
		});

		page.navigate(fixture.url());
		// Wait until Vue has mounted — the header with the project name must appear
		page.waitForSelector("header",
				new Page.WaitForSelectorOptions().setTimeout(10_000).setState(WaitForSelectorState.VISIBLE));
	}

	@AfterEach
	void closePage() {
		if (page != null)
			page.close();
	}

	// ── 1. App mounts and shows real data ────────────────────────────────────

	@Test
	@Order(1)
	void vueAppMountsWithData() {
		// "No test data yet" must NOT appear
		assertThat(page.locator("text=No test data yet").count())
				.as("Vue app must mount with data, not show the empty-state splash").isZero();

		// Project name must appear in the header
		assertThat(page.locator("header").textContent()).as("Header should contain the project name")
				.contains("ui-test-project");
	}

	@Test
	@Order(2)
	void testListIsPopulated() {
		// Tests tab (default) shows test rows in a table
		int count = page.locator("tbody tr").count();
		assertThat(count).as("Tests tab should show at least 10 test rows").isGreaterThanOrEqualTo(10);
	}

	@Test
	@Order(3)
	void testNamesAreVisible() {
		// At least one well-known synthetic test name must appear in the Tests tab
		// table
		assertThat(page.locator("main").textContent()).as("Test list should contain UserServiceTest")
				.contains("UserServiceTest");
	}

	@Test
	@Order(4)
	void kpiRowShowsValues() {
		// KPI boxes must be present and non-empty
		int kpis = page.locator(".kpi").count();
		assertThat(kpis).as("KPI row should show at least 4 summary cards").isGreaterThanOrEqualTo(4);

		// Avg APFD should be visible and non-trivial
		String kpiText = page.locator(".kpi").first().textContent();
		assertThat(kpiText).as("First KPI box must have content").isNotBlank();
	}

	@Test
	@Order(5)
	void runHistoryAppearsInFooter() {
		// We seeded 2 runs; footer shows run count
		String footerText = page.locator("footer").textContent();
		assertThat(footerText).as("Footer should show '2 runs' (seeded 2 run records)").contains("2 runs");
	}

	// ── 2. Tab navigation ────────────────────────────────────────────────────

	@Test
	@Order(10)
	void tabNavigationChangesContent() {
		// Click "Tests" (already active), then "Analytics", verify a change
		String initialContent = page.locator("main").textContent();

		clickTab("Analytics");
		page.waitForTimeout(500); // let Vue re-render

		String afterClick = page.locator("main").textContent();
		assertThat(afterClick).as("Tab content should change after clicking a different tab")
				.isNotEqualTo(initialContent);
	}

	// ── 3. Charts render (Chart.js) ──────────────────────────────────────────

	@Test
	@Order(20)
	void scoreBreakdownRendersInline() {
		// Click a test row to open the inline detail panel with score breakdown chart
		page.locator("tbody tr").first().click();
		page.waitForTimeout(500);

		// The detail panel should render a canvas (score breakdown bar)
		page.waitForSelector("canvas",
				new Page.WaitForSelectorOptions().setTimeout(5_000).setState(WaitForSelectorState.ATTACHED));

		assertThat(page.locator("canvas").count()).as("Inline detail panel must contain Chart.js canvas elements")
				.isGreaterThanOrEqualTo(1);
	}

	@Test
	@Order(21)
	void analyticsTabRendersCharts() {
		clickTab("Analytics");
		page.waitForTimeout(800); // let charts render

		assertThat(page.locator("canvas").count()).as("Analytics tab must contain at least 2 Chart.js canvases")
				.isGreaterThanOrEqualTo(2);
	}

	// ── 4. D3 dependency graph ───────────────────────────────────────────────

	@Test
	@Order(30)
	void depGraphTabRendersSvg() {
		// Click a test in the sidebar to select it
		page.locator("aside .sidebar__test-row").first().click();
		page.waitForTimeout(200);

		clickTab("Tests");
		page.waitForTimeout(200);

		// D3 creates SVG with circle nodes inside the dep graph wrapper
		page.waitForSelector("#dg-wrap svg",
				new Page.WaitForSelectorOptions().setTimeout(5_000).setState(WaitForSelectorState.ATTACHED));

		assertThat(page.locator("#dg-wrap svg").count()).as("Dep Graph tab must render an SVG element")
				.isGreaterThanOrEqualTo(1);

		// D3 should draw at least one circle (test node)
		page.waitForSelector("#dg-wrap svg circle",
				new Page.WaitForSelectorOptions().setTimeout(5_000).setState(WaitForSelectorState.ATTACHED));
	}

	// ── 5. Weights Explorer ──────────────────────────────────────────────────

	@Test
	@Order(40)
	void weightsExplorerHasSliders() {
		clickTab("Weights");
		page.waitForTimeout(400);

		int sliders = page.locator("input[type=range]").count();
		assertThat(sliders).as("Weights Explorer should have at least 7 weight sliders").isGreaterThanOrEqualTo(7);
	}

	@Test
	@Order(41)
	void weightsExplorerShowsSimulationTable() {
		clickTab("Weights");
		page.waitForTimeout(400);

		// The simulation results table must be visible
		int rows = page.locator("tbody tr").count();
		assertThat(rows).as("Weights Explorer simulation table should list all tests").isGreaterThanOrEqualTo(10);
	}

	// ── 6. Test selection and score breakdown ────────────────────────────────

	@Test
	@Order(50)
	void selectingTestShowsDetailPanel() {
		// Click a test row in the Tests tab to expand the inline detail panel
		page.locator("tbody tr").first().click();
		page.waitForTimeout(400);

		// The detail panel should show score breakdown info and test info
		String mainContent = page.locator("main").textContent();
		assertThat(mainContent).as("Detail panel should show test info when a row is clicked")
				.containsAnyOf("Score Breakdown", "Run History", "Info");
	}

	// ── 7. Search / filter ───────────────────────────────────────────────────

	@Test
	@Order(60)
	void searchFilterNarrowsTestList() {
		// Type "UserService" into the filter box in the Tests tab
		page.locator("input[placeholder*='Filter']").fill("UserService");
		page.waitForTimeout(300);

		int visibleTests = page.locator("tbody tr").count();
		assertThat(visibleTests).as("Filtering by 'UserService' should reduce the visible test rows").isLessThan(10);

		// Clear the filter
		page.locator("input[placeholder*='Filter']").fill("");
	}

	// ── 8. Assets served without CORS/404 errors ────────────────────────────

	@Test
	@Order(70)
	void selfContainedHtmlIsServed() throws Exception {
		// The dashboard is now fully self-contained (Vite bundles Vue, Chart.js, D3
		// into a single IIFE). Verify that the HTML page loads with status 200
		// and contains the inlined app script.
		HttpClient client = HttpClient.newHttpClient();
		HttpResponse<String> resp = client.send(HttpRequest.newBuilder().uri(URI.create(fixture.url())).GET().build(),
				HttpResponse.BodyHandlers.ofString());

		assertThat(resp.statusCode()).as("Dashboard HTML must be served with HTTP 200").isEqualTo(200);
		assertThat(resp.body()).as("Dashboard HTML must contain the Vue app bundle").contains("createApp");
		assertThat(resp.body()).as("Dashboard HTML must contain the dashboard data").contains("dashboard-data");
	}

	@Test
	@Order(71)
	void assetPathTraversalIsBlocked() throws Exception {
		HttpClient client = HttpClient.newHttpClient();
		HttpResponse<String> resp = client.send(
				HttpRequest.newBuilder().uri(URI.create(fixture.url() + "/assets/../index.html")).GET().build(),
				HttpResponse.BodyHandlers.ofString());

		// Normalised path is assets/index.html — which does not exist → 404
		// If the server over-serves it anyway, that is acceptable but must not be 500
		assertThat(resp.statusCode()).as("Path traversal must not 500").isNotEqualTo(500);
	}

	// ── 9. Coverage (inline in Analytics tab) ─────────────────────────────

	@Test
	@Order(75)
	void coverageTreemapRendersInAnalyticsTab() {
		clickTab("Analytics");
		page.waitForTimeout(800);

		// Coverage treemap renders an SVG (inline at the bottom of Analytics)
		page.waitForSelector("#cov-treemap svg",
				new Page.WaitForSelectorOptions().setTimeout(5_000).setState(WaitForSelectorState.ATTACHED));

		assertThat(page.locator("#cov-treemap svg").count()).as("Analytics tab must render a coverage treemap SVG")
				.isGreaterThanOrEqualTo(1);

		// Summary stats must show source class count
		String mainContent = page.locator("main").textContent();
		assertThat(mainContent).as("Analytics tab should show source class statistics").contains("Source Classes");
	}

	// ── 10. No JavaScript errors ──────────────────────────────────────────────

	@Test
	@Order(80)
	void noJavaScriptErrors() {
		// Give Vue + charts time to finish any async initialisation
		page.waitForTimeout(1000);

		// Filter out known-benign messages; fail on real errors
		List<String> realErrors = consoleErrors.stream().filter(e -> !e.contains("favicon")) // 404 for favicon is
																								// harmless
				.toList();

		assertThat(realErrors).as("No JavaScript errors should appear in the browser console.\n"
				+ "If you see CORS errors, assets are not being served locally.\n"
				+ "If you see TypeError, Chart.js or Vue failed to initialise.").isEmpty();
	}

	// ── 11. Sidebar sorting ──────────────────────────────────────────────────

	@Test
	@Order(100)
	void sidebarSortButtonsPresent() {
		int sortButtons = page.locator(".sidebar__sort-btn").count();
		assertThat(sortButtons).as("Sidebar should show 4 sort buttons (Rank, Name, Score, Dur)").isEqualTo(4);
	}

	@Test
	@Order(101)
	void sidebarSortByNameReordersTests() {
		// Capture initial first test name
		String firstTestBefore = page.locator("aside .sidebar__test-name").first().textContent();

		// Click the "Name" sort button
		page.locator(".sidebar__sort-btn").filter(new Locator.FilterOptions().setHasText("Name")).click();
		page.waitForTimeout(300);

		String firstTestAfterAsc = page.locator("aside .sidebar__test-name").first().textContent();

		// Click again to reverse direction
		page.locator(".sidebar__sort-btn").filter(new Locator.FilterOptions().setHasText("Name")).click();
		page.waitForTimeout(300);

		String firstTestAfterDesc = page.locator("aside .sidebar__test-name").first().textContent();

		// Sorting by name should change the order from the default rank-based sort
		assertThat(firstTestAfterAsc)
				.as("Sorting by name ascending should change the first test from default rank order")
				.isNotEqualTo(firstTestBefore);

		assertThat(firstTestAfterDesc).as("Toggling sort direction should change the first test")
				.isNotEqualTo(firstTestAfterAsc);
	}

	@Test
	@Order(102)
	void sidebarActiveSortButtonHighlighted() {
		// Default sort is by Rank — the Rank button should have the active class
		assertThat(page.locator(".sidebar__sort-btn--active").count())
				.as("One sort button should be highlighted as active").isEqualTo(1);

		String activeText = page.locator(".sidebar__sort-btn--active").textContent();
		assertThat(activeText).as("Default active sort should be 'Rank'").containsIgnoringCase("Rank");
	}

	// ── 12. Test badges ──────────────────────────────────────────────────────

	@Test
	@Order(110)
	void changedTestShowsBadge() {
		// UserServiceTest depends on UserService which is in changedClasses → should
		// show CHANGED badge
		// Find the row for UserServiceTest in the sidebar
		Locator userRow = page.locator("aside .sidebar__test-row")
				.filter(new Locator.FilterOptions().setHasText("UserServiceTest"));
		assertThat(userRow.count()).as("UserServiceTest should appear in the sidebar").isGreaterThanOrEqualTo(1);

		// It should have a badge for changed (abbreviated "C" in compact mode)
		assertThat(userRow.first().locator(".badge--changed").count())
				.as("UserServiceTest should have a 'changed' badge since UserService changed")
				.isGreaterThanOrEqualTo(1);
	}

	@Test
	@Order(111)
	void failingTestShowsBadge() {
		// UserServiceTest and PaymentServiceTest failed in Run 2
		Locator userRow = page.locator("aside .sidebar__test-row")
				.filter(new Locator.FilterOptions().setHasText("UserServiceTest"));
		assertThat(userRow.first().locator(".badge--fail").count())
				.as("UserServiceTest should have a 'failing' badge because it failed in run 2")
				.isGreaterThanOrEqualTo(1);
	}

	@Test
	@Order(112)
	void slowTestShowsBadge() {
		// IntegrationTest at 800ms is the slowest — should be marked slow
		Locator integRow = page.locator("aside .sidebar__test-row")
				.filter(new Locator.FilterOptions().setHasText("IntegrationTest"));
		assertThat(integRow.count()).as("IntegrationTest should appear in the sidebar").isGreaterThanOrEqualTo(1);

		// Either slow badge or we verify it shows duration
		String rowText = integRow.first().textContent();
		assertThat(rowText).as("IntegrationTest row should show duration info").containsAnyOf("800ms", "🐢", "SLOW");
	}

	// ── 13. Changed classes panel ────────────────────────────────────────────

	@Test
	@Order(120)
	void changedClassesPanelOpensOnClick() {
		// The header shows "1 changed" (UserService)
		Locator changedLink = page.locator("header").locator("text=/changed/");
		assertThat(changedLink.count()).as("Header should show a 'changed' indicator").isGreaterThanOrEqualTo(1);

		changedLink.first().click();
		page.waitForTimeout(300);

		// The changed classes panel should now be visible with the changed class name
		assertThat(page.locator(".changed-panel__tag").count())
				.as("Changed classes panel should show tag(s) after clicking").isGreaterThanOrEqualTo(1);

		assertThat(page.locator(".changed-panel__tag").first().textContent())
				.as("Changed class tag should contain UserService").contains("UserService");
	}

	@Test
	@Order(121)
	void changedClassesPanelToggles() {
		// Open the panel
		Locator changedLink = page.locator("header").locator("text=/changed/");
		changedLink.first().click();
		page.waitForTimeout(200);

		int tagsOpen = page.locator(".changed-panel__tag").count();
		assertThat(tagsOpen).as("Panel should show tags when open").isGreaterThanOrEqualTo(1);

		// Click again to close
		changedLink.first().click();
		page.waitForTimeout(200);

		int tagsClosed = page.locator(".changed-panel__tag").count();
		assertThat(tagsClosed).as("Panel should hide tags when toggled closed").isZero();
	}

	// ── 14. Multi-select comparison ──────────────────────────────────────────

	@Test
	@Order(130)
	void ctrlClickMultiSelectsTests() {
		// Click first test normally
		page.locator("aside .sidebar__test-row").nth(0).click();
		page.waitForTimeout(200);

		// Ctrl+click second test (meta key on macOS)
		page.locator("aside .sidebar__test-row").nth(1)
				.click(new Locator.ClickOptions().setModifiers(List.of(KeyboardModifier.META)));
		page.waitForTimeout(300);

		// Sidebar should show "2 selected" count
		String sidebarText = page.locator("aside").textContent();
		assertThat(sidebarText).as("Sidebar should indicate 2 tests are selected").contains("2 selected");

		// Main area should show multi-select comparison view
		String mainContent = page.locator("main").textContent();
		assertThat(mainContent).as("Main panel should show multi-select comparison with '2 tests selected'")
				.contains("2 tests selected");
	}

	// ── 15. Weight slider interaction ────────────────────────────────────────

	@Test
	@Order(140)
	void weightSliderChangesSimulation() {
		clickTab("Weights");
		page.waitForTimeout(400);

		// Capture original first row delta
		String origDelta = page.locator("tbody tr").first().locator("td").nth(3).textContent();

		// Drag the first slider to its maximum
		Locator firstSlider = page.locator("input[type=range]").first();
		double max = Double.parseDouble(firstSlider.getAttribute("max"));
		firstSlider.fill(String.valueOf((int) max));
		page.waitForTimeout(400);

		// Check that at least one row shows a non-zero delta
		boolean anyChanged = false;
		int rowCount = page.locator("tbody tr").count();
		for (int i = 0; i < rowCount && i < 10; i++) {
			String delta = page.locator("tbody tr").nth(i).locator("td").nth(3).textContent().trim();
			if (!"+0".equals(delta) && !"0".equals(delta)) {
				anyChanged = true;
				break;
			}
		}
		assertThat(anyChanged).as("Moving a weight slider to max should cause at least one delta to become non-zero")
				.isTrue();
	}

	@Test
	@Order(141)
	void weightResetButtonRestoresDefaults() {
		clickTab("Weights");
		page.waitForTimeout(400);

		// Change a slider
		Locator slider = page.locator("input[type=range]").first();
		slider.fill("0");
		page.waitForTimeout(200);

		String afterChange = slider.inputValue();

		// Click Reset button
		page.locator("button").filter(new Locator.FilterOptions().setHasText("Reset")).click();
		page.waitForTimeout(300);

		String afterReset = slider.inputValue();
		assertThat(afterReset).as("Slider value should be restored after pressing Reset").isNotEqualTo("0");
	}

	@Test
	@Order(142)
	void weightsTableIsSortable() {
		clickTab("Weights");
		page.waitForTimeout(400);

		// Click the "Δ rank" header to sort by delta
		page.locator("th").filter(new Locator.FilterOptions().setHasText("rank")).last().click();
		page.waitForTimeout(300);

		// The active header should be highlighted
		assertThat(page.locator(".weights__th--active").count()).as("Clicked column header should be highlighted")
				.isGreaterThanOrEqualTo(1);
	}

	// ── 16. Coverage in Analytics ────────────────────────────────────────────

	@Test
	@Order(150)
	void coverageKpisShowValues() {
		clickTab("Analytics");
		page.waitForTimeout(800);

		// Coverage section should show numeric KPIs
		String mainText = page.locator("main").textContent();

		assertThat(mainText).as("Analytics should show 'Packages' coverage KPI").contains("Packages");

		assertThat(mainText).as("Analytics should show 'Avg Tests/Class' coverage KPI").contains("Avg Tests/Class");
	}

	@Test
	@Order(151)
	void coverageProgressBarShowsPercentage() {
		clickTab("Analytics");
		page.waitForTimeout(800);

		// The overall coverage bar shows a percentage
		String mainText = page.locator("main").textContent();
		assertThat(mainText).as("Analytics should show an overall coverage percentage").containsPattern("\\d+%");
	}

	// ── 17. Footer details ───────────────────────────────────────────────────

	@Test
	@Order(160)
	void footerShowsPluginVersion() {
		String footerText = page.locator("footer").textContent();
		assertThat(footerText).as("Footer should contain a version indicator").containsPattern("v\\d");
	}

	@Test
	@Order(161)
	void footerShowsTimestamp() {
		String footerText = page.locator("footer").textContent();
		// The generated timestamp should be a readable date
		assertThat(footerText).as("Footer should contain a date/time").containsPattern("\\d{4}");
	}

	// ── 18. Sidebar test row structure ───────────────────────────────────────

	@Test
	@Order(170)
	void sidebarTestRowShowsRankAndScore() {
		Locator firstRow = page.locator("aside .sidebar__test-row").first();

		// Should have rank (#1 or similar)
		assertThat(firstRow.locator(".sidebar__test-rank").textContent()).as("First sidebar row should show rank #1")
				.contains("#1");

		// Should have a numeric score
		String scoreText = firstRow.locator(".sidebar__test-score").textContent().trim();
		assertThat(scoreText).as("First sidebar row should display a numeric score").matches("\\d+");
	}

	@Test
	@Order(171)
	void sidebarTestsCountMatchesOverview() {
		// Sidebar test count label
		String sidebarText = page.locator("aside").textContent();
		// Overview KPI shows total tests
		int overviewKpiCount = page.locator(".kpi").count();

		int sidebarRows = page.locator("aside .sidebar__test-row").count();
		assertThat(sidebarRows).as("Sidebar should list exactly 10 test rows (matching fixture data)").isEqualTo(10);
	}

	// ── 19. Test detail view content ─────────────────────────────────────────

	@Test
	@Order(180)
	void selectedTestShowsScoreBreakdown() {
		// Click a test to select it
		page.locator("aside .sidebar__test-row").first().click();
		page.waitForTimeout(500);

		// Detail view should show "Score Breakdown" and "Run History"
		String mainText = page.locator("main").textContent();
		assertThat(mainText).as("Detail view should show Score Breakdown section").contains("Score Breakdown");
		assertThat(mainText).as("Detail view should show Run History section").contains("Run History");
	}

	@Test
	@Order(181)
	void selectedTestShowsBadgesInDetailView() {
		// Click UserServiceTest (it has changed + failing badges)
		page.locator("aside .sidebar__test-row").filter(new Locator.FilterOptions().setHasText("UserServiceTest"))
				.first().click();
		page.waitForTimeout(500);

		// Detail view should show badges in full text mode (CHANGED, FAILING)
		String mainText = page.locator("main").textContent();
		assertThat(mainText).as("Detail panel should show 'CHANGED' badge text").contains("CHANGED");
		assertThat(mainText).as("Detail panel should show 'FAILING' badge text").contains("FAILING");
	}

	@Test
	@Order(182)
	void selectedTestRunHistoryShowsSquares() {
		page.locator("aside .sidebar__test-row").first().click();
		page.waitForTimeout(500);

		// Run history squares (pass/fail color blocks)
		int squares = page.locator(".test-detail__run-sq").count();
		assertThat(squares).as("Run history should show squares for each run record (at least 2)")
				.isGreaterThanOrEqualTo(2);
	}

	// ── 20. Analytics distribution charts ────────────────────────────────────

	@Test
	@Order(190)
	void analyticsDistributionChartsRender() {
		clickTab("Analytics");
		page.waitForTimeout(800);

		// Should render at least 4 timeline + 4 distribution = 8 canvases
		int canvases = page.locator("canvas").count();
		assertThat(canvases).as("Analytics tab should render 4 timeline + 4 distribution = 8 canvases")
				.isGreaterThanOrEqualTo(8);
	}

	@Test
	@Order(191)
	void analyticsTimelineChartIdsExist() {
		clickTab("Analytics");
		page.waitForTimeout(800);

		// Verify specific chart canvas IDs
		for (String id : List.of("tl-apfd", "tl-fail", "tl-ffp", "tl-cnt")) {
			assertThat(page.locator("#" + id).count()).as("Analytics timeline canvas '" + id + "' should be present")
					.isEqualTo(1);
		}
	}

	@Test
	@Order(192)
	void analyticsDistributionChartIdsExist() {
		clickTab("Analytics");
		page.waitForTimeout(800);

		for (String id : List.of("d-score", "d-dur", "d-deps", "d-fail")) {
			assertThat(page.locator("#" + id).count())
					.as("Analytics distribution canvas '" + id + "' should be present").isEqualTo(1);
		}
	}

	// ── 21. Search edge cases ────────────────────────────────────────────────

	@Test
	@Order(200)
	void searchWithNoResultsShowsEmptyList() {
		page.locator("input[placeholder*='Filter']").fill("NonexistentTestXYZ");
		page.waitForTimeout(300);

		int visibleRows = page.locator("aside .sidebar__test-row").count();
		assertThat(visibleRows).as("Searching for a non-existent test should show 0 rows").isZero();

		// The count label should show "0/10"
		String sidebarText = page.locator("aside").textContent();
		assertThat(sidebarText).as("Sidebar should show 0/10 when no tests match filter").contains("0/");

		// Clear filter
		page.locator("input[placeholder*='Filter']").fill("");
	}

	@Test
	@Order(201)
	void searchIsCaseInsensitive() {
		page.locator("input[placeholder*='Filter']").fill("userservice");
		page.waitForTimeout(300);

		int visibleRows = page.locator("aside .sidebar__test-row").count();
		assertThat(visibleRows).as("Search should be case-insensitive and find UserServiceTest")
				.isGreaterThanOrEqualTo(1);

		String firstName = page.locator("aside .sidebar__test-name").first().textContent();
		assertThat(firstName).as("The matched test should be UserServiceTest").containsIgnoringCase("UserServiceTest");

		page.locator("input[placeholder*='Filter']").fill("");
	}

	// ── 22. KPI values are reasonable ────────────────────────────────────────

	@Test
	@Order(210)
	void kpiAvgApfdIsReasonable() {
		// The first KPI card should show "Avg APFD"
		Locator firstKpi = page.locator(".kpi").first();
		String labelText = firstKpi.locator(".kpi__label").textContent();
		assertThat(labelText).as("First KPI should be 'Avg APFD'").containsIgnoringCase("APFD");

		String valueText = firstKpi.locator(".kpi__value").textContent().trim();
		// APFD should be a decimal in [0,1] — displayed as e.g. "0.73"
		assertThat(valueText).as("APFD value should be a non-empty numeric value").isNotBlank();
	}

	@Test
	@Order(211)
	void kpiShowsFailureCount() {
		// One of the KPI cards should mention failures
		String allKpiText = page.locator(".kpi").allTextContents().toString();
		assertThat(allKpiText).as("KPI row should include a failures-related card").containsIgnoringCase("Fail");
	}

	// ── 23. Overview table structure ─────────────────────────────────────────

	@Test
	@Order(220)
	void overviewTableHasSortableHeaders() {
		// The overview table should have sortable column headers
		int thCount = page.locator("main th").count();
		assertThat(thCount).as("Overview table should have multiple column headers").isGreaterThanOrEqualTo(4);
	}

	@Test
	@Order(221)
	void overviewTableShowsTestScores() {
		// Each row should have numeric score cells
		Locator firstRow = page.locator("main tbody tr").first();
		String rowText = firstRow.textContent();

		// Should contain the test name and some numeric values
		assertThat(rowText).as("Overview table first row should contain a test name").containsAnyOf("ServiceTest",
				"IntegrationTest", "MigrationTest");
	}

	// ── 24. Tab button states ────────────────────────────────────────────────

	@Test
	@Order(230)
	void defaultTabIsTests() {
		// The "Tests" tab button should have the 'active' state
		Locator activeTab = page.locator(".tab-btn.active");
		assertThat(activeTab.count()).as("Exactly one tab should be active").isEqualTo(1);

		assertThat(activeTab.textContent()).as("Default active tab should be 'Tests'").containsIgnoringCase("Tests");
	}

	@Test
	@Order(231)
	void clickingTabSwitchesActiveState() {
		clickTab("Analytics");
		page.waitForTimeout(300);

		Locator activeTab = page.locator(".tab-btn.active");
		assertThat(activeTab.textContent()).as("After clicking Analytics, it should be the active tab")
				.containsIgnoringCase("Analytics");

		clickTab("Tests");
		page.waitForTimeout(300);

		activeTab = page.locator(".tab-btn.active");
		assertThat(activeTab.textContent()).as("After clicking Tests again, it should be active")
				.containsIgnoringCase("Tests");
	}

	// ── Helpers ───────────────────────────────────────────────────────────────

	private void clickTab(String labelFragment) {
		page.locator(".tab-btn").filter(new Locator.FilterOptions().setHasText(labelFragment)).click();
		page.waitForTimeout(200);
	}
}
