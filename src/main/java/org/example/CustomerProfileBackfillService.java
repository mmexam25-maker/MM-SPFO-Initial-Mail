package org.example;

import org.openqa.selenium.*;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.firefox.FirefoxDriver;
import org.openqa.selenium.firefox.FirefoxOptions;
import org.openqa.selenium.print.PrintOptions;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.Select;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.io.IOException;
import java.net.URI;
import java.nio.file.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class CustomerProfileBackfillService {

    private static final Path DG_DOWNLOAD_DIR = Path.of("customer-profile-sync", "dg-profile-pdfs").toAbsolutePath();
    private static final Path CUSTOMER_EXPORT_DIR = Path.of("customer-profile-sync", "customer-export").toAbsolutePath();
    private static final Pattern CUSTOMER_ID = Pattern.compile("[?&]customerId=(\\d+)");
    private static final Pattern PAGE_INFO = Pattern.compile("(?i)Page\\s+(\\d+)\\s+of\\s+(\\d+)");
    private static final Pattern ROW_RANGE = Pattern.compile("(?i)([\\d,]+)\\s+to\\s+([\\d,]+)\\s+of\\s+([\\d,]+)");
    private static final String CUSTOMER_STATE_SCHEMA = "alltime-customers-dg-sid-v11-membership-stcw-sid-dates";
    private static volatile SyncReport LAST_PARTIAL_REPORT;

    public static SyncReport getLastPartialReport() {
        return LAST_PARTIAL_REPORT;
    }

    private final Config cfg;
    private final Properties appConfig;
    private final CustomerProfilePdfParser pdfParser = new CustomerProfilePdfParser();
    private final ResumeEntryPdfParser resumeEntryPdfParser = new ResumeEntryPdfParser();

    public CustomerProfileBackfillService(Properties appConfig) {
        this.appConfig = new Properties();
        if (appConfig != null) this.appConfig.putAll(appConfig);
        this.cfg = Config.from(this.appConfig);
    }

    /**
     * Full flow:
     * MM login -> Customers -> View -> INDoS/password -> DG -> Update Seafarer Profile
     * -> View/Print profile -> save PDF -> parse DG data -> MM Edit -> update fields.
     *
     * This merged MMcasesBot flow always processes all customer pages.
     */
    public SyncReport syncCustomers() {
        return syncCustomers(null, 0);
    }

    /**
     * Customer sync uses the actual Mariners Mentor Customers tab, ACTIVE, Period=All Time
     * when no explicit customer IDs are supplied.
     *
     * When customer IDs are supplied (for example from an MM Membership service case),
     * ONLY those customer IDs are processed.
     */
    public SyncReport syncCustomers(Collection<String> caseCustomerIds, int sourceCaseCount) {
        return syncCustomers(caseCustomerIds, sourceCaseCount, null);
    }

    /**
     * MM Membership / case-triggered flow.
     *
     * Sequence:
     * MM Customer -> INDoS/password -> DG profile -> normalized Google Sheets
     * -> update the same MM Customer profile.
     *
     * An explicit Membership service case is treated as a fresh operator request, so the selected
     * customer is re-checked even if the historical sync already marked it handled.
     */
    public SyncReport syncSelectedCustomers(Collection<String> customerIds, int sourceCaseCount) {
        if (customerIds == null || customerIds.isEmpty()) {
            return new SyncReport(
                    sourceCaseCount, 0, 0, 0, 0, 0, 0, 0,
                    Collections.emptyList(), Collections.emptyList(), Collections.emptyList()
            );
        }

        LinkedHashSet<String> cleanIds = new LinkedHashSet<>();
        for (String id : customerIds) {
            String value = id == null ? "" : id.trim();
            if (value.matches("\\d+")) {
                cleanIds.add(value);
            }
        }

        if (cleanIds.isEmpty()) {
            return new SyncReport(
                    sourceCaseCount, 0, 0, 0, 0, 0, 0, 0,
                    Collections.emptyList(), Collections.emptyList(), Collections.emptyList()
            );
        }

        return syncCustomers(cleanIds, sourceCaseCount, null, false);
    }

    /**
     * Same customer sync, with lightweight progress callbacks so MMcasesBot can
     * email a STARTED/PROGRESS/PARTIAL report while a very large 4,000+ customer
     * run is still in progress.
     */
    public SyncReport syncCustomers(
            Collection<String> caseCustomerIds,
            int sourceCaseCount,
            ProgressListener progressListener
    ) {
        return syncCustomers(caseCustomerIds, sourceCaseCount, progressListener, false);
    }

    /** Lightweight watcher used after the full historical run: only the newest
     * first-page customer IDs are checked, and the persistent handled-state
     * causes already completed customers to be skipped immediately. */
    public SyncReport syncNewestCustomers() {
        return syncCustomers(null, 0, null, true);
    }

    private SyncReport syncCustomers(
            Collection<String> caseCustomerIds,
            int sourceCaseCount,
            ProgressListener progressListener,
            boolean newestOnly
    ) {
        Config cfg = this.cfg;
        if (!cfg.isEnabled()) {
            throw new IllegalStateException("Customer profile sync is disabled. Set customer.profile.sync.enabled=true");
        }
        require(cfg.getDashboardUsername(), "dashboard username");
        require(cfg.getDashboardPassword(), "dashboard password");
        require(cfg.getDashboardUrl(), "dashboard URL");
        require(cfg.getDgLoginUrl(), "DG Profile URL");

        try {
            Files.createDirectories(DG_DOWNLOAD_DIR);
            Files.createDirectories(CUSTOMER_EXPORT_DIR);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot create customer/DG working folders", e);
        }

        ChromeOptions options = new ChromeOptions();
        if (cfg.isHeadless()) options.addArguments("--headless=new");
        options.addArguments(
                "--start-maximized",
                "--disable-notifications",
                "--disable-popup-blocking",
                "--disable-extensions",
                "--disable-background-networking",
                "--disable-sync",
                "--disable-default-apps",
                "--no-first-run"
        );
        Map<String, Object> prefs = new HashMap<>();
        prefs.put("download.default_directory", CUSTOMER_EXPORT_DIR.toString());
        prefs.put("download.prompt_for_download", false);
        prefs.put("download.directory_upgrade", true);
        prefs.put("plugins.always_open_pdf_externally", true);
        options.setExperimentalOption("prefs", prefs);

        WebDriver driver = new ChromeDriver(options);
        WebDriverWait mmWait = new WebDriverWait(driver, Duration.ofSeconds(cfg.getWaitSeconds()));

        int customersFound = 0;
        int alreadyHandled = 0;
        int scanned = 0;
        int dgRead = 0;
        int updated = 0;
        int noChange = 0;
        int skipped = 0;
        List<String> notes = new ArrayList<>();
        List<MissingCredential> missingCredentials = new ArrayList<>();
        List<MissingField> missingFields = new ArrayList<>();
        LAST_PARTIAL_REPORT = null;

        Properties state = loadCustomerSyncState();
        ResumeEntrySheetService resumeSheet = new ResumeEntrySheetService(appConfig);

        try {
            loginMarinersMentor(driver, mmWait, cfg);

            // Normal mode discovers customers from Customers -> ACTIVE -> All Time.
            // ML membership mode supplies exact customer IDs from the Case record and
            // therefore skips the 10,000+ customer discovery completely.
            boolean selectedOnly =
                    caseCustomerIds != null && !caseCustomerIds.isEmpty();

            List<String> customerIds;
            if (selectedOnly) {
                LinkedHashSet<String> selected = new LinkedHashSet<>();
                for (String customerId : caseCustomerIds) {
                    String value = customerId == null ? "" : customerId.trim();
                    if (value.matches("\\d+")) {
                        selected.add(value);
                    }
                }
                customerIds = new ArrayList<>(selected);
                customersFound = customerIds.size();
                System.out.println(
                        "MM MEMBERSHIP SERVICE SYNC | SOURCE = CASE SERVICE"
                                + " | Selected Customer IDs: " + customerIds
                );
            } else if (newestOnly) {
                System.out.println("MM NEW CUSTOMER WATCH | SOURCE = CUSTOMERS TAB | ACTIVE | PERIOD=ALL TIME | FIRST PAGE");
                customerIds = discoverNewestActiveCustomerIds(driver, mmWait, cfg);
                customersFound = customerIds.size();
                System.out.println("MM NEW CUSTOMER WATCH | IDs inspected: " + customersFound);
            } else {
                System.out.println("MM CUSTOMER DISCOVERY | SOURCE = CUSTOMERS TAB | ACTIVE | PERIOD=ALL TIME | PAGE SIZE 100");
                customerIds = discoverAllTimeActiveCustomerIds(driver, mmWait, cfg);
                customersFound = customerIds.size();
                System.out.println("MM CUSTOMER DISCOVERY | ALL TIME customers found: " + customersFound);
            }

            List<String> toProcess = new ArrayList<>();
            for (String customerId : customerIds) {
                if (selectedOnly) {
                    // Explicit Membership service case = fresh request. Re-check this selected customer
                    // even if a previous historical run already marked it handled.
                    //
                    // Remove the permanent handled flag BEFORE the fresh ML attempt.
                    // If this run fails, the ordinary daily customer retry can pick it up;
                    // if it succeeds, the normal success path marks it handled again.
                    state.remove(customerStateKey(customerId));
                    state.remove(credentialAttemptKey(customerId));
                    state.remove(retryAttemptKey(customerId));
                    saveCustomerSyncState(state);
                    toProcess.add(customerId);
                } else if (isPermanentlyHandled(state, customerId)) {
                    alreadyHandled++;
                } else if (newestOnly && wasRetryIssueCheckedToday(state, customerId)) {
                    // The lightweight 5-minute watcher must not hammer the same
                    // customer repeatedly on the same day. Credential problems and
                    // transient DG/MM/Sheet failures are retried by the next daily run.
                    alreadyHandled++;
                } else {
                    toProcess.add(customerId);
                }
            }

            System.out.println("MM CUSTOMER DISCOVERY COMPLETE | All Time customers: " + customersFound
                    + " | Already handled: " + alreadyHandled
                    + " | New/retry customers: " + toProcess.size());

            notifyProgress(progressListener, new SyncProgress(
                    sourceCaseCount,
                    customersFound,
                    alreadyHandled,
                    toProcess.size(),
                    0,
                    0,
                    0,
                    0,
                    0
            ));
            LAST_PARTIAL_REPORT = snapshotReport(sourceCaseCount, customersFound, alreadyHandled,
                    0, 0, 0, 0, 0, notes, missingCredentials, missingFields);

            for (String customerId : toProcess) {
                if (!selectedOnly && Main.isMembershipPriorityPending()) {
                    Main.requestCustomerBackfillResumeAfterMembership();
                    String pauseNote =
                            "Customer DG sync paused before Customer ID "
                                    + customerId
                                    + " because a priority Membership service case is waiting.";
                    notes.add(pauseNote);
                    System.out.println(
                            "CUSTOMER PROFILE SYNC PAUSED FOR PRIORITY MEMBERSHIP"
                                    + " | Next Customer ID: " + customerId
                    );
                    break;
                }

                scanned++;
                String customerUrl = trimSlash(cfg.getDashboardUrl())
                        + "/dashboard/customer/view?customerId=" + customerId;

                try {
                    driver.get(customerUrl);
                    mmWait.until(d -> d.getCurrentUrl().contains("/dashboard/customer/view")
                            && pageContains(d, "View Customer"));

                    MmCustomer mm = readMmCustomer(driver, mmWait);

                    // Membership rule: the member must appear in Customer Profile immediately,
                    // even before DG login / SID CAPTCHA. This guarantees that a missing/wrong
                    // INDoS password or a temporary DG/SID failure never prevents the member
                    // from being recorded in the Membership workbook.
                    if (selectedOnly && resumeSheet.isEnabled()) {
                        try {
                            ResumeEntryData mmSeed = resumeDataFromMmCustomer(mm);
                            ResumeEntrySheetService.SheetWriteResult seedWrite = resumeSheet.upsert(
                                    customerId,
                                    mm.createdBy,
                                    mm.createdAt,
                                    "",
                                    mmSeed
                            );
                            System.out.println(
                                    "MEMBERSHIP CUSTOMER PROFILE SAVED TO SHEET"
                                            + " | Customer ID: " + customerId
                                            + " | Row: " + seedWrite.row()
                                            + " | Stage: MM CUSTOMER VIEW (BEFORE DG)"
                            );
                        } catch (Exception seedSheetError) {
                            notes.add(
                                    "Customer " + customerLabel(customerId, mm.customerName)
                                            + ": Membership Customer Profile pre-DG sheet write failed - "
                                            + safeMessage(seedSheetError)
                            );
                            System.err.println(
                                    "MEMBERSHIP CUSTOMER PROFILE PRE-DG SHEET WRITE FAILED"
                                            + " | Customer ID: " + customerId
                                            + " | " + safeMessage(seedSheetError)
                            );
                        }
                    }

                    if (blank(mm.indosNo)) {
                        skipped++;
                        notes.add("Customer " + customerLabel(customerId, mm.customerName)
                                + ": INDoS number missing - LEFT UNCHANGED");
                        missingCredentials.add(new MissingCredential(
                                customerId,
                                mm.customerName,
                                "",
                                "No INDoS No",
                                mm.createdBy,
                                mm.createdAt
                        ));
                        // Keep in retry pool, but the 5-minute new-customer watcher
                        // will not retry this same credential issue again today.
                        markCredentialIssueCheckedToday(state, customerId, "NO_INDOS_NO");
                        continue;
                    }

                    driver.get(customerUrl);
                    mmWait.until(d -> d.getCurrentUrl().contains("/dashboard/customer/view")
                            && pageContains(d, "View Customer"));

                    String indosPassword = revealAndReadIndosPassword(driver, mmWait);
                    if (blank(indosPassword)) {
                        skipped++;
                        notes.add("Customer " + customerLabel(customerId, mm.customerName)
                                + ": NO INDoS PASSWORD - LEFT UNCHANGED");
                        missingCredentials.add(new MissingCredential(
                                customerId,
                                mm.customerName,
                                mm.indosNo,
                                "No INDoS Password",
                                mm.createdBy,
                                mm.createdAt
                        ));
                        markCredentialIssueCheckedToday(state, customerId, "NO_INDOS_PASSWORD");
                        continue;
                    }

                    DgResult dg;
                    SidLookupResult sidLookup = SidLookupResult.notAttempted();
                    try {
                        dg = fetchDgProfile(
                                driver, cfg, mm.indosNo, indosPassword, customerId,
                                mm.dob(), selectedOnly);
                    } catch (WrongIndosPasswordException wrongPassword) {
                        skipped++;
                        notes.add("Customer " + customerLabel(customerId, mm.customerName)
                                + ": WRONG INDoS PASSWORD - LEFT UNCHANGED; WILL RETRY DAILY");
                        missingCredentials.add(new MissingCredential(
                                customerId,
                                mm.customerName,
                                mm.indosNo,
                                "Wrong INDoS Password",
                                mm.createdBy,
                                mm.createdAt
                        ));
                        markCredentialIssueCheckedToday(state, customerId, "WRONG_INDOS_PASSWORD");
                        indosPassword = null;
                        continue;
                    }

                    if (dg == null || dg.data == null) {
                        indosPassword = null;
                        skipped++;
                        notes.add("Customer " + customerLabel(customerId, mm.customerName)
                                + ": DG profile PDF could not be read - WILL RETRY ON NEXT DAILY RUN");
                        if (newestOnly) {
                            markRetryIssueCheckedToday(state, customerId, "DG_PROFILE_NOT_READABLE");
                        }
                        continue;
                    }
                    dgRead++;

                    // DG printable profile often omits SID. When the MM customer and
                    // DG profile together still do not provide complete SID details,
                    // open the official public SID Checker in a VISIBLE browser.
                    // The operator types the CAPTCHA; after submission the bot reads
                    // SID number/issue/expiry and merges it into the same customer.
                    // Membership workflow: always open the public SID checker visibly.
                    // For ordinary customer backfill, open it only when SID details are incomplete.
                    if (selectedOnly || shouldLookupSid(mm, dg.data)) {
                        System.out.println("SID CHECKER OPENING | Customer ID: " + customerId
                                + (selectedOnly ? " | Membership case" : " | Missing SID data"));
                        sidLookup = lookupSidFromPublicChecker(mm.indosNo, customerId);
                        if (sidLookup.sid() != null && !sidLookup.sid().isEmpty()) {
                            dg = mergeSidIntoDgResult(dg, sidLookup);
                        } else if (sidLookup.attempted()) {
                            notes.add("Customer " + customerLabel(customerId, mm.customerName)
                                    + ": " + sidLookup.detail());
                        }
                    }

                    if (!sameIndos(mm.indosNo, dg.data.indosNo())) {
                        indosPassword = null;
                        skipped++;
                        notes.add("Customer " + customerLabel(customerId, mm.customerName)
                                + ": DG PDF INDoS mismatch - LEFT UNCHANGED");
                        // Treat identity mismatch as terminal: never risk writing wrong DG data later automatically.
                        markCustomerHandled(state, customerId, "INDOS_MISMATCH");
                        continue;
                    }

                    ResumeEntrySheetService.SheetWriteResult sheetWrite =
                            ResumeEntrySheetService.SheetWriteResult.disabled();

                    // User rule:
                    //   Membership service -> DG + SID -> 3 normalized sheets -> MM Customer + RPSL links.
                    //   Other customer      -> DG + SID -> MM Customer only (no sheet write).
                    boolean writeNormalizedSheets = selectedOnly;
                    boolean resumeSheetOk = true;

                    try {
                        if (writeNormalizedSheets) {
                            ResumeEntryData resumeData = dg.resumeData == null
                                    ? null
                                    : dg.resumeData.withFallback(dg.data);

                            if (resumeSheet.isEnabled() && resumeData != null) {
                                sheetWrite = resumeSheet.upsert(
                                        customerId,
                                        mm.createdBy,
                                        mm.createdAt,
                                        indosPassword,
                                        resumeData
                                );
                                System.out.println("MEMBERSHIP CUSTOMER SHEETS UPDATED | Customer ID: " + customerId
                                        + " | Row: " + sheetWrite.row()
                                        + " | Cells filled: " + sheetWrite.cellsFilled()
                                        + " | Vessel links: " + sheetWrite.vesselCellLinks().size());
                            } else if (resumeSheet.isEnabled()) {
                                resumeSheetOk = false;
                                notes.add("Customer " + customerLabel(customerId, mm.customerName)
                                        + ": Membership sheet data was unavailable - WILL RETRY");
                            }
                        } else {
                            System.out.println("NON-ML CUSTOMER | Customer ID: " + customerId
                                    + " | DG + SID -> MM Customer only | 3-sheet write skipped by rule");
                        }
                    } catch (Exception sheetError) {
                        resumeSheetOk = false;
                        notes.add("Customer " + customerLabel(customerId, mm.customerName)
                                + ": Customer Google Sheet update failed - " + safeMessage(sheetError)
                                + " - WILL RETRY");
                        System.err.println("CUSTOMER GOOGLE SHEET UPDATE FAILED | Customer ID: " + customerId
                                + " | " + safeMessage(sheetError));
                    } finally {
                        // Keep the password in memory only long enough for this customer's DG/ML processing.
                        indosPassword = null;
                    }

                    List<String> missingForCustomer = findMissingProfileFields(
                            mm,
                            dg.data,
                            writeNormalizedSheets,
                            sheetWrite
                    );
                    if (!missingForCustomer.isEmpty()) {
                        missingFields.add(new MissingField(
                                customerId,
                                mm.customerName,
                                mm.indosNo,
                                String.join(", ", missingForCustomer),
                                sidLookup.attempted() ? sidLookup.detail() : "DG/SID source did not provide these values",
                                mm.createdBy,
                                mm.createdAt
                        ));
                        System.out.println("MISSING PROFILE DATA | Customer ID: " + customerId
                                + " | " + String.join(", ", missingForCustomer));
                    }

                    boolean retryableMissingProfileData =
                            hasRetryableMissingProfileData(missingForCustomer);

                    driver.switchTo().window(dg.mmWindow);
                    UpdateResult result = updateMarinersMentorFromDg(
                            driver, mmWait, cfg, customerUrl, dg.data, sheetWrite);
                    if (result.changed) {
                        notes.add("Customer " + customerLabel(customerId, mm.customerName)
                                + ": dashboard updated (" + result.changedFields + " fields)");
                        // A customer is a SUCCESS only when both MM and normalized Google Sheets are complete.
                        if (resumeSheetOk && !retryableMissingProfileData) {
                            updated++;
                            markCustomerHandled(state, customerId,
                                    selectedOnly ? "ML_UPDATED+SHEETS+SID" : "UPDATED+DG+SID");
                        } else {
                            skipped++;
                            if (newestOnly) {
                                markRetryIssueCheckedToday(
                                        state,
                                        customerId,
                                        retryableMissingProfileData
                                                ? "MISSING_PROFILE_DATA"
                                                : "RESUME_SHEET_UPDATE_FAILED"
                                );
                            }
                        }
                    } else {
                        notes.add("Customer " + customerLabel(customerId, mm.customerName)
                                + ": DG checked; existing MM values kept; no duplicate update submitted");
                        if (resumeSheetOk && !retryableMissingProfileData) {
                            noChange++;
                            markCustomerHandled(state, customerId,
                                    selectedOnly ? "ML_CHECKED+SHEETS+SID" : "CHECKED+DG+SID");
                        } else {
                            skipped++;
                            if (newestOnly) {
                                markRetryIssueCheckedToday(
                                        state,
                                        customerId,
                                        retryableMissingProfileData
                                                ? "MISSING_PROFILE_DATA"
                                                : "RESUME_SHEET_UPDATE_FAILED"
                                );
                            }
                        }
                    }

                } catch (Exception customerError) {
                    skipped++;
                    String failure = safeMessage(customerError);
                    notes.add("Customer ID " + customerId + " failed: " + failure
                            + " - WILL RETRY ON NEXT DAILY RUN");
                    System.err.println("PROFILE SYNC | Customer ID " + customerId
                            + " failed: " + failure
                            + (newestOnly ? " - PAUSED UNTIL NEXT DAILY RUN" : " - WILL RETRY ON NEXT DAILY RUN"));
                    if (newestOnly) {
                        markRetryIssueCheckedToday(state, customerId, "TRANSIENT_FAILURE");
                    }
                    closeExtraTabsAndReturnToMm(driver);
                } finally {
                    LAST_PARTIAL_REPORT = snapshotReport(sourceCaseCount, customersFound, alreadyHandled,
                            scanned, dgRead, updated, noChange, skipped, notes, missingCredentials, missingFields);
                    if (progressListener != null) {
                        notifyProgress(progressListener, new SyncProgress(
                                sourceCaseCount,
                                customersFound,
                                alreadyHandled,
                                toProcess.size(),
                                scanned,
                                dgRead,
                                updated,
                                noChange,
                                skipped
                        ));
                    }
                }
            }

        } finally {
            saveCustomerSyncState(state);
            if (cfg.isKeepBrowserOpen()) {
                System.out.println("MM-DG AUTO: browser left open. Use IntelliJ STOP (red square) when finished.");
            } else {
                try { driver.quit(); } catch (Exception ignored) {}
            }
        }

        SyncReport finalReport = snapshotReport(sourceCaseCount, customersFound, alreadyHandled,
                scanned, dgRead, updated, noChange, skipped, notes, missingCredentials, missingFields);
        LAST_PARTIAL_REPORT = finalReport;
        return finalReport;
    }

    private SyncReport snapshotReport(int sourceCaseCount, int customersFound, int alreadyHandled,
                                      int scanned, int dgRead, int updated, int noChange, int skipped,
                                      List<String> notes, List<MissingCredential> missingCredentials,
                                      List<MissingField> missingFields) {
        return new SyncReport(sourceCaseCount, customersFound, alreadyHandled, scanned, dgRead, updated,
                noChange, skipped, List.copyOf(notes), List.copyOf(missingCredentials),
                List.copyOf(missingFields));
    }

    private List<String> discoverNewestActiveCustomerIds(WebDriver driver, WebDriverWait wait, Config cfg) {
        openAllTimeCustomersPage(driver, wait, cfg);
        ensurePageSize(driver, wait, 100);
        int total = readTotalCustomerCount(driver);
        int expected = total <= 0 ? 100 : Math.min(100, total);
        LinkedHashSet<String> ids = collectCustomerIdsFromCurrentGridPage(driver, expected);
        System.out.println("MM NEW CUSTOMER WATCH PAGE 1 | Customer IDs: " + ids.size() + "/" + expected);
        return new ArrayList<>(ids);
    }

    /**
     * Discover every ACTIVE customer under the dashboard's All Time period.
     * The list is collected first; customer view pages are processed afterwards by direct customerId URL.
     * This covers the full All Time list regardless of whether a customer has a case.
     */
    private List<String> discoverAllTimeActiveCustomerIds(WebDriver driver, WebDriverWait wait, Config cfg) {
        openAllTimeCustomersPage(driver, wait, cfg);
        ensurePageSize(driver, wait, 100);

        // Fast/reliable path: the dashboard's Export button returns the complete
        // filtered customer list and avoids AG-Grid virtual-row loss on 10,000+ rows.
        int pagerTotal = readTotalCustomerCount(driver);
        List<String> exportedIds = tryDiscoverAllCustomerIdsViaExport(driver, wait, pagerTotal);
        if (!exportedIds.isEmpty()) {
            System.out.println("MM CUSTOMER DISCOVERY VIA EXPORT | Customer IDs: " + exportedIds.size());
            return exportedIds;
        }

        // IMPORTANT: The customer list is sourced ONLY from the Customers grid itself.
        // We read the actual Customer ID cells, for example:
        // <span id="cell-id-749" class="ag-cell-value">11591</span>
        // No Case ID / case-linked customer ID is used for this customer backfill.
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        int safety = 0;
        int totalCustomers = pagerTotal > 0 ? pagerTotal : readTotalCustomerCount(driver);
        if (totalCustomers > 0) {
            System.out.println("MM CUSTOMER DISCOVERY TOTAL FROM PAGER: " + totalCustomers);
        }

        while (safety++ < 200) {
            PageState pageState = readPageState(driver);

            // Page Size is explicitly forced to 100. Do not trust the number of
            // currently rendered AG-Grid rows: virtualization commonly renders only ~20-25.
            int expectedOnPage;
            if (totalCustomers > 0 && pageState.totalPages > 0) {
                if (pageState.page < pageState.totalPages) {
                    expectedOnPage = 100;
                } else {
                    expectedOnPage = totalCustomers - ((pageState.totalPages - 1) * 100);
                    if (expectedOnPage <= 0 || expectedOnPage > 100) expectedOnPage = 100;
                }
            } else {
                expectedOnPage = currentPageRowCount(driver);
            }
            if (expectedOnPage <= 0) break;

            LinkedHashSet<String> pageIds = new LinkedHashSet<>();
            for (int attempt = 1; attempt <= 3 && pageIds.size() < expectedOnPage; attempt++) {
                pageIds.addAll(collectCustomerIdsFromCurrentGridPage(driver, expectedOnPage));
                if (pageIds.size() < expectedOnPage) {
                    System.out.println("MM CUSTOMER DISCOVERY RETRY " + attempt + "/3 | Page " + pageState.page
                            + " | Found " + pageIds.size() + "/" + expectedOnPage
                            + " | Re-scrolling AG Grid virtual rows...");
                    sleep(300);
                }
            }

            ids.addAll(pageIds);

            System.out.println("MM CUSTOMER DISCOVERY PAGE " + pageState.page + "/" + pageState.totalPages
                    + " | Customer IDs on page: " + pageIds.size() + "/" + expectedOnPage
                    + " | Total Customer IDs collected: " + ids.size());

            if (pageIds.size() < expectedOnPage) {
                throw new IllegalStateException(
                        "AG Grid customer ID collection incomplete on page " + pageState.page
                                + ": expected " + expectedOnPage + " but collected " + pageIds.size()
                                + ". Stopping instead of silently skipping customers.");
            }

            // Return grid scroll to top before touching the pager; some AG-Grid
            // versions otherwise report the paging control as not visible.
            resetCustomerGridVerticalScroll(driver);
            sleep(120);
            WebElement next = findNextPageButton(driver);
            if (pageState.page < pageState.totalPages && (next == null || isAriaDisabled(next))) {
                // Do not silently finish at page 1/107. Retry a raw data-ref lookup.
                try {
                    List<WebElement> rawNext = driver.findElements(By.cssSelector("[data-ref='btNext']"));
                    if (!rawNext.isEmpty()) next = rawNext.get(0);
                } catch (Exception ignored) {}
            }
            if (pageState.page < pageState.totalPages && (next == null || isAriaDisabled(next))) {
                throw new IllegalStateException("Next Page control unavailable on page "
                        + pageState.page + "/" + pageState.totalPages
                        + "; stopping instead of processing only the first 100 customers");
            }
            if (next == null || isAriaDisabled(next)) break;

            int beforePage = pageState.page;
            String beforeFirstCustomerId = pageIds.stream().findFirst().orElse("");
            click(driver, next);
            wait.until(d -> {
                PageState now = readPageState(d);
                if (now.page > beforePage) return true;
                String firstNow = firstRenderedCustomerId(d);
                return !blank(firstNow) && !firstNow.equals(beforeFirstCustomerId);
            });
            wait.until(d -> !visibleRows(d).isEmpty());
            sleep(250);
        }

        // Put the grid back at the top simply for cleaner diagnostics/screenshots.
        resetCustomerGridVerticalScroll(driver);
        return new ArrayList<>(ids);
    }

    private List<String> tryDiscoverAllCustomerIdsViaExport(
            WebDriver driver,
            WebDriverWait wait,
            int expectedTotal
    ) {
        try {
            Files.createDirectories(CUSTOMER_EXPORT_DIR);
            try (java.util.stream.Stream<Path> stream = Files.list(CUSTOMER_EXPORT_DIR)) {
                stream.filter(Files::isRegularFile).forEach(path -> {
                    try { Files.deleteIfExists(path); } catch (Exception ignored) {}
                });
            }

            WebElement export = firstVisible(driver,
                    By.xpath("//button[normalize-space()='Export']"),
                    By.xpath("//*[normalize-space()='Export']/ancestor::button[1]"),
                    By.xpath("//*[normalize-space()='Export' and (self::a or self::div or self::span)]"));
            if (export == null) {
                System.out.println("MM CUSTOMER EXPORT | Export control not found - using grid fallback");
                return Collections.emptyList();
            }

            System.out.println("MM CUSTOMER EXPORT | Downloading ACTIVE / All Time customer list...");
            click(driver, export);

            Path downloaded = waitForCustomerExportFile(Duration.ofSeconds(120));
            if (downloaded == null) {
                System.out.println("MM CUSTOMER EXPORT | No XLSX/CSV download detected - using grid fallback");
                return Collections.emptyList();
            }

            List<String> ids = CustomerExportReader.readCustomerIds(downloaded);
            LinkedHashSet<String> unique = new LinkedHashSet<>(ids);

            System.out.println("MM CUSTOMER EXPORT | File: " + downloaded.getFileName()
                    + " | Unique Customer IDs: " + unique.size()
                    + (expectedTotal > 0 ? " | Pager total: " + expectedTotal : ""));

            if (expectedTotal > 0 && unique.size() != expectedTotal) {
                System.err.println("MM CUSTOMER EXPORT COUNT MISMATCH | Expected " + expectedTotal
                        + " but export contained " + unique.size() + " IDs - using grid fallback");
                return Collections.emptyList();
            }
            return new ArrayList<>(unique);
        } catch (Exception e) {
            System.err.println("MM CUSTOMER EXPORT FAILED | " + safeMessage(e)
                    + " | Using grid fallback");
            return Collections.emptyList();
        }
    }

    private Path waitForCustomerExportFile(Duration timeout) {
        Instant end = Instant.now().plus(timeout);
        while (Instant.now().isBefore(end)) {
            try (java.util.stream.Stream<Path> stream = Files.list(CUSTOMER_EXPORT_DIR)) {
                Optional<Path> ready = stream
                        .filter(Files::isRegularFile)
                        .filter(p -> {
                            String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
                            return (n.endsWith(".xlsx") || n.endsWith(".xls") || n.endsWith(".csv"))
                                    && !n.endsWith(".crdownload") && !n.endsWith(".part");
                        })
                        .max(Comparator.comparingLong(this::modifiedTime));
                if (ready.isPresent()) {
                    Path p = ready.get();
                    long size1 = Files.size(p);
                    sleep(800);
                    long size2 = Files.size(p);
                    if (size1 > 0 && size1 == size2) return p;
                }
            } catch (Exception ignored) {}
            sleep(500);
        }
        return null;
    }

    /**
     * AG Grid virtualises rows, so only ~20-25 rows may exist in the DOM at one time even when
     * Page Size is 100. Scroll through the grid viewport and collect the actual Customer ID cells.
     */
    private LinkedHashSet<String> collectCustomerIdsFromCurrentGridPage(WebDriver driver, int expectedOnPage) {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        JavascriptExecutor js = (JavascriptExecutor) driver;

        collectRenderedCustomerIdCells(driver, ids);
        if (ids.size() >= expectedOnPage) return ids;

        // Newer AG Grid builds may use a dedicated vertical scrollbar viewport
        // while .ag-body-viewport itself reports little/no scroll range. Scan every
        // plausible vertical scroller and use whichever actually has scrollable height.
        List<WebElement> scrollers = new ArrayList<>();
        for (String selector : List.of(
                ".ag-body-vertical-scroll-viewport",
                ".ag-body-viewport",
                ".ag-center-cols-viewport")) {
            try {
                for (WebElement e : driver.findElements(By.cssSelector(selector))) {
                    if (e != null && e.isDisplayed() && !scrollers.contains(e)) scrollers.add(e);
                }
            } catch (Exception ignored) {}
        }

        // Prefer the element with the largest true vertical scroll range.
        scrollers.sort((a, b) -> Long.compare(scrollRange(js, b), scrollRange(js, a)));

        for (WebElement scroller : scrollers) {
            if (ids.size() >= expectedOnPage) break;

            long range = scrollRange(js, scroller);
            if (range <= 2) continue;

            long clientHeight = Math.max(1, jsLong(js.executeScript(
                    "return arguments[0].clientHeight || 1;", scroller)));
            long step = Math.max(80, clientHeight / 3);

            for (long top = 0; top <= range && ids.size() < expectedOnPage; top += step) {
                setGridScrollTop(js, scroller, Math.min(top, range));
                sleep(120);
                collectRenderedCustomerIdCells(driver, ids);
            }

            setGridScrollTop(js, scroller, range);
            sleep(180);
            collectRenderedCustomerIdCells(driver, ids);

            setGridScrollTop(js, scroller, 0);
            sleep(100);
        }

        return ids;
    }

    private long scrollRange(JavascriptExecutor js, WebElement element) {
        try {
            long client = Math.max(0, jsLong(js.executeScript(
                    "return arguments[0].clientHeight || 0;", element)));
            long scroll = Math.max(0, jsLong(js.executeScript(
                    "return arguments[0].scrollHeight || 0;", element)));
            return Math.max(0, scroll - client);
        } catch (Exception ignored) {
            return 0;
        }
    }

    private void setGridScrollTop(JavascriptExecutor js, WebElement element, long top) {
        try {
            js.executeScript(
                    "arguments[0].scrollTop=arguments[1];"
                            + "arguments[0].dispatchEvent(new Event('scroll',{bubbles:true}));",
                    element, top);
        } catch (Exception ignored) {}
    }

    private void collectRenderedCustomerIdCells(WebDriver driver, Set<String> ids) {
        List<WebElement> cells = driver.findElements(By.cssSelector(
                "span[id^='cell-id-'],div[id^='cell-id-'],[id^='cell-id-'].ag-cell-value"));
        for (WebElement cell : cells) {
            try {
                if (!cell.isDisplayed()) continue;
                String text = nvl(cell.getText()).trim();
                Matcher m = Pattern.compile("^(\\d+)$").matcher(text);
                if (m.find()) ids.add(m.group(1));
            } catch (StaleElementReferenceException ignored) {}
        }
    }

    private String firstRenderedCustomerId(WebDriver driver) {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        collectRenderedCustomerIdCells(driver, ids);
        return ids.stream().findFirst().orElse("");
    }

    private void resetCustomerGridVerticalScroll(WebDriver driver) {
        try {
            WebElement viewport = firstVisible(driver,
                    By.cssSelector(".ag-body-viewport"),
                    By.cssSelector(".ag-center-cols-viewport"));
            if (viewport != null) {
                ((JavascriptExecutor) driver).executeScript("arguments[0].scrollTop=0;", viewport);
            }
        } catch (Exception ignored) {}
    }

    private long jsLong(Object value) {
        if (value instanceof Number n) return n.longValue();
        try { return Long.parseLong(String.valueOf(value)); }
        catch (Exception ignored) { return 0; }
    }

    private void openAllTimeCustomersPage(WebDriver driver, WebDriverWait wait, Config cfg) {
        String base = trimSlash(cfg.getDashboardUrl());
        driver.get(base + "/dashboard/customer?period=all");
        wait.until(d -> d.getCurrentUrl().contains("/dashboard/customer") && pageContains(d, "Customers"));

        // Force the ACTIVE customer tab so archived records are not mixed into the All Time run.
        try {
            WebElement active = firstVisible(driver,
                    By.xpath("//button[normalize-space()='ACTIVE']"),
                    By.xpath("//*[@role='tab' and normalize-space()='ACTIVE']"),
                    By.xpath("//*[normalize-space()='ACTIVE' and (self::button or @role='tab')]")
            );
            if (active != null) {
                click(driver, active);
                sleep(250);
            }
        } catch (Exception ignored) {}

        wait.until(d -> !visibleRows(d).isEmpty());

        // The current dashboard supports ?period=all.  If a UI build ignores the
        // query string, use the visible MUI Period picker and explicitly choose All Time.
        boolean allTimeShown = false;
        try {
            String body = driver.findElement(By.tagName("body")).getText();
            allTimeShown = body.matches("(?is).*\\bPeriod\\b.*\\bAll Time\\b.*");
        } catch (Exception ignored) {}

        if (!driver.getCurrentUrl().toLowerCase(Locale.ROOT).contains("period=all") || !allTimeShown) {
            try {
                WebElement periodPicker = firstVisible(driver,
                        By.xpath("//label[normalize-space()='Period']/following::*[@role='combobox'][1]"),
                        By.xpath("//*[contains(normalize-space(.),'Period')]/following::*[@role='combobox'][1]"),
                        By.xpath("//*[normalize-space()='Week' or normalize-space()='Month' or normalize-space()='Year' or normalize-space()='All Time']/ancestor::*[@role='combobox' or self::div][1]")
                );
                if (periodPicker != null) click(driver, periodPicker);
                WebElement allTime = wait.until(d -> firstVisible(d,
                        By.xpath("//*[@role='option' and normalize-space()='All Time']"),
                        By.xpath("//li[normalize-space()='All Time']"),
                        By.xpath("//*[normalize-space()='All Time' and (@role='option' or self::li)]")
                ));
                if (allTime != null) click(driver, allTime);
                wait.until(d -> !visibleRows(d).isEmpty());
            } catch (Exception e) {
                System.out.println("MM: All Time picker fallback not needed/available - " + safeMessage(e));
            }
        }

        System.out.println("MM CUSTOMERS TAB READY | ACTIVE | PERIOD=ALL TIME");
    }

    private String customerIdFromGridRow(WebDriver driver, WebElement row, int absoluteRowIndex) {
        try {
            List<WebElement> idCells = row.findElements(By.cssSelector(
                    "[col-id='id'],[col-id='customer_id'],[col-id='customerId'],[col-id='customer_no'],[col-id='customerNo']"));
            for (WebElement cell : idCells) {
                Matcher m = Pattern.compile("\\b(\\d{2,})\\b").matcher(nvl(cell.getText()));
                if (m.find()) return m.group(1);
            }
        } catch (Exception ignored) {}

        try {
            Matcher m = Pattern.compile("^\\s*(\\d{2,})\\b").matcher(nvl(row.getText()));
            if (m.find()) return m.group(1);
        } catch (Exception ignored) {}

        // Some AG Grid builds put the first column in the pinned-left container.
        try {
            WebElement pinned = firstVisible(driver,
                    By.cssSelector(".ag-pinned-left-cols-container .ag-row[row-index='" + absoluteRowIndex + "']"),
                    By.cssSelector(".ag-center-cols-container .ag-row[row-index='" + absoluteRowIndex + "']"));
            if (pinned != null) {
                Matcher m = Pattern.compile("\\b(\\d{2,})\\b").matcher(nvl(pinned.getText()));
                if (m.find()) return m.group(1);
            }
        } catch (Exception ignored) {}
        return "";
    }

    private Path customerSyncStatePath() {
        String local = System.getenv("LOCALAPPDATA");
        Path dir;
        if (!blank(local)) {
            dir = Path.of(local, "MarinersMentor", "MMcasesBot");
        } else {
            dir = Path.of(System.getProperty("user.home", "."), ".marinersmentor", "MMcasesBot");
        }
        try { Files.createDirectories(dir); } catch (Exception ignored) {}
        return dir.resolve("customer-profile-sync.properties");
    }

    private Properties loadCustomerSyncState() {
        Properties p = new Properties();
        Path path = customerSyncStatePath();
        if (!Files.exists(path)) return p;
        try (java.io.InputStream in = Files.newInputStream(path)) {
            p.load(in);
        } catch (Exception e) {
            System.out.println("MM CUSTOMER STATE READ WARNING | " + safeMessage(e));
        }
        return p;
    }

    private void saveCustomerSyncState(Properties state) {
        if (state == null) return;
        Path path = customerSyncStatePath();
        try (java.io.OutputStream out = Files.newOutputStream(path,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            state.store(out, "Mariners Mentor customer DG sync - handled customer IDs");
        } catch (Exception e) {
            System.out.println("MM CUSTOMER STATE WRITE WARNING | " + safeMessage(e));
        }
    }

    private String customerStateKey(String customerId) {
        return "customer." + CUSTOMER_STATE_SCHEMA + "." + customerId;
    }

    private boolean isPermanentlyHandled(Properties state, String customerId) {
        return state != null && !blank(state.getProperty(customerStateKey(customerId)));
    }

    private void markCustomerHandled(Properties state, String customerId, String status) {
        if (state == null || blank(customerId)) return;
        String date = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Kolkata")).toString();
        state.setProperty(customerStateKey(customerId), date + "|" + nvl(status));
        // Persist immediately so a restart does not cause already-entered customers to be repeated.
        saveCustomerSyncState(state);
    }

    private String credentialAttemptKey(String customerId) {
        return "credential-attempt." + CUSTOMER_STATE_SCHEMA + "." + customerId;
    }

    private String retryAttemptKey(String customerId) {
        return "retry-attempt." + CUSTOMER_STATE_SCHEMA + "." + customerId;
    }

    private boolean wasCredentialIssueCheckedToday(Properties state, String customerId) {
        if (state == null || blank(customerId)) return false;
        String today = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Kolkata")).toString();
        String value = nvl(state.getProperty(credentialAttemptKey(customerId)));
        return value.startsWith(today + "|");
    }

    private boolean wasRetryIssueCheckedToday(Properties state, String customerId) {
        if (state == null || blank(customerId)) return false;
        if (wasCredentialIssueCheckedToday(state, customerId)) return true;
        String today = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Kolkata")).toString();
        String value = nvl(state.getProperty(retryAttemptKey(customerId)));
        return value.startsWith(today + "|");
    }

    private void markCredentialIssueCheckedToday(Properties state, String customerId, String status) {
        if (state == null || blank(customerId)) return;
        String today = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Kolkata")).toString();
        state.setProperty(credentialAttemptKey(customerId), today + "|" + nvl(status));
        saveCustomerSyncState(state);
    }

    private void markRetryIssueCheckedToday(Properties state, String customerId, String status) {
        if (state == null || blank(customerId)) return;
        String today = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Kolkata")).toString();
        state.setProperty(retryAttemptKey(customerId), today + "|" + nvl(status));
        saveCustomerSyncState(state);
    }

    // ---------------------------------------------------------------------
    // MARINERS MENTOR
    // ---------------------------------------------------------------------

    private void loginMarinersMentor(WebDriver driver, WebDriverWait wait, Config cfg) {
        String base = trimSlash(cfg.getDashboardUrl());
        driver.get(base + "/dashboard/account");

        wait.until(d -> firstVisible(d, By.cssSelector("input[type='password']")) != null
                || pageContainsAny(d, "Customers", "Overview", "Users management", "My Account"));

        if (firstVisible(driver, By.cssSelector("input[type='password']")) == null) return;

        WebElement username = firstVisible(driver,
                By.cssSelector("input[name='email']"),
                By.cssSelector("input[name='username']"),
                By.cssSelector("input[name='userId']"),
                By.cssSelector("input[type='email']"),
                By.xpath("//input[contains(translate(@placeholder,'ABCDEFGHIJKLMNOPQRSTUVWXYZ','abcdefghijklmnopqrstuvwxyz'),'email') or contains(translate(@placeholder,'ABCDEFGHIJKLMNOPQRSTUVWXYZ','abcdefghijklmnopqrstuvwxyz'),'user')]")
        );
        WebElement password = firstVisible(driver,
                By.cssSelector("input[name='password']"),
                By.cssSelector("input[type='password']")
        );
        if (username == null || password == null) {
            throw new IllegalStateException("Mariners Mentor login fields not found on /dashboard/account");
        }

        setInputValue(driver, username, cfg.getDashboardUsername());
        setInputValue(driver, password, cfg.getDashboardPassword());

        WebElement login = firstVisible(driver,
                By.xpath("//button[@type='submit']"),
                By.xpath("//input[@type='submit']"),
                By.xpath("//button[contains(translate(normalize-space(.),'ABCDEFGHIJKLMNOPQRSTUVWXYZ','abcdefghijklmnopqrstuvwxyz'),'login') or contains(translate(normalize-space(.),'ABCDEFGHIJKLMNOPQRSTUVWXYZ','abcdefghijklmnopqrstuvwxyz'),'sign in')]")
        );
        if (login == null) throw new IllegalStateException("Mariners Mentor login button not found");
        click(driver, login);
        wait.until(d -> d.getCurrentUrl().contains("/dashboard"));
    }

    private void openCustomersPage(WebDriver driver, WebDriverWait wait, Config cfg, int page) {
        String base = trimSlash(cfg.getDashboardUrl());
        driver.get(base + "/dashboard/customer");
        wait.until(d -> d.getCurrentUrl().contains("/dashboard/customer") && pageContains(d, "Customers"));
        wait.until(d -> !visibleRows(d).isEmpty());

        // IMPORTANT: set 10 rows BEFORE moving to page 2/3/etc.
        // Otherwise page 2 could mean records 11-20 so AG Grid renders the complete page reliably.
        ensurePageSize(driver, wait, 100);

        if (page <= 1) return;

        for (int p = 1; p < page; p++) {
            PageState before = readPageState(driver);
            WebElement next = wait.until(d -> findNextPageButton(d));

            if (next == null || isAriaDisabled(next)) {
                throw new IllegalStateException("Cannot move to customer page " + page
                        + " (Next Page button disabled/not found)");
            }

            click(driver, next);
            int expected = p + 1;

            wait.until(d -> {
                PageState now = readPageState(d);
                return now.page == expected || (before.page > 0 && now.page > before.page);
            });
            wait.until(d -> !visibleRows(d).isEmpty());

            System.out.println("MM: Customers page " + expected + " opened");
        }
    }

    /**
     * AG Grid uses a custom picker (not always a normal <select>) for Page Size.
     * Support both versions and make sure the grid is showing 10 customers per page.
     */
    private void ensurePageSize(WebDriver driver, WebDriverWait wait, int wanted) {
        String wantedText = String.valueOf(wanted);
        try {
            WebElement display = firstVisible(driver,
                    By.cssSelector(".ag-paging-page-size .ag-picker-field-display"),
                    By.cssSelector("[data-ref='eDisplayField'].ag-picker-field-display"),
                    By.cssSelector("[data-ref='eDisplayField']")
            );

            if (display != null && wantedText.equals(nvl(display.getText()).trim())) return;

            WebElement selectElement = firstVisible(driver,
                    By.xpath("//select[ancestor::*[contains(normalize-space(.),'Page Size')]]"),
                    By.cssSelector(".ag-paging-page-size select")
            );
            if (selectElement != null) {
                Select select = new Select(selectElement);
                boolean hasWanted = select.getOptions().stream()
                        .anyMatch(o -> wantedText.equals(nvl(o.getAttribute("value")).trim())
                                || wantedText.equals(nvl(o.getText()).trim()));
                if (hasWanted && !wantedText.equals(nvl(select.getFirstSelectedOption().getText()).trim())) {
                    try { select.selectByVisibleText(wantedText); }
                    catch (Exception e) { select.selectByValue(wantedText); }
                    wait.until(d -> pageSizeIs(d, wanted));
                }
                return;
            }

            WebElement picker = firstVisible(driver,
                    By.cssSelector(".ag-paging-page-size .ag-picker-field"),
                    By.xpath("//*[@data-ref='eDisplayField']/ancestor::*[contains(@class,'ag-picker-field')][1]"),
                    By.xpath("//*[@data-ref='eDisplayField']")
            );
            if (picker == null) return;

            click(driver, picker);
            WebElement option = wait.until(d -> firstVisible(d,
                    By.xpath("//*[contains(@class,'ag-list-item') and normalize-space()='" + wantedText + "']"),
                    By.xpath("//*[@role='option' and normalize-space()='" + wantedText + "']"),
                    By.xpath("//*[contains(@class,'ag-select-list-item') and normalize-space()='" + wantedText + "']"),
                    By.xpath("//*[normalize-space()='" + wantedText + "' and ancestor::*[contains(@class,'ag-picker') or contains(@class,'ag-popup')]]")
            ));
            if (option != null) {
                click(driver, option);
                wait.until(d -> pageSizeIs(d, wanted));
                wait.until(d -> !visibleRows(d).isEmpty());
                System.out.println("MM: Page Size set to " + wantedText);
            }
        } catch (Exception e) {
            if (!pageSizeIs(driver, wanted)) {
                System.out.println("MM: Could not force Page Size " + wantedText + " - " + safeMessage(e));
            }
        }
    }

    private boolean pageSizeIs(WebDriver driver, int wanted) {
        String wantedText = String.valueOf(wanted);
        try {
            WebElement display = firstVisible(driver,
                    By.cssSelector(".ag-paging-page-size .ag-picker-field-display"),
                    By.cssSelector("[data-ref='eDisplayField'].ag-picker-field-display"),
                    By.cssSelector("[data-ref='eDisplayField']")
            );
            if (display != null && wantedText.equals(nvl(display.getText()).trim())) return true;

            WebElement select = firstVisible(driver,
                    By.xpath("//select[ancestor::*[contains(normalize-space(.),'Page Size')]]"),
                    By.cssSelector(".ag-paging-page-size select")
            );
            if (select != null) {
                return wantedText.equals(nvl(new Select(select).getFirstSelectedOption().getText()).trim());
            }
        } catch (Exception ignored) {}
        return false;
    }

    // Compatibility helper for older callers in this class.
    private boolean pageSizeIs10(WebDriver driver) {
        return pageSizeIs(driver, 10);
    }


    private boolean isAriaDisabled(WebElement e) {
        try {
            String aria = nvl(e.getAttribute("aria-disabled")).trim();
            if ("true".equalsIgnoreCase(aria)) return true;
            String cls = nvl(e.getAttribute("class"));
            if (cls.contains("ag-disabled")) return true;
            return !e.isEnabled();
        } catch (Exception ex) {
            return true;
        }
    }

    private List<WebElement> visibleRows(WebDriver driver) {
        List<WebElement> rows = driver.findElements(By.cssSelector(".ag-center-cols-container .ag-row[role='row']"));
        if (rows.isEmpty()) rows = driver.findElements(By.cssSelector(".ag-center-cols-container .ag-row"));
        if (rows.isEmpty()) rows = driver.findElements(By.cssSelector(".ag-row[role='row']"));

        List<WebElement> visible = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (WebElement row : rows) {
            try {
                if (!row.isDisplayed()) continue;
                String idx = nvl(row.getAttribute("row-index"));
                if (idx.isBlank()) idx = nvl(row.getAttribute("aria-rowindex"));
                String key = idx.isBlank() ? String.valueOf(System.identityHashCode(row)) : idx;
                if (seen.add(key)) visible.add(row);
            } catch (StaleElementReferenceException ignored) {}
        }
        visible.sort(Comparator.comparingInt(this::rowIndex));
        return visible;
    }

    private int rowIndex(WebElement row) {
        String x = nvl(row.getAttribute("row-index"));
        if (x.isBlank()) x = nvl(row.getAttribute("aria-rowindex"));
        try { return Integer.parseInt(x); }
        catch (Exception e) { return Integer.MAX_VALUE; }
    }

    private void scrollCustomerGridToRight(WebDriver driver, WebDriverWait wait) {
        WebElement viewport = firstVisible(driver,
                By.cssSelector(".ag-body-horizontal-scroll-viewport"),
                By.cssSelector(".ag-center-cols-viewport")
        );
        if (viewport == null) throw new IllegalStateException("Customers horizontal scroll viewport not found");

        ((JavascriptExecutor) driver).executeScript(
                "var els=document.querySelectorAll('.ag-body-horizontal-scroll-viewport,.ag-center-cols-viewport');" +
                "for(var i=0;i<els.length;i++){els[i].scrollLeft=els[i].scrollWidth;}"
        );

        wait.until(d -> d.findElements(By.cssSelector("button.action-btn")).stream().anyMatch(this::displayed));
    }

    private void openCustomerAtIndex(WebDriver driver, WebDriverWait wait, int index) {
        scrollCustomerGridToRight(driver, wait);

        // AG Grid virtualises rows. With a large page size, rows that are lower on the page may not
        // exist in the DOM until we scroll vertically. Keep page size small and explicitly render
        // the requested row before clicking its eye/view action.
        int absoluteTarget = currentPageStartIndex(driver) + index;
        WebElement row = renderRow(driver, wait, absoluteTarget, index);
        if (row == null) {
            throw new IllegalStateException("Customer row " + (index + 1) + " could not be rendered");
        }

        // Prefer the actual eye/view action, not simply the first action button.
        // The Phosphor eye icon used by MM starts with path M247.31,124.76...
        WebElement view = null;
        List<WebElement> eyeActions = row.findElements(By.xpath(
                ".//button[contains(@class,'action-btn')][.//*[name()='path' and contains(@d,'M247.31,124.76')]]"));
        for (WebElement b : eyeActions) {
            if (displayed(b)) { view = b; break; }
        }

        if (view == null) {
            List<WebElement> actions = row.findElements(By.cssSelector("button.action-btn"));
            if (actions.isEmpty()) actions = row.findElements(By.xpath(".//button[contains(@class,'action-btn')][.//*[name()='svg']]"));
            if (actions.isEmpty()) actions = row.findElements(By.xpath(".//button[.//*[name()='svg']]"));
            if (actions.isEmpty()) throw new IllegalStateException("View/eye button not found in customer row");
            view = actions.get(0);
        }
        ((JavascriptExecutor) driver).executeScript("arguments[0].scrollIntoView({block:'center',inline:'center'});", view);
        click(driver, view);
        wait.until(d -> d.getCurrentUrl().contains("/dashboard/customer/view"));
        wait.until(d -> pageContains(d, "View Customer"));
    }

    private WebElement renderRow(WebDriver driver, WebDriverWait wait, int absoluteTarget, int pageIndex) {
        By exact = By.cssSelector(".ag-center-cols-container .ag-row[row-index='" + absoluteTarget + "']");
        WebElement existing = firstVisible(driver, exact);
        if (existing != null) return existing;

        // Scroll the page-local grid viewport. Row height is read from a rendered row when possible.
        WebElement viewport = firstVisible(driver,
                By.cssSelector(".ag-body-viewport"),
                By.cssSelector(".ag-center-cols-viewport"));
        if (viewport == null) return null;

        long rowHeight = 42;
        try {
            WebElement any = visibleRows(driver).stream().findFirst().orElse(null);
            if (any != null && any.getSize().getHeight() > 0) rowHeight = any.getSize().getHeight();
        } catch (Exception ignored) {}

        // First jump close to the requested row, then retry around that position.
        long base = Math.max(0, pageIndex * rowHeight);
        for (int attempt = 0; attempt < 8; attempt++) {
            long offset = base + (long) (attempt / 2 + 1) * rowHeight * (attempt % 2 == 0 ? 1 : -1);
            if (attempt == 0) offset = base;
            if (offset < 0) offset = 0;
            try {
                ((JavascriptExecutor) driver).executeScript("arguments[0].scrollTop=arguments[1];", viewport, offset);
            } catch (Exception ignored) {}
            sleep(180);
            existing = firstVisible(driver, exact);
            if (existing != null) return existing;
        }

        // Fallback: when AG Grid uses page-local row-index values, use the rendered row order.
        List<WebElement> rows = visibleRows(driver);
        if (pageIndex < rows.size()) return rows.get(pageIndex);
        return null;
    }

    private int currentPageStartIndex(WebDriver driver) {
        try {
            String body = driver.findElement(By.tagName("body")).getText();
            Matcher m = ROW_RANGE.matcher(body);
            if (m.find()) return Math.max(0, Integer.parseInt(m.group(1).replace(",", "")) - 1);
        } catch (Exception ignored) {}
        List<WebElement> rows = visibleRows(driver);
        if (!rows.isEmpty()) {
            int idx = rowIndex(rows.get(0));
            if (idx != Integer.MAX_VALUE) return idx;
        }
        return 0;
    }

    private int readTotalCustomerCount(WebDriver driver) {
        try {
            String body = driver.findElement(By.tagName("body")).getText();
            Matcher m = ROW_RANGE.matcher(body);
            if (m.find()) {
                return Integer.parseInt(m.group(3).replace(",", ""));
            }
        } catch (Exception ignored) {}
        return 0;
    }

    private int currentPageRowCount(WebDriver driver) {
        try {
            String body = driver.findElement(By.tagName("body")).getText();
            Matcher m = ROW_RANGE.matcher(body);
            if (m.find()) {
                int first = Integer.parseInt(m.group(1).replace(",", ""));
                int last = Integer.parseInt(m.group(2).replace(",", ""));
                return Math.max(0, last - first + 1);
            }
        } catch (Exception ignored) {}
        return visibleRows(driver).size();
    }

    private MmCustomer readMmCustomer(WebDriver driver, WebDriverWait wait) {
        // React/Next renders the shell first. Wait for the INDoS area itself, but do NOT require
        // a non-blank INDoS value because some genuine customers have INDoS = Null / Not Provided.
        wait.until(d -> firstVisible(d,
                By.cssSelector("input[name='indosNo']"),
                By.cssSelector("input[name='indos_no']")) != null
                || pageContainsAny(d, "Indos No", "INDoS No", "INDOS No"));

        String indos = waitForIndosValueOrConfirmedBlank(driver);
        String first = textNearLabel(driver, "First Name");
        String surname = textNearLabel(driver, "Surname", "Last Name");
        String dob = textNearLabel(driver, "Date Of Birth", "Date of Birth", "DOB");
        String fatherName = textNearLabel(driver, "Father Name", "Father's Name", "Name of Father");
        // Document numbers on the MM View page are rendered after their labels.
        // Do NOT use the generic preceding-sibling fallback here: when a document
        // number is blank it can otherwise pick up the previous field block (for
        // example "Weight / Null") and write that into Passport No.
        String passportNo = cleanDocumentNumberCandidate(
                textAfterLabel(driver, "Passport Number", "Passport No", "Passport No."));
        String cdcNo = cleanDocumentNumberCandidate(
                textAfterLabel(driver, "CDC No", "CDC No.", "CDC Number", "Cdc No"));
        String sidNo = cleanDocumentNumberCandidate(
                textAfterLabel(driver, "SID No", "SID No.", "SID Number", "Sid No", "BSID Number", "BSID No"));
        // MM shows generic date labels inside the SID section (for example
        // "Issued / Renewed Date" and "Expiry Date") rather than always
        // prefixing them with SID. Read SID-prefixed labels first, then fall
        // back to the value inside the SID block only. This avoids accidentally
        // taking the CDC/COP/COC date that uses the same generic label.
        String sidIssuedDate = firstNonBlankText(
                textNearLabel(driver, "SID Issued / Renewed Date", "SID Issue Date", "SID Issued Date"),
                readDocumentSectionValue(driver, "SID", "Issued / Renewed Date", "Issue Date", "Issued Date", "Date of Issue")
        );
        String sidExpiryDate = firstNonBlankText(
                textNearLabel(driver, "SID Expiry Date", "SID Valid Upto", "SID Valid Until"),
                readDocumentSectionValue(driver, "SID", "Expiry Date", "Valid Upto", "Valid Until", "Date of Expiry")
        );
        String role = textNearLabel(driver, "Role", "Rank");
        String vessel = textNearLabel(driver, "Vessel", "Vessel Name", "Ship Name");
        String rpsl = textNearLabel(driver, "RPSL", "RPSL Company", "Company");
        String phone = textNearLabel(driver, "Phone", "Mobile", "Mobile No", "Mobile No.");
        String email = textNearLabel(driver, "Email Address", "Email", "Email Id", "Email ID");
        String createdBy = textNearLabel(driver, "Created By");
        String createdAt = textNearLabel(driver, "Created at", "Created At");

        String name = "";
        try {
            List<WebElement> headings = driver.findElements(By.xpath(
                    "//*[self::h1 or self::h2 or self::h3 or self::h4 or self::h5 or self::h6][following::*[contains(.,'Indos No')]]"));
            for (WebElement h : headings) {
                if (displayed(h) && !blank(h.getText())) { name = h.getText().trim(); break; }
            }
        } catch (Exception ignored) {}
        if (blank(name)) name = (nvl(first) + " " + nvl(surname)).trim();

        return new MmCustomer(
                cleanNull(name), cleanNull(first), cleanNull(surname), cleanNull(dob),
                cleanNull(fatherName), cleanNull(passportNo), cleanNull(cdcNo), cleanNull(sidNo),
                cleanNull(sidIssuedDate), cleanNull(sidExpiryDate), cleanNull(role), cleanNull(vessel), cleanNull(rpsl),
                cleanNull(indos), cleanNull(phone), cleanNull(email), cleanNull(createdBy), cleanNull(createdAt));
    }

    private boolean customerProfileComplete(MmCustomer mm) {
        return mm != null
                && !blank(mm.cdcNo)
                && !blank(mm.sidNo)
                && !blank(mm.dob)
                && !blank(mm.fatherName);
    }

    /**
     * Give the React value a few seconds to arrive. If the profile explicitly says Null / Not Provided,
     * return blank immediately instead of treating that customer as a Selenium failure.
     */
    private String waitForIndosValueOrConfirmedBlank(WebDriver driver) {
        Instant end = Instant.now().plusSeconds(6);
        while (Instant.now().isBefore(end)) {
            String value = readIndosNow(driver);
            if (!blank(value)) return value;
            try {
                String body = driver.findElement(By.tagName("body")).getText();
                if (body.matches("(?is).*indos\\s*no\\s*[:\\-]?\\s*(null|not provided|undefined).*")) return "";
            } catch (Exception ignored) {}
            sleep(250);
        }
        return readIndosNow(driver);
    }

    private String readIndosNow(WebDriver driver) {
        String indos = attributeValue(driver, By.cssSelector("input[name='indosNo']"), "value");
        if (blank(indos)) indos = attributeValue(driver, By.cssSelector("input[name='indos_no']"), "value");
        if (blank(indos)) indos = textAfterLabel(driver, "Indos No", "INDOS No", "INDoS No", "INDoS No.");
        return cleanNull(indos);
    }

    /**
     * Checks the actual MM Edit Customer inputs. Returns true when at least one
     * DG-backed field exists and is blank. If the form cannot be inspected,
     * return true conservatively so the customer is not incorrectly skipped.
     */
    private boolean hasBlankDgTargetFields(
            WebDriver driver,
            WebDriverWait wait,
            String customerViewUrl
    ) {
        try {
            driver.get(customerViewUrl);
            wait.until(d -> d.getCurrentUrl().contains("/dashboard/customer/view")
                    && pageContains(d, "View Customer"));

            ((JavascriptExecutor) driver).executeScript(
                    "window.scrollTo(0, document.body.scrollHeight);"
            );

            WebElement edit = wait.until(d -> firstVisible(d,
                    By.xpath("//button[normalize-space()='Edit']"),
                    By.xpath("//button[contains(normalize-space(.),'Edit')]")
            ));
            if (edit == null) return true;

            click(driver, edit);
            wait.until(d -> d.getCurrentUrl().contains("/dashboard/customer/edit")
                    || firstVisible(d, By.cssSelector("input[name='cdc_no']")) != null);

            String[][] groups = new String[][]{
                    {"date_of_birth", "dob", "dateOfBirth"},
                    {"father_name", "fatherName", "father"},
                    {"passport_no", "passportNo", "passport_number"},
                    {"height"},
                    {"weight"},
                    {"cdc_no", "cdcNo", "cdc_number"},
                    {"cdc_issued_or_renewed_date", "cdc_issue_date", "cdcIssuedOrRenewedDate"},
                    {"cdc_expiry_date", "cdcExpiryDate"},
                    {"sid_no", "sidNo", "sid_number"},
                    {"sid_issued_or_renewed_date", "sid_issue_date", "sidIssuedOrRenewedDate"},
                    {"sid_expiry_date", "sidExpiryDate"},
                    {"cop_no", "copNo", "cop_number"},
                    {"cop_issued_or_renewed_date", "cop_issue_date", "copIssuedOrRenewedDate"},
                    {"cop_expiry_date", "copExpiryDate"},
                    {"coc_no", "cocNo", "coc_number"},
                    {"coc_issued_date", "coc_issue_date", "cocIssuedDate"},
                    {"coc_expiry_date", "cocExpiryDate"}
            };

            int foundGroups = 0;
            for (String[] names : groups) {
                WebElement input = null;
                for (String name : names) {
                    input = firstVisible(driver, By.cssSelector("input[name='" + name + "']"));
                    if (input != null) break;
                }
                if (input == null) continue;

                foundGroups++;
                String current = cleanNull(nvl(input.getAttribute("value")));
                if (blank(current)) {
                    return true;
                }
            }

            // If no expected field was found, do not trust the page structure.
            return foundGroups == 0;

        } catch (Exception inspectError) {
            return true;
        } finally {
            try {
                driver.get(customerViewUrl);
                new WebDriverWait(driver, Duration.ofSeconds(10)).until(d ->
                        d.getCurrentUrl().contains("/dashboard/customer/view"));
            } catch (Exception ignored) {}
        }
    }

    private ResumeEntryData resumeDataFromMmCustomer(MmCustomer mm) {
        if (mm == null) {
            return new ResumeEntryData(
                    "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "",
                    new ResumeEntryData.PassportInfo("", "", "", ""),
                    ResumeEntryData.DocInfo.empty(),
                    ResumeEntryData.DocInfo.empty(),
                    ResumeEntryData.DocInfo.empty(),
                    ResumeEntryData.DocInfo.empty(),
                    "", "", "", Collections.emptyList(), Collections.emptyList()
            );
        }

        List<ResumeEntryData.VesselInfo> vessels = new ArrayList<>();
        if (!blank(mm.rpsl) || !blank(mm.vessel) || !blank(mm.role)) {
            vessels.add(new ResumeEntryData.VesselInfo(
                    cleanNull(mm.rpsl),
                    cleanNull(mm.vessel),
                    "", "", "", "", "", "", "",
                    cleanNull(mm.role),
                    "", "", "", "", "", "", "", "", ""
            ));
        }

        return new ResumeEntryData(
                cleanNull(mm.surname),
                cleanNull(mm.firstName),
                "",
                cleanNull(mm.fatherName),
                cleanNull(mm.email),
                cleanNull(mm.phone),
                "",
                cleanNull(mm.dob),
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                cleanNull(mm.indosNo),
                new ResumeEntryData.PassportInfo(cleanNull(mm.passportNo), "", "", ""),
                new ResumeEntryData.DocInfo(cleanNull(mm.cdcNo), "", "", ""),
                new ResumeEntryData.DocInfo(
                        cleanNull(mm.sidNo),
                        cleanNull(mm.sidIssuedDate),
                        cleanNull(mm.sidExpiryDate),
                        ""
                ),
                ResumeEntryData.DocInfo.empty(),
                ResumeEntryData.DocInfo.empty(),
                "",
                "",
                "",
                Collections.emptyList(),
                vessels
        );
    }

    private String customerLabel(String customerId, String customerName) {
        String name = cleanNull(customerName);
        if (blank(name)) name = "Customer";
        return name + (blank(customerId) ? "" : " [ID " + customerId + "]");
    }

    private String revealAndReadIndosPassword(WebDriver driver, WebDriverWait wait) {
        /*
         * MM currently shows TWO password-related areas on Customer View:
         *   1) "Indos Password (Hidden): <stored value>" + eye icon  <-- source of truth
         *   2) empty "INDOS Password *" verification input          <-- NOT source of truth
         *
         * Therefore never classify the password as missing just because the input is empty.
         * We locate the text block containing "Indos Password (Hidden)", click the eye inside/
         * beside that same block, and re-read the revealed text. Three local attempts are used
         * so a React/MUI render delay does not force a five-minute whole-case retry.
         */
        for (int attempt = 1; attempt <= 3; attempt++) {
            String visible = extractIndosPasswordFromPage(driver);
            if (!blank(visible)) {
                System.out.println("MM INDOS PASSWORD FOUND ON CUSTOMER VIEW | Attempt: " + attempt);
                return visible;
            }

            try {
                Object clicked = ((JavascriptExecutor) driver).executeScript("""
                    const norm = s => (s || '').replace(/\\s+/g,' ').trim().toLowerCase();
                    const all = Array.from(document.querySelectorAll('body *'));
                    let label = all.find(e => {
                      const t = norm(e.innerText || e.textContent);
                      return t.includes('indos password (hidden)') && t.length < 220;
                    });
                    if (!label) {
                      label = all.find(e => {
                        const t = norm(e.innerText || e.textContent);
                        return t.includes('indos password') && t.includes('hidden') && t.length < 220;
                      });
                    }
                    if (!label) return 'NO_LABEL';

                    let box = label;
                    for (let i=0; i<5 && box; i++, box=box.parentElement) {
                      const candidates = Array.from(box.querySelectorAll(
                        'button,[role="button"],svg,[data-testid*="Visibility"],[aria-label*="password" i]'
                      ));
                      for (const c of candidates) {
                        const target = c.closest('button,[role="button"]') || c;
                        const r = target.getBoundingClientRect();
                        if (r.width > 0 && r.height > 0) {
                          target.click();
                          return 'CLICKED';
                        }
                      }
                    }
                    return 'NO_EYE';
                """);
                System.out.println("MM INDOS PASSWORD EYE | Attempt: " + attempt + " | " + String.valueOf(clicked));
            } catch (Exception jsError) {
                System.out.println("MM INDOS PASSWORD EYE JS RETRY | Attempt: " + attempt);
            }

            Instant deadline = Instant.now().plusSeconds(3);
            while (Instant.now().isBefore(deadline)) {
                String value = extractIndosPasswordFromPage(driver);
                if (!blank(value)) {
                    System.out.println("MM INDOS PASSWORD REVEALED FROM CUSTOMER VIEW");
                    return value;
                }
                sleep(200);
            }

            // React pages occasionally need a clean reload before the eye becomes clickable.
            if (attempt < 3) {
                try {
                    driver.navigate().refresh();
                    wait.until(d -> d.getCurrentUrl().contains("/dashboard/customer/view")
                            && pageContains(d, "View Customer"));
                    sleep(500);
                } catch (Exception ignored) {}
            }
        }

        return "";
    }

    private WebElement firstDisplayed(List<WebElement> elements) {
        if (elements == null) return null;
        for (WebElement element : elements) {
            if (displayed(element)) return element;
        }
        return null;
    }

    private String extractIndosPasswordFromPage(WebDriver driver) {
        // First inspect only compact elements around the stored "Indos Password (Hidden)" label.
        try {
            List<WebElement> candidates = driver.findElements(By.xpath(
                    "//*[contains(translate(normalize-space(.),'ABCDEFGHIJKLMNOPQRSTUVWXYZ','abcdefghijklmnopqrstuvwxyz'),'indos password')]"
            ));
            for (WebElement candidate : candidates) {
                if (!displayed(candidate)) continue;
                String text = cleanNull(candidate.getText());
                if (text.length() > 250) continue; // avoid matching the whole page/container
                String value = extractPasswordAfterIndosLabel(text);
                if (!blank(value)) return value;
            }
        } catch (Exception ignored) {}

        // MUI/React can render the revealed value in a sibling span/input without
        // including it in candidate.getText(). Inspect only the compact card that
        // contains the stored-password label. Never read the separate verification
        // input (placeholder/label contains verify/verification).
        try {
            Object raw = ((JavascriptExecutor) driver).executeScript("""
                const norm = s => (s || '').replace(/\\s+/g,' ').trim();
                const low = s => norm(s).toLowerCase();
                const all = Array.from(document.querySelectorAll('body *'));
                let label = all.find(e => {
                  const t = low(e.innerText || e.textContent);
                  return t.includes('indos password') && t.includes('hidden') && t.length < 220;
                });
                if (!label) return '';
                let box = label;
                for (let i=0; i<5 && box; i++, box=box.parentElement) {
                  const text = norm(box.innerText || box.textContent);
                  if (text.length > 0 && text.length < 500) {
                    const inputs = Array.from(box.querySelectorAll('input'));
                    for (const inp of inputs) {
                      const meta = low((inp.placeholder||'')+' '+(inp.name||'')+' '+(inp.id||'')+' '+(inp.getAttribute('aria-label')||''));
                      if (meta.includes('verify') || meta.includes('verification')) continue;
                      const v = norm(inp.value || inp.getAttribute('value'));
                      if (v && v.length >= 4 && v.length <= 80) return 'INDOS Password: ' + v;
                    }
                    if (low(text).includes('indos password')) return text;
                  }
                }
                return '';
            """);
            String value = extractPasswordAfterIndosLabel(String.valueOf(raw));
            if (!blank(value)) return value;
        } catch (Exception ignored) {}

        // Body-text fallback after the eye has revealed the stored value.
        String fromBody = extractPasswordAfterIndosLabel(safeBodyText(driver));
        if (!blank(fromBody)) return fromBody;

        // Intentionally DO NOT use the empty "INDOS Password *" verification input.
        return "";
    }

    private String extractPasswordAfterIndosLabel(String text) {
        if (blank(text)) return "";
        try {
            // Accept the stored value exactly as MM displays it. Do not restrict the
            // password to only letters/numbers because valid DG passwords can contain
            // punctuation that the older regex silently truncated.
            Matcher m = Pattern.compile(
                    "(?is)indos\\s+password(?:\\s*\\(hidden\\))?\\s*[:\\-]?\\s*([^\\r\\n]{4,100})"
            ).matcher(text);
            if (!m.find()) return "";
            return cleanIndosPasswordValue(m.group(1));
        } catch (Exception ignored) {
            return "";
        }
    }

    private String cleanIndosPasswordValue(String raw) {
        String value = cleanNull(raw)
                .replaceAll("(?i)\\s*(show|hide)\\s+password.*$", "")
                .trim();

        if (blank(value)) return "";
        if (value.matches("[•●*]+")) return "";
        if (value.equalsIgnoreCase("null")
                || value.equalsIgnoreCase("undefined")
                || value.equalsIgnoreCase("not provided")
                || value.equalsIgnoreCase("indos password")) {
            return "";
        }

        if (value.length() < 4 || value.length() > 80) return "";
        if (value.toLowerCase(Locale.ROOT).contains("verify password")) return "";
        return value;
    }

    private UpdateResult updateMarinersMentorFromDg(WebDriver driver, WebDriverWait wait,
                                                      Config cfg,
                                                      String customerViewUrl,
                                                      CustomerProfileData data,
                                                      ResumeEntrySheetService.SheetWriteResult sheetWrite) {
        driver.get(customerViewUrl);
        wait.until(d -> d.getCurrentUrl().contains("/dashboard/customer/view") && pageContains(d, "View Customer"));

        ((JavascriptExecutor) driver).executeScript("window.scrollTo(0, document.body.scrollHeight);");
        WebElement edit = wait.until(d -> firstVisible(d,
                By.xpath("//button[normalize-space()='Edit']"),
                By.xpath("//button[contains(normalize-space(.),'Edit')]")
        ));
        if (edit == null) throw new IllegalStateException("MM Edit button not found");
        click(driver, edit);
        wait.until(d -> d.getCurrentUrl().contains("/dashboard/customer/edit")
                || firstVisible(d, By.cssSelector("input[name='cdc_no']")) != null
                || firstVisible(d, By.xpath("//button[contains(translate(normalize-space(.),'ABCDEFGHIJKLMNOPQRSTUVWXYZ','abcdefghijklmnopqrstuvwxyz'),'update customer')]")) != null);

        dumpCustomerEditFormStructureOnce(driver);

        int changed = 0;

        // Fill every customer field that the DG profile provides.  The MM form is
        // React/MUI and some current fields do not expose the old name= attributes,
        // so use name/id first and then visible label/placeholder fallbacks.
        // Existing MM values are NEVER overwritten.
        // Fill the rest of the DG-backed personal profile too, when MM is blank.
        changed += updateInputSmart(driver, data.givenName(),
                new String[]{"first_name", "firstName", "given_name", "givenName"},
                "First Name", "Given Name");
        changed += updateInputSmart(driver, data.surname(),
                new String[]{"surname", "last_name", "lastName"},
                "Surname", "Last Name");
        changed += updateInputSmart(driver, data.email(),
                new String[]{"email", "email_id", "emailAddress"},
                "Email Address", "Email");
        changed += updateInputSmart(driver, data.phone(),
                new String[]{"phone", "mobile", "mobile_no", "mobileNo"},
                "Phone", "Mobile Number");
        changed += updateInputSmart(driver, data.city(),
                new String[]{"city"},
                "City");

        ChoiceResult countryChoice = updateMuiChoiceIfBlank(driver, "Country", data.country());
        changed += countryChoice.changed;
        ChoiceResult stateChoice = updateMuiChoiceIfBlank(driver, "State", data.state());
        changed += stateChoice.changed;

        changed += updateInputSmart(driver, data.dob(),
                new String[]{"date_of_birth", "dob", "dateOfBirth"},
                "Date of Birth", "DOB");
        changed += updateInputSmart(driver, data.fatherName(),
                new String[]{"father_name", "fatherName", "father"},
                "Father Name", "Father's Name");
        changed += updateInputSmart(driver, data.passportNo(),
                new String[]{"passport_no", "passportNo", "passport_number"},
                "Passport Number", "Passport No");
        changed += updateInputSmart(driver, data.height(),
                new String[]{"height"},
                "Height (cm)", "Height");
        changed += updateInputSmart(driver, data.weight(),
                new String[]{"weight"},
                "Weight (kg)", "Weight");

        changed += updateDocumentSmart(driver, data.cdc(),
                new String[]{"cdc_no", "cdcNo", "cdc_number"},
                new String[]{"cdc_issued_or_renewed_date", "cdc_issue_date", "cdcIssuedOrRenewedDate"},
                new String[]{"cdc_expiry_date", "cdcExpiryDate"},
                "CDC Number", "CDC Issued / Renewed Date", "CDC Expiry Date");

        changed += updateDocumentSmart(driver, data.sid(),
                new String[]{"sid_no", "sidNo", "sid_number", "sidNumber", "sid",
                        "bsid_no", "bsidNo", "bsid_number", "bsidNumber"},
                new String[]{"sid_issued_or_renewed_date", "sid_issue_date", "sid_issued_date",
                        "sidIssuedOrRenewedDate", "sidIssueDate", "sidIssuedDate",
                        "bsid_issue_date", "bsid_issued_date", "bsidIssueDate", "bsidIssuedDate"},
                new String[]{"sid_expiry_date", "sid_expiry", "sidExpiryDate", "sidExpiry",
                        "bsid_expiry_date", "bsidExpiryDate"},
                "SID Number", "SID Issued / Renewed Date", "SID Expiry Date");

        changed += updateDocumentSmart(driver, data.cop(),
                new String[]{"cop_no", "copNo", "cop_number"},
                new String[]{"cop_issued_or_renewed_date", "cop_issue_date", "copIssuedOrRenewedDate"},
                new String[]{"cop_expiry_date", "copExpiryDate"},
                "COP Number", "COP Issued / Renewed Date", "COP Expiry Date");

        changed += updateDocumentSmart(driver, data.coc(),
                new String[]{"coc_no", "cocNo", "coc_number"},
                new String[]{"coc_issued_date", "coc_issue_date", "cocIssuedDate"},
                new String[]{"coc_expiry_date", "cocExpiryDate"},
                "COC Number", "COC Issued Date", "COC Expiry Date");

        // Optional sea-going-service fields. These are filled only when the DG
        // printable profile actually contains those rows.
        ChoiceResult vesselChoice = updateMuiChoiceIfBlank(driver, "Vessel", data.vesselName());
        changed += vesselChoice.changed;
        ChoiceResult rpslChoice = updateMuiChoiceIfBlank(driver, "RPSL", data.rpsl());
        changed += rpslChoice.changed;
        ChoiceResult roleChoice = updateMuiChoiceIfBlank(driver, "Role", data.role());
        changed += roleChoice.changed;

        // The full sea-service details now live in the Sea Service sheet.
        // MM keeps only compact clickable sheet-cell links, comma separated.
        String vesselCellLinks = sheetWrite == null ? "" : sheetWrite.commaSeparatedVesselLinks();
        if (blank(vesselCellLinks) && sheetWrite != null && sheetWrite.enabled()) {
            vesselCellLinks = sheetWrite.rowLink();
        }
        if (!blank(vesselCellLinks)) {
            changed += updateRpslLinkField(driver, vesselCellLinks, "RPSL History");
            changed += updateRpslLinkField(driver, vesselCellLinks, "RPSL Comments");
        }

        // Nothing new to fill: do not submit the same data again.
        // Existing Mariners Mentor values are preserved exactly as they are.
        if (changed == 0) {
            return new UpdateResult(false, 0);
        }

        ((JavascriptExecutor) driver).executeScript("window.scrollTo(0, document.body.scrollHeight);");
        WebElement update = wait.until(d -> firstVisible(d,
                By.xpath("//button[normalize-space()='Update Customer']"),
                By.xpath("//button[contains(translate(normalize-space(.),'ABCDEFGHIJKLMNOPQRSTUVWXYZ','abcdefghijklmnopqrstuvwxyz'),'update customer')]")
        ));
        if (update == null) throw new IllegalStateException("Update Customer button not found");
        click(driver, update);

        // Mariners Mentor currently shows a JavaScript alert:
        // "Customer updated successfully."
        // Accept it automatically so the browser is not blocked before the next customer.
        try {
            Alert alert = new WebDriverWait(driver, Duration.ofSeconds(10))
                    .until(ExpectedConditions.alertIsPresent());
            String alertText = alert.getText();
            alert.accept();
            System.out.println("MM: Success popup accepted"
                    + (blank(alertText) ? "" : " - " + alertText));
        } catch (TimeoutException | NoAlertPresentException ignored) {
            // Some dashboard versions may redirect/show a toast instead of a JS alert.
        }

        // Success may either redirect to View or show a toast while staying on Edit.
        try {
            new WebDriverWait(driver, Duration.ofSeconds(Math.max(8, cfg.getWaitSeconds()))).until(d ->
                    d.getCurrentUrl().contains("/dashboard/customer/view")
                            || pageContainsAny(d, "updated successfully", "customer updated", "success"));
        } catch (TimeoutException ignored) {
            // Do not resubmit; the click has already been made.
        }
        return new UpdateResult(changed > 0, changed);
    }



    private static final Object FORM_DUMP_LOCK = new Object();
    private static volatile boolean FORM_DUMP_DONE = false;

    /**
     * Saves the exact live React/MUI customer edit form structure from the user's
     * authenticated dashboard session. This runs once per bot process.
     */
    private void dumpCustomerEditFormStructureOnce(WebDriver driver) {
        if (FORM_DUMP_DONE) return;

        synchronized (FORM_DUMP_LOCK) {
            if (FORM_DUMP_DONE) return;

            try {
                Path stateDir = resolveStateDir();
                Files.createDirectories(stateDir);

                String html = String.valueOf(((JavascriptExecutor) driver).executeScript(
                        "var f=document.querySelector('form');" +
                        "return f ? f.outerHTML : document.documentElement.outerHTML;"
                ));
                Files.writeString(
                        stateDir.resolve("customer-edit-form-full.html"),
                        html == null ? "" : html,
                        java.nio.charset.StandardCharsets.UTF_8
                );

                Object fieldDump = ((JavascriptExecutor) driver).executeScript(
                        "return Array.from(document.querySelectorAll('input,textarea,select,[role=\"combobox\"]')).map(function(e,i){" +
                        "var label='';" +
                        "if(e.id){var l=document.querySelector('label[for=\"'+CSS.escape(e.id)+'\"]');if(l)label=(l.innerText||'').trim();}" +
                        "if(!label){var p=e.closest('.MuiFormControl-root');if(p){var l2=p.querySelector('label');if(l2)label=(l2.innerText||'').trim();}}" +
                        "var opts=[];" +
                        "if(e.tagName==='SELECT'){opts=Array.from(e.options).map(function(o){return (o.textContent||'').trim();});}" +
                        "return {i:i,tag:e.tagName,name:e.name||'',id:e.id||'',type:e.type||'',placeholder:e.placeholder||'',aria:e.getAttribute('aria-label')||'',role:e.getAttribute('role')||'',label:label,value:e.value||'',options:opts};" +
                        "});"
                );

                String fieldsJson = new com.fasterxml.jackson.databind.ObjectMapper()
                        .writerWithDefaultPrettyPrinter()
                        .writeValueAsString(fieldDump);
                Files.writeString(
                        stateDir.resolve("customer-edit-form-fields.json"),
                        fieldsJson,
                        java.nio.charset.StandardCharsets.UTF_8
                );

                System.out.println(
                        "MM CUSTOMER FORM STRUCTURE SAVED | "
                                + stateDir.resolve("customer-edit-form-full.html")
                );
                System.out.println(
                        "MM CUSTOMER FORM FIELD MAP SAVED | "
                                + stateDir.resolve("customer-edit-form-fields.json")
                );

                FORM_DUMP_DONE = true;
            } catch (Exception e) {
                System.out.println(
                        "MM CUSTOMER FORM STRUCTURE DUMP FAILED | "
                                + safeMessage(e)
                );
            }
        }
    }

    private Path resolveStateDir() {
        String local = nvl(System.getenv("LOCALAPPDATA")).trim();
        if (!local.isBlank()) {
            return Path.of(local, "MarinersMentor", "MMcasesBot");
        }
        String home = nvl(System.getProperty("user.home")).trim();
        if (!home.isBlank()) {
            return Path.of(home, ".mmcasesbot");
        }
        return Path.of(".mmcasesbot-state").toAbsolutePath();
    }

    private void notifyProgress(ProgressListener listener, SyncProgress progress) {
        if (listener == null || progress == null) return;
        try {
            listener.onProgress(progress);
        } catch (Exception e) {
            System.out.println(
                    "CUSTOMER PROFILE PROGRESS CALLBACK FAILED | "
                            + safeMessage(e)
            );
        }
    }

    private int updateDocumentSmart(
            WebDriver driver,
            CustomerProfileData.DocumentInfo doc,
            String[] numberNames,
            String[] issueNames,
            String[] expiryNames,
            String numberLabel,
            String issueLabel,
            String expiryLabel
    ) {
        if (doc == null || doc.isEmpty()) return 0;

        int changed = 0;
        changed += updateInputSmart(driver, doc.number(), numberNames, numberLabel);

        // The MM edit form commonly repeats the generic labels
        // "Issued / Renewed Date" and "Expiry Date" once for CDC, SID, COP
        // and COC. A global label lookup can therefore hit CDC when we are
        // trying to fill SID. Prefer stable name/id aliases; if those are not
        // present, locate the date input inside the correct document section.
        String section = documentSectionFromNumberLabel(numberLabel);
        changed += updateDocumentDateSmart(
                driver, doc.issuedDate(), issueNames, section, "issue", issueLabel);
        changed += updateDocumentDateSmart(
                driver, doc.expiryDate(), expiryNames, section, "expiry", expiryLabel);
        return changed;
    }

    private String documentSectionFromNumberLabel(String numberLabel) {
        String x = nvl(numberLabel).toUpperCase(Locale.ROOT);
        if (x.contains("SID")) return "SID";
        if (x.contains("CDC")) return "CDC";
        if (x.contains("COP")) return "COP";
        if (x.contains("COC")) return "COC";
        return "";
    }

    private int updateDocumentDateSmart(
            WebDriver driver,
            String desired,
            String[] names,
            String section,
            String kind,
            String logLabel
    ) {
        if (blank(desired)) return 0;

        // First use only name/id aliases. Do not use a generic global date
        // label here because the same text occurs in several document blocks.
        WebElement input = findInputSmart(driver, names);
        if (input == null && !blank(section)) {
            input = findDocumentSectionInput(driver, section, kind);
        }
        if (input == null) {
            System.out.println("MM FIELD NOT FOUND | " + logLabel + " | section=" + section);
            return 0;
        }

        String current = cleanNull(nvl(input.getAttribute("value")));
        if (!blank(current)) return 0;

        String wanted = adaptValueForInput(input, desired.trim());
        setInputValue(driver, input, wanted);
        String after = cleanNull(nvl(input.getAttribute("value")));
        System.out.println("MM FIELD FILLED | " + logLabel + " = " + maskForLog(wanted, logLabel)
                + (blank(after) ? " | WARNING: input still blank after set" : ""));
        return blank(after) ? 0 : 1;
    }

    /**
     * Locate an issue/expiry input inside one document block (CDC/SID/COP/COC).
     * This is specifically needed because MM repeats identical generic date
     * labels in each block and some builds generate unstable input names/ids.
     */
    private WebElement findDocumentSectionInput(WebDriver driver, String section, String kind) {
        try {
            Object found = ((JavascriptExecutor) driver).executeScript(
                    "var section=String(arguments[0]||'').toLowerCase();" +
                    "var kind=String(arguments[1]||'').toLowerCase();" +
                    "function vis(e){if(!e)return false;var r=e.getBoundingClientRect();var st=getComputedStyle(e);return r.width>0&&r.height>0&&st.display!=='none'&&st.visibility!=='hidden';}" +
                    "function meta(e){var p=e.closest('.MuiFormControl-root,.MuiTextField-root,[class*=MuiFormControl],[class*=MuiTextField]')||e.parentElement;return (((p&&p.innerText)||'')+' '+(e.name||'')+' '+(e.id||'')+' '+(e.placeholder||'')+' '+(e.getAttribute('aria-label')||'')).toLowerCase();}" +
                    "var inputs=Array.from(document.querySelectorAll('input,textarea')).filter(vis);" +
                    "var numberInput=null;" +
                    "for(var i=0;i<inputs.length;i++){var m=meta(inputs[i]);if(m.indexOf(section)>=0&&(m.indexOf('number')>=0||m.indexOf(' no')>=0||m.indexOf('_no')>=0)){numberInput=inputs[i];break;}}" +
                    "if(!numberInput){" +
                    " for(var j=0;j<inputs.length;j++){var nm=((inputs[j].name||'')+' '+(inputs[j].id||'')).toLowerCase();if(nm.indexOf(section)>=0&&(nm.indexOf('no')>=0||nm.indexOf('number')>=0)){numberInput=inputs[j];break;}}" +
                    "}" +
                    "if(!numberInput)return null;" +
                    "var container=numberInput.parentElement;" +
                    "for(var up=0;up<9&&container;up++,container=container.parentElement){" +
                    " var txt=((container.innerText)||'').toLowerCase();" +
                    " var blockInputs=Array.from(container.querySelectorAll('input,textarea')).filter(vis);" +
                    " if(blockInputs.length<2)continue;" +
                    " if(txt.indexOf(section)<0)continue;" +
                    " var idx=blockInputs.indexOf(numberInput);if(idx<0)continue;" +
                    " var matches=[];" +
                    " for(var k=idx+1;k<blockInputs.length;k++){var mm=meta(blockInputs[k]);" +
                    "   if(kind==='issue'&&(mm.indexOf('issue')>=0||mm.indexOf('renew')>=0))matches.push(blockInputs[k]);" +
                    "   if(kind==='expiry'&&(mm.indexOf('expir')>=0||mm.indexOf('valid')>=0))matches.push(blockInputs[k]);" +
                    " }" +
                    " if(matches.length)return matches[0];" +
                    " if(kind==='issue'&&idx+1<blockInputs.length)return blockInputs[idx+1];" +
                    " if(kind==='expiry'&&idx+2<blockInputs.length)return blockInputs[idx+2];" +
                    "}" +
                    "return null;",
                    section, kind
            );
            if (found instanceof WebElement e && displayed(e)) return e;
        } catch (Exception ignored) {}
        return null;
    }

    /**
     * Current Mariners Mentor Edit Customer is MUI/React.  Depending on the
     * build, a field may have a useful name, id, placeholder, aria-label or
     * only a visible MUI label.  This locator intentionally supports all of
     * those instead of depending on one old name= attribute.
     */
    private int updateInputSmart(WebDriver driver, String desired, String[] names, String... hints) {
        if (blank(desired)) return 0;
        WebElement input = findInputSmart(driver, names, hints);
        if (input == null) {
            System.out.println("MM FIELD NOT FOUND | " + String.join(" / ", hints));
            return 0;
        }

        String current = cleanNull(nvl(input.getAttribute("value")));
        if (!blank(current)) return 0;

        String wanted = adaptValueForInput(input, desired.trim());
        setInputValue(driver, input, wanted);
        System.out.println("MM FIELD FILLED | " + firstNonBlankText(hints) + " = " + maskForLog(wanted, hints));
        return 1;
    }

    private WebElement findInputSmart(WebDriver driver, String[] names, String... hints) {
        if (names != null) {
            for (String name : names) {
                if (blank(name)) continue;
                WebElement e = firstVisible(driver,
                        By.cssSelector("input[name='" + name + "']"),
                        By.cssSelector("textarea[name='" + name + "']"),
                        By.cssSelector("input[id='" + name + "']"),
                        By.cssSelector("textarea[id='" + name + "']"));
                if (e != null) return e;
            }
        }

        for (String hint : hints) {
            if (blank(hint)) continue;
            String safe = escapeXpath(hint);
            String lower = safe.toLowerCase(Locale.ROOT);
            String ci = "translate(normalize-space(.),'ABCDEFGHIJKLMNOPQRSTUVWXYZ','abcdefghijklmnopqrstuvwxyz')";
            String ciPlaceholder = "translate(@placeholder,'ABCDEFGHIJKLMNOPQRSTUVWXYZ','abcdefghijklmnopqrstuvwxyz')";
            String ciAria = "translate(@aria-label,'ABCDEFGHIJKLMNOPQRSTUVWXYZ','abcdefghijklmnopqrstuvwxyz')";

            WebElement e = firstVisible(driver,
                    By.xpath("//input[" + ciPlaceholder + "='" + lower + "']"),
                    By.xpath("//textarea[" + ciPlaceholder + "='" + lower + "']"),
                    By.xpath("//input[contains(" + ciPlaceholder + ",'" + lower + "')]"),
                    By.xpath("//textarea[contains(" + ciPlaceholder + ",'" + lower + "')]"),
                    By.xpath("//input[contains(" + ciAria + ",'" + lower + "')]"),
                    By.xpath("//textarea[contains(" + ciAria + ",'" + lower + "')]"),
                    By.xpath("//label[contains(" + ci + ",'" + lower + "')]/following::input[1]"),
                    By.xpath("//label[contains(" + ci + ",'" + lower + "')]/following::textarea[1]"),
                    By.xpath("//*[self::p or self::span][contains(" + ci + ",'" + lower + "')]/following::input[1]"),
                    By.xpath("//*[contains(@class,'MuiFormControl')][.//label[contains(" + ci + ",'" + lower + "')]]//input[1]"),
                    By.xpath("//*[contains(@class,'MuiFormControl')][.//label[contains(" + ci + ",'" + lower + "')]]//textarea[1]")
            );
            if (e != null) return e;

            // Final React/MUI fallback: inspect the nearest form-control text around
            // each input instead of depending on a stable id/name.
            try {
                Object jsFound = ((JavascriptExecutor) driver).executeScript(
                        "var h=arguments[0].toLowerCase();" +
                        "var els=Array.from(document.querySelectorAll('input,textarea'));" +
                        "for(var i=0;i<els.length;i++){" +
                        " var e=els[i]; if(e.offsetParent===null) continue;" +
                        " var p=e.closest('.MuiFormControl-root,.MuiTextField-root,[class*=MuiFormControl]')||e.parentElement;" +
                        " var txt=((p&&p.innerText)||'')+' '+(e.placeholder||'')+' '+(e.getAttribute('aria-label')||'')+' '+(e.name||'')+' '+(e.id||'');" +
                        " if(txt.toLowerCase().indexOf(h)>=0) return e;" +
                        "}" +
                        "return null;",
                        hint
                );
                if (jsFound instanceof WebElement found && displayed(found)) {
                    return found;
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private String adaptValueForInput(WebElement input, String desired) {
        if (blank(desired)) return desired;
        String type = nvl(input.getAttribute("type")).toLowerCase(Locale.ROOT);
        String placeholder = nvl(input.getAttribute("placeholder")).toLowerCase(Locale.ROOT);
        if (desired.matches("\\d{4}-\\d{2}-\\d{2}")) {
            if (type.equals("date")) return desired;
            if (placeholder.contains("dd-mm-yyyy") || placeholder.contains("dd/mm/yyyy")) {
                try {
                    java.time.LocalDate d = java.time.LocalDate.parse(desired);
                    return d.format(java.time.format.DateTimeFormatter.ofPattern(
                            placeholder.contains("/") ? "dd/MM/uuuu" : "dd-MM-uuuu"));
                } catch (Exception ignored) {}
            }
        }
        return desired;
    }


    /**
     * RPSL History/Comments used to receive long DG-generated descriptions.
     * Replace only blank fields or values that match the old bot-generated format;
     * never overwrite a staff-written note.
     */
    private int updateRpslLinkField(WebDriver driver, String desiredLinks, String hint) {
        if (blank(desiredLinks)) return 0;
        WebElement field = findInputSmart(driver, new String[0], hint);
        if (field == null) return 0;

        String current = cleanNull(nvl(field.getAttribute("value")));
        if (blank(current)) {
            setInputValue(driver, field, desiredLinks.trim());
            return 1;
        }

        String lower = current.toLowerCase(Locale.ROOT);
        boolean oldBotText = lower.contains(" | vessel ")
                || lower.contains("dg vessel:")
                || lower.contains("dg rpsl:")
                || lower.contains("flag :")
                || lower.contains("flag:")
                || lower.contains("docs.google.com/spreadsheets/d/19u_pwgf35zhvnonmujt2wqr7yzt6zfep9ej9w02uhal");

        if (!oldBotText) return 0;
        if (current.equals(desiredLinks.trim())) return 0;

        setInputValue(driver, field, desiredLinks.trim());
        return 1;
    }

    private int updateTextFieldSmart(WebDriver driver, String desired, String hint) {
        if (blank(desired)) return 0;
        WebElement field = findInputSmart(driver, new String[0], hint);
        if (field == null) return 0;
        String current = cleanNull(nvl(field.getAttribute("value")));
        if (!blank(current)) return 0;
        setInputValue(driver, field, desired.trim());
        return 1;
    }

    /**
     * Fill an MUI autocomplete/select only when blank.  If the DG value is not
     * present in the MM dropdown, return optionMissing=true so the caller can
     * preserve it in RPSL Comments rather than entering a non-existent option.
     */

    private WebElement findChoiceInput(WebDriver driver, String fieldHint) {
        if (blank(fieldHint)) return null;
        String lower = escapeXpath(fieldHint).toLowerCase(Locale.ROOT);
        String ciText = "translate(normalize-space(.),'ABCDEFGHIJKLMNOPQRSTUVWXYZ','abcdefghijklmnopqrstuvwxyz')";
        String ciPlaceholder = "translate(@placeholder,'ABCDEFGHIJKLMNOPQRSTUVWXYZ','abcdefghijklmnopqrstuvwxyz')";
        String ciAria = "translate(@aria-label,'ABCDEFGHIJKLMNOPQRSTUVWXYZ','abcdefghijklmnopqrstuvwxyz')";
        WebElement direct = firstVisible(driver,
                By.xpath("//input[" + ciPlaceholder + "='" + lower + "']"),
                By.xpath("//input[" + ciAria + "='" + lower + "']"),
                By.xpath("//label[" + ciText + "='" + lower + "']/following::input[1]"),
                By.xpath("//label[contains(" + ciText + ",'" + lower + "')]/following::input[1]"),
                By.xpath("//*[@role='combobox' and (" + ciAria + "='" + lower + "')]"));
        if (direct != null) return direct;

        try {
            Object jsFound = ((JavascriptExecutor) driver).executeScript(
                    "var h=arguments[0].toLowerCase();" +
                    "var els=Array.from(document.querySelectorAll('input,[role=combobox]'));" +
                    "for(var i=0;i<els.length;i++){" +
                    " var e=els[i]; if(e.offsetParent===null) continue;" +
                    " var p=e.closest('.MuiFormControl-root,.MuiAutocomplete-root,[class*=MuiFormControl],[class*=MuiAutocomplete]')||e.parentElement;" +
                    " var txt=((p&&p.innerText)||'')+' '+(e.placeholder||'')+' '+(e.getAttribute('aria-label')||'')+' '+(e.name||'')+' '+(e.id||'');" +
                    " if(txt.toLowerCase().indexOf(h)>=0) return e;" +
                    "}" +
                    "return null;",
                    fieldHint
            );
            if (jsFound instanceof WebElement found && displayed(found)) {
                return found;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private ChoiceResult updateMuiChoiceIfBlank(WebDriver driver, String fieldHint, String desired) {
        if (blank(desired)) return new ChoiceResult(0, false);

        WebElement input = findChoiceInput(driver, fieldHint);
        if (input == null) return new ChoiceResult(0, true);

        String current = cleanNull(nvl(input.getAttribute("value")));
        if (!blank(current) && !current.equalsIgnoreCase(fieldHint)) {
            return new ChoiceResult(0, false);
        }

        try {
            input.click();
            try {
                input.sendKeys(Keys.chord(Keys.CONTROL, "a"));
                input.sendKeys(desired.trim());
            } catch (Exception ignored) {}
            sleep(350);

            List<WebElement> options = driver.findElements(By.xpath("//*[@role='option']"));
            WebElement exact = null;
            WebElement contains = null;
            for (WebElement option : options) {
                if (!displayed(option)) continue;
                String text = cleanNull(option.getText());
                if (text.equalsIgnoreCase(desired.trim())) { exact = option; break; }
                if (contains == null && text.toLowerCase(Locale.ROOT).contains(desired.trim().toLowerCase(Locale.ROOT))) {
                    contains = option;
                }
            }
            WebElement choice = exact != null ? exact : contains;
            if (choice != null) {
                click(driver, choice);
                return new ChoiceResult(1, false);
            }

            try { input.sendKeys(Keys.ESCAPE); } catch (Exception ignored) {}
            // Do not leave free text in an autocomplete when it is not a valid option.
            try {
                input.sendKeys(Keys.chord(Keys.CONTROL, "a"));
                input.sendKeys(Keys.BACK_SPACE);
                input.sendKeys(Keys.TAB);
            } catch (Exception ignored) {}
            return new ChoiceResult(0, true);
        } catch (Exception e) {
            return new ChoiceResult(0, true);
        }
    }

    private String maskForLog(String value, String... hints) {
        String h = String.join(" ", hints).toLowerCase(Locale.ROOT);
        if (h.contains("passport") || h.contains("cdc") || h.contains("sid")
                || h.contains("coc") || h.contains("cop")) {
            String v = nvl(value).trim();
            return v.length() <= 4 ? "****" : v.substring(0, 2) + "***" + v.substring(v.length() - 2);
        }
        return value;
    }

    private int updateDocumentAnyName(
            WebDriver driver,
            CustomerProfileData.DocumentInfo doc,
            String[] numberNames,
            String[] issueNames,
            String[] expiryNames
    ) {
        if (doc == null || doc.isEmpty()) return 0;

        int changed = 0;
        changed += updateInputAnyName(driver, doc.number(), numberNames);
        changed += updateInputAnyName(driver, doc.issuedDate(), issueNames);
        changed += updateInputAnyName(driver, doc.expiryDate(), expiryNames);
        return changed;
    }

    private int updateDocument(WebDriver driver, String numberField, String issueField, String expiryField,
                               CustomerProfileData.DocumentInfo doc) {
        if (doc == null || doc.isEmpty()) return 0;
        int changed = 0;
        changed += updateInput(driver, numberField, doc.number());
        changed += updateInput(driver, issueField, doc.issuedDate());
        changed += updateInput(driver, expiryField, doc.expiryDate());
        return changed;
    }

    private int updateInputAnyName(WebDriver driver, String desired, String... names) {
        if (blank(desired)) return 0;
        for (String name : names) {
            WebElement input = firstVisible(driver, By.cssSelector("input[name='" + name + "']"));
            if (input == null) continue;
            String current = cleanNull(nvl(input.getAttribute("value")));
            if (!blank(current)) return 0;
            setInputValue(driver, input, desired.trim());
            return 1;
        }
        return 0;
    }

    private int updateInput(WebDriver driver, String name, String desired) {
        if (blank(desired)) return 0; // Never erase MM with an absent DG value.
        WebElement input = firstVisible(driver, By.cssSelector("input[name='" + name + "']"));
        if (input == null) return 0;
        String current = cleanNull(nvl(input.getAttribute("value")));

        // User rule: if Mariners Mentor already has ANY value, leave it exactly as it is.
        // DG data is used only to fill a blank field; existing MM data is never replaced.
        if (!blank(current)) return 0;

        String wanted = desired.trim();
        setInputValue(driver, input, wanted);
        return 1;
    }

    // ---------------------------------------------------------------------
    // DG SHIPPING
    // ---------------------------------------------------------------------

    private DgResult fetchDgProfile(WebDriver mmDriver, Config cfg,
                                    String indos, String password, String customerId,
                                    String customerDob, boolean membershipExtras) throws Exception {
        // Keep Mariners Mentor in Chrome. DG Shipping uses a separate browser
        // session. Firefox is preferred; Chrome is an automatic fallback.
        String mmWindow = mmDriver.getWindowHandle();

        WebDriver dgDriver = null;
        try {
            dgDriver = createDgDriver(cfg);
            if (!cfg.isHeadless()) {
                try { dgDriver.manage().window().maximize(); } catch (Exception ignored) {}
            }

            WebDriverWait dgWait = new WebDriverWait(dgDriver, Duration.ofSeconds(cfg.getDgWaitSeconds()));
            openDgLoginPage(dgDriver, cfg);
            loginDg(dgDriver, dgWait, indos, password);
            handleDgBackToHome(dgDriver, dgWait);

            // RPSL / sea-service source requested by admin: use the live DG menu
            // "View Sea Service and Acknowledge" and read every visible voyage row.
            // IMPORTANT: the bot only opens/reads this page. It never clicks the
            // per-row Acknowledge links.
            List<ResumeEntryData.VesselInfo> liveSeaServiceRows =
                    readDgSeaServiceRecords(dgDriver, dgWait);

            // Read DG e-Learning Details separately. These dates are stored as
            // eLearning Start/End dates; they are NOT treated as certificate expiry.
            List<ResumeEntryData.CourseInfo> elearningCourses =
                    readDgElearningCourses(dgDriver, dgWait);

            openUpdateSeafarerProfile(dgDriver, dgWait);

            // Some DG printable profiles omit values that are present on the live
            // Update Seafarer Profile page. Capture the live page as a fallback
            // before opening the printable profile.
            CustomerProfileData directData = readDgPageData(dgDriver);
            String directDob = firstNonBlankText(readDgDob(dgDriver), directData.dob());
            String directFather = firstNonBlankText(readDgFatherName(dgDriver), directData.fatherName());

            // Membership Lifetime only: use the official DG STCW Course checker
            // with INDoS + DOB and capture every certificate row, including the
            // Digital Certificate/View link. Prefer the MM DOB, but if MM DOB was
            // blank use the DOB just read from the authenticated DG profile.
            List<ResumeEntryData.CourseInfo> stcwCheckerCourses = membershipExtras
                    ? readDgStcwCourses(
                            dgDriver, dgWait, indos,
                            firstNonBlankText(customerDob, directDob))
                    : Collections.emptyList();

            Path pdf = openAndSavePrintableProfile(dgDriver, dgWait, customerId, indos);
            if (pdf == null || !Files.exists(pdf)) {
                throw new IllegalStateException("DG profile PDF not created/downloaded");
            }

            CustomerProfileData data = pdfParser.parse(pdf);
            ResumeEntryData resumeData = resumeEntryPdfParser.parse(pdf)
                    .withAdditionalCourses(elearningCourses)
                    .withStcwCheckerCourses(stcwCheckerCourses)
                    .withSeaServiceRows(liveSeaServiceRows);
            // The normalized sea-service parser is richer than the legacy compact
            // parser. Use its latest service row to fill blank MM Role/Vessel/RPSL.
            data = withLatestSeaServiceFallback(data, resumeData);

            String mergedDob = blank(data.dob())
                    ? CustomerProfilePdfParser.isoDate(directDob)
                    : data.dob();
            String mergedFather = blank(data.fatherName())
                    ? cleanNull(directFather)
                    : data.fatherName();
            CustomerProfileData.DocumentInfo mergedCdc = chooseDocument(data.cdc(), directData.cdc());
            CustomerProfileData.DocumentInfo mergedSid = chooseDocument(data.sid(), directData.sid());

            if (!Objects.equals(mergedDob, data.dob())
                    || !Objects.equals(mergedFather, data.fatherName())
                    || !Objects.equals(mergedCdc, data.cdc())
                    || !Objects.equals(mergedSid, data.sid())) {
                data = new CustomerProfileData(
                        data.indosNo(), mergedDob, mergedFather, data.passportNo(),
                        data.height(), data.weight(), mergedCdc, mergedSid,
                        data.cop(), data.coc(),
                        data.vesselName(), data.rpsl(), data.role(), data.rpslHistory(),
                        data.givenName(), data.surname(), data.email(), data.phone(),
                        data.city(), data.state(), data.country());
            }

            if (!cfg.isKeepDgPdf()) {
                try { Files.deleteIfExists(pdf); } catch (Exception ignored) {}
            }

            return new DgResult(mmWindow, "FIREFOX", pdf, data, resumeData);
        } finally {
            // DG Firefox is separate from the MM Chrome session. Closing Firefox here
            // cannot disturb the Mariners Mentor customer page.
            if (dgDriver != null) {
                try { dgDriver.quit(); } catch (Exception ignored) {}
            }
            try { mmDriver.switchTo().window(mmWindow); } catch (Exception ignored) {}
        }
    }



    private List<String> findMissingProfileFields(
            MmCustomer mm,
            CustomerProfileData data,
            boolean mlMembership,
            ResumeEntrySheetService.SheetWriteResult sheetWrite
    ) {
        List<String> missing = new ArrayList<>();
        if (mm == null || data == null) return missing;

        if (blank(mm.dob()) && blank(data.dob())) missing.add("DOB");
        if (blank(mm.fatherName()) && blank(data.fatherName())) missing.add("Father Name");
        if (blank(mm.passportNo()) && blank(data.passportNo())) missing.add("Passport Number");

        CustomerProfileData.DocumentInfo cdc = data.cdc() == null
                ? CustomerProfileData.DocumentInfo.empty() : data.cdc();
        if (blank(mm.cdcNo()) && blank(cdc.number())) missing.add("CDC Number");

        CustomerProfileData.DocumentInfo sid = data.sid() == null
                ? CustomerProfileData.DocumentInfo.empty() : data.sid();
        if (blank(mm.sidNo()) && blank(sid.number())) missing.add("SID Number");
        if (blank(mm.sidIssuedDate()) && blank(sid.issuedDate())) missing.add("SID Issue Date");
        if (blank(mm.sidExpiryDate()) && blank(sid.expiryDate())) missing.add("SID Expiry Date");

        if (blank(mm.role()) && blank(data.role())) missing.add("Role");
        if (blank(mm.vessel()) && blank(data.vesselName())) missing.add("Vessel");
        if (blank(mm.rpsl()) && blank(data.rpsl())) missing.add("RPSL");

        if (mlMembership && sheetWrite != null && sheetWrite.enabled()) {
            String link = firstNonBlankText(
                    sheetWrite.commaSeparatedVesselLinks(),
                    sheetWrite.rowLink()
            );
            if (blank(link)) missing.add("RPSL Sheet Link");
        }

        return missing;
    }

    private boolean hasRetryableMissingProfileData(List<String> missing) {
        if (missing == null || missing.isEmpty()) return false;
        for (String field : missing) {
            String f = nvl(field).toLowerCase(Locale.ROOT);
            if (f.contains("sid")
                    || f.contains("passport")
                    || f.contains("cdc")
                    || f.equals("dob")
                    || f.contains("father")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Public DG Shipping BSID/SID checker fallback.
     *
     * The checker CAPTCHA is deliberately manual. The bot only fills the INDoS,
     * enlarges the CAPTCHA area, waits for the operator to type the CAPTCHA and
     * click Search/Submit, then reads the returned SID number/dates/status.
     * No CAPTCHA bypass is attempted.
     */
    private SidLookupResult lookupSidFromPublicChecker(String indos, String customerId) {
        if (!cfg.isSidCheckerEnabled() || blank(indos)) {
            return SidLookupResult.notAttempted();
        }

        WebDriver driver = null;
        final int waitSeconds = Math.max(600, cfg.getSidCaptchaWaitSeconds());
        final Instant deadline = Instant.now().plusSeconds(waitSeconds);

        try {
            driver = createSidCheckerDriver();
            try { driver.manage().window().maximize(); } catch (Exception ignored) {}

            // BSID Version 2.0 (rolled out in 2026) uses a redesigned portal.
            // Always begin from the configured portal/home URL and then explicitly
            // enter the public SID Verification screen.  The old SIDChecker.jsp
            // endpoint can redirect to the V2 home page, so treating any random
            // text field on that page as the INDoS field is unsafe.
            driver.get(cfg.getSidCheckerUrl());

            WebDriverWait wait = new WebDriverWait(
                    driver,
                    Duration.ofSeconds(Math.max(20, cfg.getWaitSeconds()))
            );

            boolean navigationWarningPrinted = false;
            boolean indosFilledPrinted = false;
            boolean captchaReadyPrinted = false;
            boolean invalidCaptchaReported = false;
            boolean waitingForSidDatesPrinted = false;
            long lastNavigationAttempt = 0L;
            Instant sidNumberFirstSeenAt = null;
            SidLookupResult bestSidResult = SidLookupResult.notAttempted();

            while (Instant.now().isBefore(deadline)) {
                // The BSID portal sometimes loads the home page first and the menu click can
                // fail while React/JS is still starting. Never close the browser for that.
                // Keep retrying and also allow the operator to click SID Verification manually.
                // BSID V2 may expose more than one public verification mode.
                // Prefer the INDoS mode before locating/filling the search field.
                selectIndosLookupMode(driver);
                WebElement indosInput = findSidIndosInput(driver);
                if (indosInput == null) {
                    long now = System.currentTimeMillis();
                    if (now - lastNavigationAttempt >= 5000L) {
                        lastNavigationAttempt = now;
                        try {
                            openSidVerificationPage(driver, wait);
                        } catch (Exception navigationError) {
                            if (!navigationWarningPrinted) {
                                navigationWarningPrinted = true;
                                System.out.println();
                                System.out.println("==============================================");
                                System.out.println("SID VERIFICATION BROWSER WILL REMAIN OPEN | Customer ID: " + customerId);
                                System.out.println("Automatic SID Verification navigation is not ready yet.");
                                System.out.println("If the home page is shown, CLICK 'SID Verification' manually.");
                                System.out.println("The bot will detect the page, fill INDoS, and continue.");
                                System.out.println("Browser wait limit: " + waitSeconds + " seconds");
                                System.out.println("==============================================");
                            }
                        }
                    }
                    sleep(700);
                    continue;
                }

                // Keep trying until the exact INDoS is present. Do not abort/close if a modern
                // controlled input ignores the first Selenium value assignment.
                String currentIndos = cleanNull(indosInput.getAttribute("value"));
                if (!normalizeId(currentIndos).equals(normalizeId(indos))) {
                    try {
                        fillSidIndos(driver, indosInput, indos.trim());
                    } catch (Exception ignored) {}
                    currentIndos = cleanNull(indosInput.getAttribute("value"));
                }

                if (normalizeId(currentIndos).equals(normalizeId(indos)) && !indosFilledPrinted) {
                    indosFilledPrinted = true;
                    System.out.println("SID VERIFICATION INDOS FILLED | Customer ID: " + customerId
                            + " | INDoS: " + maskIndos(indos));
                }

                // CAPTCHA field on the current portal is labelled "Enter Security Code" and
                // may have placeholder text such as "Enter the code shown above" rather than
                // the word captcha. Support both old and new layouts.
                WebElement captchaInput = firstVisible(driver,
                        By.xpath("//input[contains(translate(@name,'ABCDEFGHIJKLMNOPQRSTUVWXYZ','abcdefghijklmnopqrstuvwxyz'),'captcha')]"),
                        By.xpath("//input[contains(translate(@id,'ABCDEFGHIJKLMNOPQRSTUVWXYZ','abcdefghijklmnopqrstuvwxyz'),'captcha')]"),
                        By.xpath("//input[contains(translate(@placeholder,'ABCDEFGHIJKLMNOPQRSTUVWXYZ','abcdefghijklmnopqrstuvwxyz'),'captcha')]"),
                        By.xpath("//input[contains(translate(@placeholder,'ABCDEFGHIJKLMNOPQRSTUVWXYZ','abcdefghijklmnopqrstuvwxyz'),'code shown')]"),
                        By.xpath("//*[contains(translate(normalize-space(.),'ABCDEFGHIJKLMNOPQRSTUVWXYZ','abcdefghijklmnopqrstuvwxyz'),'enter security code')]/following::input[not(@type='hidden')][1]")
                );

                WebElement captchaImage = firstVisible(driver,
                        By.xpath("//img[contains(translate(@id,'ABCDEFGHIJKLMNOPQRSTUVWXYZ','abcdefghijklmnopqrstuvwxyz'),'captcha')]"),
                        By.xpath("//img[contains(translate(@src,'ABCDEFGHIJKLMNOPQRSTUVWXYZ','abcdefghijklmnopqrstuvwxyz'),'captcha')]"),
                        By.xpath("//img[contains(translate(@alt,'ABCDEFGHIJKLMNOPQRSTUVWXYZ','abcdefghijklmnopqrstuvwxyz'),'captcha')]"),
                        By.xpath("//*[contains(translate(normalize-space(.),'ABCDEFGHIJKLMNOPQRSTUVWXYZ','abcdefghijklmnopqrstuvwxyz'),'enter security code')]/following::img[1]")
                );

                if (!captchaReadyPrinted) {
                    if (captchaImage != null) {
                        try {
                            ((JavascriptExecutor) driver).executeScript(
                                    "arguments[0].scrollIntoView({block:'center'});"
                                            + "arguments[0].style.width='320px';"
                                            + "arguments[0].style.height='auto';"
                                            + "arguments[0].style.maxWidth='95vw';"
                                            + "arguments[0].style.border='3px solid #1e7f2d';"
                                            + "arguments[0].style.padding='8px';",
                                    captchaImage
                            );
                        } catch (Exception ignored) {}
                    }

                    if (captchaInput != null) {
                        try {
                            ((JavascriptExecutor) driver).executeScript(
                                    "arguments[0].scrollIntoView({block:'center'});"
                                            + "arguments[0].style.fontSize='26px';"
                                            + "arguments[0].style.height='48px';",
                                    captchaInput
                            );
                            captchaInput.click();
                        } catch (Exception ignored) {}
                    }

                    System.out.println();
                    System.out.println("==============================================");
                    System.out.println("SID VERIFICATION CAPTCHA READY | Customer ID: " + customerId);
                    System.out.println("INDoS: " + maskIndos(indos));
                    System.out.println("TYPE CAPTCHA AND CLICK 'Verify SID' IN THE SID BROWSER");
                    System.out.println("DO NOT CLOSE THE SID BROWSER. The bot is waiting for the result.");
                    System.out.println("The bot will read SID Number + Issue Date + Expiry Date automatically.");
                    System.out.println("Wait limit: " + waitSeconds + " seconds");
                    System.out.println("==============================================");
                    captchaReadyPrinted = true;
                }

                SidLookupResult result = readSidCheckerResult(driver);
                if (result.sid() != null && !result.sid().isEmpty()) {
                    bestSidResult = mergeSidLookupResults(bestSidResult, result);
                    CustomerProfileData.DocumentInfo bestSid = bestSidResult.sid();

                    boolean numberReady = !blank(bestSid.number());
                    boolean issueReady = !blank(bestSid.issuedDate());
                    boolean expiryReady = !blank(bestSid.expiryDate());

                    if (numberReady && issueReady && expiryReady) {
                        System.out.println("SID VERIFICATION RESULT FOUND | Customer ID: " + customerId
                                + " | SID: " + maskSid(bestSid.number())
                                + " | Issue: " + displayDgDate(bestSid.issuedDate())
                                + " | Expiry: " + displayDgDate(bestSid.expiryDate())
                                + (blank(bestSidResult.status()) ? "" : " | Status: " + bestSidResult.status()));
                        return bestSidResult;
                    }

                    // Some BSID pages paint the SID number first and the dates a
                    // moment later. The old code returned immediately on the number,
                    // which is why MM received SID Number but left both dates Null.
                    if (numberReady) {
                        if (sidNumberFirstSeenAt == null) sidNumberFirstSeenAt = Instant.now();
                        if (!waitingForSidDatesPrinted) {
                            waitingForSidDatesPrinted = true;
                            System.out.println("SID VERIFICATION | SID number found; waiting for Issue Date + Expiry Date...");
                        }

                        // The SID portal can render the number before the date fields. Keep polling
                        // for a full minute so Issue Date / Expiry Date have time to appear.
                        // If the official checker genuinely does not publish a date, return
                        // the best partial result; missing-date retry logic keeps it retryable.
                        if (Duration.between(sidNumberFirstSeenAt, Instant.now()).toSeconds() >= 60) {
                            System.out.println("SID VERIFICATION PARTIAL RESULT | Customer ID: " + customerId
                                    + " | SID: " + maskSid(bestSid.number())
                                    + " | Issue: " + (issueReady ? displayDgDate(bestSid.issuedDate()) : "MISSING")
                                    + " | Expiry: " + (expiryReady ? displayDgDate(bestSid.expiryDate()) : "MISSING"));
                            return bestSidResult;
                        }
                    }
                }

                String body = safeBodyText(driver).toLowerCase(Locale.ROOT);
                if (body.contains("no record")
                        || body.contains("record not found")
                        || body.contains("no data found")
                        || body.contains("sid not found")) {
                    return new SidLookupResult(
                            true,
                            CustomerProfileData.DocumentInfo.empty(),
                            "No SID record found for this INDoS",
                            "NOT FOUND"
                    );
                }

                if (body.contains("invalid captcha")
                        || body.contains("incorrect captcha")
                        || body.contains("captcha is invalid")) {
                    if (!invalidCaptchaReported) {
                        invalidCaptchaReported = true;
                        System.out.println("SID VERIFICATION | CAPTCHA not accepted - enter the new CAPTCHA and click Verify SID again.");
                    }
                } else {
                    invalidCaptchaReported = false;
                }

                sleep(700);
            }

            if (bestSidResult.sid() != null && !bestSidResult.sid().isEmpty()) {
                return new SidLookupResult(
                        true, bestSidResult.sid(),
                        "SID result was only partially available before timeout",
                        firstNonBlankText(bestSidResult.status(), "PARTIAL")
                );
            }
            return new SidLookupResult(
                    true,
                    CustomerProfileData.DocumentInfo.empty(),
                    "SID CAPTCHA/result not completed within " + waitSeconds + " seconds",
                    "TIMEOUT"
            );

        } catch (Exception e) {
            // A recoverable portal/navigation issue must not produce an instant browser flash.
            // If the browser session is still alive, keep it open and allow manual navigation
            // to SID Verification; continue polling for a valid SID result until the same deadline.
            System.err.println("SID VERIFICATION AUTO FLOW ERROR | Customer ID: " + customerId
                    + " | " + safeMessage(e));

            if (driver != null) {
                System.out.println("SID VERIFICATION browser will remain open for manual completion.");
                System.out.println("Open/click SID Verification, ensure INDoS is filled, enter CAPTCHA, then click Verify SID.");

                while (Instant.now().isBefore(deadline)) {
                    try {
                        WebElement input = findSidIndosInput(driver);
                        if (input != null) {
                            String current = cleanNull(input.getAttribute("value"));
                            if (!normalizeId(current).equals(normalizeId(indos))) {
                                try { fillSidIndos(driver, input, indos.trim()); } catch (Exception ignored) {}
                            }
                        }

                        SidLookupResult recovered = readSidCheckerResult(driver);
                        if (recovered.sid() != null && !recovered.sid().isEmpty()) {
                            System.out.println("SID VERIFICATION RESULT FOUND AFTER MANUAL RECOVERY | Customer ID: "
                                    + customerId + " | SID: " + maskSid(recovered.sid().number()));
                            return recovered;
                        }
                    } catch (Exception browserGone) {
                        break;
                    }
                    sleep(700);
                }
            }

            return new SidLookupResult(
                    true,
                    CustomerProfileData.DocumentInfo.empty(),
                    "SID Verification failed: " + safeMessage(e),
                    "FAILED"
            );
        } finally {
            if (driver != null) {
                try { driver.quit(); } catch (Exception ignored) {}
            }
        }
    }

    private WebDriver createSidCheckerDriver() {
        // The rest of MMcasesBot is Chrome-based and Chrome is present on the
        // operator PCs. Use it first for the V2 BSID portal; keep Firefox only
        // as a fallback. CAPTCHA remains visible for manual entry.
        try {
            System.out.println("SID VERIFICATION: opening visible Chrome for manual CAPTCHA");
            ChromeOptions chrome = new ChromeOptions();
            chrome.addArguments(
                    "--window-size=1280,900",
                    "--disable-notifications",
                    "--disable-popup-blocking",
                    "--disable-extensions",
                    "--disable-background-networking",
                    "--disable-sync",
                    "--disable-default-apps",
                    "--no-first-run",
                    "--remote-allow-origins=*"
            );
            return new ChromeDriver(chrome);
        } catch (Exception chromeError) {
            System.out.println("SID VERIFICATION: Chrome unavailable - opening visible Firefox");
            FirefoxOptions firefox = new FirefoxOptions();
            firefox.addPreference("browser.download.alwaysOpenPanel", false);
            return new FirefoxDriver(firefox);
        }
    }

    /**
     * Navigate from the BSID home page to the public SID Verification page.
     * The portal label changed from "SID Checker" to "SID Verification";
     * this supports both and also handles links that open in a new tab.
     */
    private void openSidVerificationPage(WebDriver driver, WebDriverWait wait) {
        try {
            // If the configured legacy URL redirected to an old/error page, go
            // back to the current BSID portal root before looking for the V2 tile.
            if (findSidVerificationLink(driver) == null) {
                String home = sidPortalHome(cfg.getSidCheckerUrl());
                if (!sameUrlIgnoringSlash(driver.getCurrentUrl(), home)) {
                    driver.get(home);
                    waitForDocumentReady(driver);
                    sleep(900);
                }
            }

            Set<String> before = new LinkedHashSet<>(driver.getWindowHandles());
            WebElement verification = findSidVerificationLink(driver);
            if (verification == null) {
                throw new IllegalStateException("SID Verification link/tile not found on BSID V2 portal");
            }

            String href = cleanNull(verification.getAttribute("href"));
            if (!blank(href) && !href.toLowerCase(Locale.ROOT).startsWith("javascript")) {
                // Resolve relative V2 routes such as /sid-verification.
                URI base = URI.create(sidPortalHome(cfg.getSidCheckerUrl()));
                driver.get(base.resolve(href).toString());
            } else {
                try { click(driver, verification); }
                catch (Exception clickError) {
                    ((JavascriptExecutor) driver).executeScript("arguments[0].click();", verification);
                }
            }

            sleep(900);
            Set<String> after = new LinkedHashSet<>(driver.getWindowHandles());
            after.removeAll(before);
            if (!after.isEmpty()) {
                driver.switchTo().window(after.iterator().next());
            }

            wait.until(d -> findSidIndosInput(d) != null
                    || safeBodyText(d).toLowerCase(Locale.ROOT).contains("sid verification")
                    || d.getCurrentUrl().toLowerCase(Locale.ROOT).contains("sid"));

            System.out.println("SID VERIFICATION PAGE OPENED | " + driver.getCurrentUrl());
        } catch (Exception e) {
            throw new IllegalStateException("Could not open SID Verification page: " + safeMessage(e), e);
        }
    }

    private WebElement findSidVerificationLink(WebDriver driver) {
        WebElement direct = firstVisible(driver,
                By.xpath("//a[contains(translate(@href,'ABCDEFGHIJKLMNOPQRSTUVWXYZ','abcdefghijklmnopqrstuvwxyz'),'sid') and contains(translate(@href,'ABCDEFGHIJKLMNOPQRSTUVWXYZ','abcdefghijklmnopqrstuvwxyz'),'verif')]"),
                By.xpath("//*[self::a or self::button or @role='button'][contains(translate(normalize-space(.),'ABCDEFGHIJKLMNOPQRSTUVWXYZ','abcdefghijklmnopqrstuvwxyz'),'sid verification')]"),
                By.xpath("//*[self::a or self::button or @role='button'][contains(translate(normalize-space(.),'ABCDEFGHIJKLMNOPQRSTUVWXYZ','abcdefghijklmnopqrstuvwxyz'),'sid checker')]"),
                By.xpath("//*[contains(translate(normalize-space(.),'ABCDEFGHIJKLMNOPQRSTUVWXYZ','abcdefghijklmnopqrstuvwxyz'),'sid verification')]/ancestor::*[self::a or self::button or @role='button'][1]"),
                By.xpath("//*[contains(translate(normalize-space(.),'ABCDEFGHIJKLMNOPQRSTUVWXYZ','abcdefghijklmnopqrstuvwxyz'),'sid checker')]/ancestor::*[self::a or self::button or @role='button'][1]")
        );
        if (direct != null) return direct;

        // BSID V2 can render service cards as clickable DIVs (React router) with no
        // real <a> element. Locate the visible text and walk up to a clickable card.
        try {
            Object found = ((JavascriptExecutor) driver).executeScript(
                    "var els=Array.from(document.querySelectorAll('body *'));" +
                    "function vis(e){if(!e)return false;var r=e.getBoundingClientRect();var s=getComputedStyle(e);return r.width>0&&r.height>0&&s.display!=='none'&&s.visibility!=='hidden';}" +
                    "for(var i=0;i<els.length;i++){var e=els[i];if(!vis(e))continue;var t=String(e.innerText||e.textContent||'').replace(/\\s+/g,' ').trim().toLowerCase();" +
                    "if(t!=='sid verification' && t!=='sid checker')continue;" +
                    "var p=e;for(var n=0;n<7&&p;n++,p=p.parentElement){var tag=(p.tagName||'').toLowerCase();var role=(p.getAttribute&&p.getAttribute('role')||'').toLowerCase();" +
                    "if(tag==='a'||tag==='button'||role==='button'||p.onclick||p.getAttribute&&p.getAttribute('tabindex')==='0')return p;}return e;}return null;"
            );
            if (found instanceof WebElement element && displayed(element)) return element;
        } catch (Exception ignored) {}

        return null;
    }

    private String sidPortalHome(String configuredUrl) {
        try {
            URI u = URI.create(configuredUrl);
            String scheme = blank(u.getScheme()) ? "https" : u.getScheme();
            String authority = u.getRawAuthority();
            if (!blank(authority)) return scheme + "://" + authority + "/";
        } catch (Exception ignored) {}
        return "https://dgshippingbsid.in/";
    }

    private boolean sameUrlIgnoringSlash(String a, String b) {
        String aa = cleanNull(a).replaceAll("/+$", "");
        String bb = cleanNull(b).replaceAll("/+$", "");
        return aa.equalsIgnoreCase(bb);
    }

    private void waitForDocumentReady(WebDriver driver) {
        try {
            new WebDriverWait(driver, Duration.ofSeconds(Math.max(15, cfg.getWaitSeconds())))
                    .until(d -> "complete".equals(String.valueOf(
                            ((JavascriptExecutor) d).executeScript("return document.readyState"))));
        } catch (Exception ignored) {}
    }

    /** Find the public SID Verification INDoS No field across old and new layouts. */
    private WebElement findSidIndosInput(WebDriver driver) {
        // IMPORTANT: never fall back to the first arbitrary text input. On BSID
        // V2 the home/login page also contains text inputs, and the old fallback
        // could fill the LOGIN field instead of opening SID Verification.
        return firstVisible(driver,
                By.xpath("//label[contains(translate(normalize-space(.),'ABCDEFGHIJKLMNOPQRSTUVWXYZ','abcdefghijklmnopqrstuvwxyz'),'indos')]/following::input[not(@type='hidden')][1]"),
                By.xpath("//*[self::span or self::div or self::p][contains(translate(normalize-space(.),'ABCDEFGHIJKLMNOPQRSTUVWXYZ','abcdefghijklmnopqrstuvwxyz'),'indos no')]/following::input[not(@type='hidden')][1]"),
                By.xpath("//*[self::span or self::div or self::p][contains(translate(normalize-space(.),'ABCDEFGHIJKLMNOPQRSTUVWXYZ','abcdefghijklmnopqrstuvwxyz'),'indos number')]/following::input[not(@type='hidden')][1]"),
                By.xpath("//input[contains(translate(@name,'ABCDEFGHIJKLMNOPQRSTUVWXYZ','abcdefghijklmnopqrstuvwxyz'),'indos')]"),
                By.xpath("//input[contains(translate(@id,'ABCDEFGHIJKLMNOPQRSTUVWXYZ','abcdefghijklmnopqrstuvwxyz'),'indos')]"),
                By.xpath("//input[contains(translate(@placeholder,'ABCDEFGHIJKLMNOPQRSTUVWXYZ','abcdefghijklmnopqrstuvwxyz'),'indos')]"),
                By.xpath("//input[contains(translate(@aria-label,'ABCDEFGHIJKLMNOPQRSTUVWXYZ','abcdefghijklmnopqrstuvwxyz'),'indos')]")
        );
    }

    /** Fill INDoS No and explicitly fire browser events used by modern controlled forms. */
    private void fillSidIndos(WebDriver driver, WebElement input, String indos) {
        setInputValue(driver, input, indos);
        String current = cleanNull(input.getAttribute("value"));
        if (normalizeId(current).equals(normalizeId(indos))) return;

        try {
            input.click();
            input.sendKeys(Keys.chord(Keys.CONTROL, "a"));
            input.sendKeys(Keys.BACK_SPACE);
            input.sendKeys(indos);
            input.sendKeys(Keys.TAB);
        } catch (Exception ignored) {}

        current = cleanNull(input.getAttribute("value"));
        if (normalizeId(current).equals(normalizeId(indos))) return;

        ((JavascriptExecutor) driver).executeScript(
                "var el=arguments[0],v=arguments[1];" +
                        "var p=Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value');" +
                        "if(p&&p.set){p.set.call(el,v);}else{el.value=v;}" +
                        "['input','change','blur'].forEach(function(n){el.dispatchEvent(new Event(n,{bubbles:true}));});",
                input, indos);
    }

    private void selectIndosLookupMode(WebDriver driver) {
        try {
            // Native select used by some checker versions.
            for (WebElement selectEl : driver.findElements(By.tagName("select"))) {
                if (!displayed(selectEl)) continue;
                try {
                    Select select = new Select(selectEl);
                    for (WebElement option : select.getOptions()) {
                        String text = cleanNull(option.getText()).toLowerCase(Locale.ROOT);
                        String value = cleanNull(option.getAttribute("value")).toLowerCase(Locale.ROOT);
                        if (text.contains("indos") || value.contains("indos")) {
                            select.selectByVisibleText(option.getText());
                            sleep(250);
                            return;
                        }
                    }
                } catch (Exception ignored) {}
            }

            // Native radio.
            for (WebElement radio : driver.findElements(By.xpath("//input[@type='radio']"))) {
                if (!displayed(radio)) continue;
                String meta = (nvl(radio.getAttribute("value")) + " "
                        + nvl(radio.getAttribute("name")) + " "
                        + nvl(radio.getAttribute("id")) + " "
                        + nvl(radio.getAttribute("aria-label"))).toLowerCase(Locale.ROOT);
                if (meta.contains("indos")) {
                    if (!radio.isSelected()) click(driver, radio);
                    sleep(250);
                    return;
                }
            }

            // V2 can render the lookup selector as a Material/Bootstrap tab or button.
            WebElement mode = firstVisible(driver,
                    By.xpath("//*[@role='radio' and contains(translate(normalize-space(.),'ABCDEFGHIJKLMNOPQRSTUVWXYZ','abcdefghijklmnopqrstuvwxyz'),'indos')]"),
                    By.xpath("//*[@role='tab' and contains(translate(normalize-space(.),'ABCDEFGHIJKLMNOPQRSTUVWXYZ','abcdefghijklmnopqrstuvwxyz'),'indos')]"),
                    By.xpath("//*[self::button or self::label][contains(translate(normalize-space(.),'ABCDEFGHIJKLMNOPQRSTUVWXYZ','abcdefghijklmnopqrstuvwxyz'),'indos')]"));
            if (mode != null) {
                click(driver, mode);
                sleep(250);
            }
        } catch (Exception ignored) {}
    }

    private SidLookupResult mergeSidLookupResults(SidLookupResult a, SidLookupResult b) {
        if (a == null || !a.attempted()) return b == null ? SidLookupResult.notAttempted() : b;
        if (b == null || !b.attempted()) return a;
        CustomerProfileData.DocumentInfo ad = a.sid() == null
                ? CustomerProfileData.DocumentInfo.empty() : a.sid();
        CustomerProfileData.DocumentInfo bd = b.sid() == null
                ? CustomerProfileData.DocumentInfo.empty() : b.sid();
        CustomerProfileData.DocumentInfo merged = new CustomerProfileData.DocumentInfo(
                firstNonBlankText(ad.number(), bd.number()),
                firstNonBlankText(ad.issuedDate(), bd.issuedDate()),
                firstNonBlankText(ad.expiryDate(), bd.expiryDate())
        );
        return new SidLookupResult(
                true, merged,
                firstNonBlankText(b.detail(), a.detail()),
                firstNonBlankText(b.status(), a.status())
        );
    }

    private SidLookupResult readSidCheckerResult(WebDriver driver) {
        String body = safeBodyText(driver);

        // Genuine SID/BSID examples are one letter followed by digits.
        // Reject nearby form text such as "Enter code".
        String labelledSid = textNearLabel(
                driver,
                "SID Number", "SID No", "SID No.", "SID",
                "BSID Number", "BSID No", "BSID No.", "BSID"
        );
        String sidNo = firstNonBlankText(
                extractValidSidNumber(labelledSid),
                extractSidNearLabelFromBody(body)
        );

        String issued = firstNonBlankText(
                textNearLabel(driver,
                        "SID Issue Date", "SID Issued Date", "SID Date of Issue",
                        "Date of SID Issue", "Date of Issue", "Issued Date", "Issue Date", "Issued On",
                        "Date of Issuance", "SID Issuance Date", "Issue Date / Time"),
                readSidResultFieldFromDom(driver, false),
                regexGroup(body,
                        "(?im)(?:BSID|SID)?\\s*(?:Issue Date|Issued Date|Date of Issue|Issued On)\\s*[:\\-]?\\s*"
                                + "(\\d{1,2}[/-]\\d{1,2}[/-]\\d{2,4}|\\d{4}-\\d{1,2}-\\d{1,2}|\\d{1,2}[- ]?[A-Za-z]{3,9}[- ]?\\d{2,4})")
        );

        String expiry = firstNonBlankText(
                textNearLabel(driver,
                        "SID Expiry Date", "SID Date of Expiry", "Date of SID Expiry",
                        "Date of Expiry", "Expiry Date", "Valid Upto", "Valid Up To",
                        "Valid Until", "Valid Till", "Validity Date", "Validity Upto",
                        "SID Valid Upto", "SID Valid Up To", "Expiry Date / Time"),
                readSidResultFieldFromDom(driver, true),
                regexGroup(body,
                        "(?im)(?:BSID|SID)?\\s*(?:Expiry Date|Date of Expiry|Valid Upto|Valid Up To|Valid Until|Valid Till|Validity Date|Validity Upto)\\s*[:\\-]?\\s*"
                                + "(\\d{1,2}[/-]\\d{1,2}[/-]\\d{2,4}|\\d{4}-\\d{1,2}-\\d{1,2}|\\d{1,2}[- ]?[A-Za-z]{3,9}[- ]?\\d{2,4}|LIFETIME)")
        );

        String status = firstNonBlankText(
                textNearLabel(driver, "SID Status", "Status"),
                regexGroup(body,
                        "(?im)(?:SID\\s*)?Status\\s*[:\\-]?\\s*([A-Za-z][A-Za-z ]{2,30})")
        );

        String normalizedSid = cleanSidNumber(sidNo);
        CustomerProfileData.DocumentInfo sid = new CustomerProfileData.DocumentInfo(
                normalizedSid,
                sidIsoDate(issued),
                "LIFETIME".equalsIgnoreCase(cleanNull(expiry))
                        ? "LIFETIME"
                        : sidIsoDate(expiry)
        );

        return new SidLookupResult(true, sid, "SID Verification result", cleanNull(status));
    }

    /**
     * Read SID result dates from table/card layouts where the visible labels are
     * generic (Issue Date / Expiry Date) and values may be in sibling cells or
     * readonly inputs. Restrict the search to a container that also contains an
     * SID/BSID number so unrelated CDC/passport dates are not taken.
     */
    private String readSidResultFieldFromDom(WebDriver driver, boolean expiry) {
        try {
            Object value = ((JavascriptExecutor) driver).executeScript(
                    "var expiry=!!arguments[0];" +
                    "function vis(e){if(!e)return false;var r=e.getBoundingClientRect();var st=getComputedStyle(e);return r.width>0&&r.height>0&&st.display!=='none'&&st.visibility!=='hidden';}" +
                    "function txt(e){return e?String(e.innerText||e.textContent||'').replace(/\\s+/g,' ').trim():'';}" +
                    "function val(e){if(!e)return '';if('value' in e&&e.value)return String(e.value).trim();var q=e.querySelector&&e.querySelector('input,textarea,[data-value]');if(q){if(q.value)return String(q.value).trim();var dv=q.getAttribute('data-value');if(dv)return String(dv).trim();}return txt(e);}" +
                    "function isTarget(t){t=(t||'').toLowerCase();return expiry?(t.indexOf('expir')>=0||t.indexOf('valid upto')>=0||t.indexOf('valid up to')>=0||t.indexOf('valid until')>=0||t.indexOf('valid till')>=0||t.indexOf('validity')>=0):(t.indexOf('issue')>=0||t.indexOf('issued')>=0||t.indexOf('issuance')>=0);}" +
                    "function dateish(v){return /(?:\\d{1,2}[\\/-]\\d{1,2}[\\/-]\\d{2,4}|\\d{4}-\\d{1,2}-\\d{1,2}|\\d{1,2}[- ]?[A-Za-z]{3,9}[- ]?\\d{2,4}|LIFETIME)/i.test(v||'');}" +
                    "var nodes=Array.from(document.querySelectorAll('tr,table,div,section,article,fieldset,li'));" +
                    "var blocks=[];" +
                    "for(var i=0;i<nodes.length;i++){var n=nodes[i];if(!vis(n))continue;var t=txt(n);if(!/(?:\\bSID\\b|\\bBSID\\b)/i.test(t))continue;if(!/[A-Z]\\s*[-/]?\\s*\\d{7,10}/i.test(t))continue;if(!isTarget(t))continue;blocks.push(n);}" +
                    "blocks.sort(function(a,b){return txt(a).length-txt(b).length;});" +
                    "for(var b=0;b<blocks.length;b++){var block=blocks[b];var labs=Array.from(block.querySelectorAll('label,th,td,dt,dd,span,p,div,strong,b')).filter(vis);" +
                    " for(var j=0;j<labs.length;j++){var e=labs[j];var et=txt(e);if(!et||!isTarget(et))continue;" +
                    "   var same=et.match(/(?:Issue(?:d|\\s+Date|\\s+of\\s+Issue|\\s+of\\s+Issuance)?|Expir(?:y|ation)(?:\\s+Date)?|Valid(?:ity)?(?:\\s+Upto|\\s+Up\\s+To|\\s+Until|\\s+Till)?)\\s*[:\\-]?\\s*((?:\\d{1,2}[\\/-]\\d{1,2}[\\/-]\\d{2,4}|\\d{4}-\\d{1,2}-\\d{1,2}|\\d{1,2}[- ]?[A-Za-z]{3,9}[- ]?\\d{2,4}|LIFETIME))/i);if(same&&same[1])return same[1];" +
                    "   var next=e.nextElementSibling;if(next){var nv=val(next);if(nv&&!isTarget(nv)&&dateish(nv))return nv;}" +
                    "   var cell=e.closest('td,th');var row=e.closest('tr');if(row&&cell){var cells=Array.from(row.querySelectorAll('td,th'));var ix=cells.indexOf(cell);for(var k=ix+1;k<cells.length;k++){var cv=val(cells[k]);if(dateish(cv))return cv;}}" +
                    "   var p=e.parentElement;if(p){var kids=Array.from(p.children||[]);var ex=kids.indexOf(e);for(var q=ex+1;q<kids.length;q++){var pv=val(kids[q]);if(dateish(pv))return pv;}var inp=p.querySelector('input,textarea');if(inp&&inp!==e&&dateish(inp.value))return inp.value;}" +
                    "   var wrap=e.closest('.MuiGrid-root,.MuiBox-root,.MuiFormControl-root,.card,[class*=card],[class*=Card]');if(wrap){var all=Array.from(wrap.querySelectorAll('input,textarea,p,span,div')).filter(vis);for(var z=0;z<all.length;z++){var wv=val(all[z]);if(dateish(wv)&&!isTarget(wv))return wv;}}" +
                    " }" +
                    "}" +
                    "return '';", expiry
            );
            return cleanNull(value == null ? "" : String.valueOf(value));
        } catch (Exception ignored) {
            return "";
        }
    }

    private String sidIsoDate(String raw) {
        String value = cleanNull(raw);
        if (blank(value)) return "";

        String standard = CustomerProfilePdfParser.isoDate(value);
        if (!blank(standard)) return standard;

        // New/old BSID checker builds have used both abbreviated and full
        // English month names, sometimes separated by spaces.
        String normalized = value.replace(',', ' ').replaceAll("\\s+", " ").trim();
        java.util.List<java.time.format.DateTimeFormatter> formats = java.util.List.of(
                java.time.format.DateTimeFormatter.ofPattern("d MMM uuuu", Locale.ENGLISH),
                java.time.format.DateTimeFormatter.ofPattern("d MMMM uuuu", Locale.ENGLISH),
                java.time.format.DateTimeFormatter.ofPattern("d-MMMM-uuuu", Locale.ENGLISH),
                java.time.format.DateTimeFormatter.ofPattern("d/MM/uu", Locale.ENGLISH),
                java.time.format.DateTimeFormatter.ofPattern("d-MM-uu", Locale.ENGLISH)
        );
        for (java.time.format.DateTimeFormatter f : formats) {
            try {
                return java.time.LocalDate.parse(normalized, f).toString();
            } catch (Exception ignored) {}
        }

        // If the DOM helper returned surrounding text, extract the date part.
        Matcher m = Pattern.compile(
                "(?i)(\\d{1,2}[/-]\\d{1,2}[/-]\\d{2,4}|\\d{4}-\\d{1,2}-\\d{1,2}|\\d{1,2}[- ]+[A-Za-z]{3,9}[- ]+\\d{2,4})"
        ).matcher(normalized);
        if (m.find() && !m.group(1).equals(normalized)) {
            return sidIsoDate(m.group(1));
        }
        return "";
    }

    private String extractValidSidNumber(String text) {
        if (blank(text)) return "";
        try {
            // Indian SID/BSID is normally one letter followed by 7-10 digits.
            // BSID V2 pages may display a separator or space (for example M-12345678).
            Matcher m = Pattern.compile("(?i)\\b([A-Z])\\s*[-/]?\\s*(\\d{7,10})\\b").matcher(text);
            return m.find() ? (m.group(1) + m.group(2)).toUpperCase(Locale.ROOT) : "";
        } catch (Exception ignored) {
            return "";
        }
    }

    private String extractSidNearLabelFromBody(String body) {
        if (blank(body)) return "";
        try {
            // Do NOT accept an arbitrary passport-like value from the page.
            // Only accept a candidate that is explicitly next to SID/BSID wording.
            Matcher labelled = Pattern.compile(
                    "(?is)\\b(?:BSID|SID)\\s*(?:NO\\.?|NUMBER)?\\s*[:#-]?\\s*"
                            + "([A-Z]\\s*[-/]?\\s*\\d{7,10})\\b"
            ).matcher(body);
            if (labelled.find()) return extractValidSidNumber(labelled.group(1));

            String[] lines = body.replace('\r', '\n').split("\\n+");
            for (int i = 0; i < lines.length; i++) {
                String line = cleanNull(lines[i]);
                String lower = line.toLowerCase(Locale.ROOT);
                if (!(lower.contains("sid no") || lower.contains("sid number")
                        || lower.contains("bsid no") || lower.contains("bsid number"))) {
                    continue;
                }
                String sameLine = extractValidSidNumber(line);
                if (!blank(sameLine)) return sameLine;
                for (int j = i + 1; j < Math.min(lines.length, i + 3); j++) {
                    String candidate = extractValidSidNumber(lines[j]);
                    if (!blank(candidate)) return candidate;
                }
            }
        } catch (Exception ignored) {}
        return "";
    }

    private String safeBodyText(WebDriver driver) {
        try {
            return nvl(driver.findElement(By.tagName("body")).getText());
        } catch (Exception e) {
            return "";
        }
    }

    private String regexGroup(String text, String regex) {
        if (blank(text)) return "";
        try {
            Matcher m = Pattern.compile(regex).matcher(text);
            return m.find() ? cleanNull(m.group(1)) : "";
        } catch (Exception ignored) {
            return "";
        }
    }

    private String cleanSidNumber(String value) {
        String sid = extractValidSidNumber(cleanNull(value));
        return sid.matches("(?i)^[A-Z]\\d{7,10}$")
                ? sid.toUpperCase(Locale.ROOT)
                : "";
    }

    private String maskSid(String sid) {
        String s = cleanNull(sid).replaceAll("\\s+", "");
        if (s.length() <= 4) return "****";
        return s.substring(0, Math.min(2, s.length())) + "***" + s.substring(s.length() - 2);
    }

    private boolean shouldLookupSid(MmCustomer mm, CustomerProfileData data) {
        if (!cfg.isSidCheckerEnabled() || mm == null || data == null) return false;
        CustomerProfileData.DocumentInfo sid = data.sid() == null
                ? CustomerProfileData.DocumentInfo.empty()
                : data.sid();

        boolean numberMissing = blank(mm.sidNo()) && blank(sid.number());
        boolean issueMissing = blank(mm.sidIssuedDate()) && blank(sid.issuedDate());
        boolean expiryMissing = blank(mm.sidExpiryDate()) && blank(sid.expiryDate());
        return numberMissing || issueMissing || expiryMissing;
    }

    private DgResult mergeSidIntoDgResult(DgResult dg, SidLookupResult sidLookup) {
        if (dg == null || dg.data() == null || sidLookup == null
                || sidLookup.sid() == null || sidLookup.sid().isEmpty()) {
            return dg;
        }

        CustomerProfileData base = dg.data();
        CustomerProfileData.DocumentInfo mergedSid = chooseDocument(base.sid(), sidLookup.sid());
        CustomerProfileData mergedData = new CustomerProfileData(
                base.indosNo(), base.dob(), base.fatherName(), base.passportNo(),
                base.height(), base.weight(), base.cdc(), mergedSid, base.cop(), base.coc(),
                base.vesselName(), base.rpsl(), base.role(), base.rpslHistory(),
                base.givenName(), base.surname(), base.email(), base.phone(),
                base.city(), base.state(), base.country()
        );

        return new DgResult(dg.mmWindow(), dg.dgWindow(), dg.pdf(), mergedData, dg.resumeData());
    }

    private CustomerProfileData withLatestSeaServiceFallback(
            CustomerProfileData base,
            ResumeEntryData resumeData
    ) {
        if (base == null || resumeData == null) return base;
        ResumeEntryData.VesselInfo latest = resumeData.latestVessel();
        if (latest == null || latest.isEmpty()) return base;

        String vessel = blank(base.vesselName()) ? cleanNull(latest.vesselName()) : base.vesselName();
        String role = blank(base.role()) ? cleanNull(latest.rank()) : base.role();
        String rpsl = blank(base.rpsl()) ? cleanNull(latest.companyName()) : base.rpsl();

        if (Objects.equals(vessel, base.vesselName())
                && Objects.equals(role, base.role())
                && Objects.equals(rpsl, base.rpsl())) {
            return base;
        }

        return new CustomerProfileData(
                base.indosNo(), base.dob(), base.fatherName(), base.passportNo(),
                base.height(), base.weight(), base.cdc(), base.sid(), base.cop(), base.coc(),
                vessel, rpsl, role, base.rpslHistory(), base.givenName(), base.surname(),
                base.email(), base.phone(), base.city(), base.state(), base.country());
    }

    private WebDriver createDgDriver(Config cfg) {
        // Use Chrome first for DG Shipping. This keeps the automated browser on the
        // same DG/eSamudra page the operator sees manually and makes the legacy login
        // form behaviour easier to verify. Firefox remains only as a fallback.
        ChromeOptions chrome = new ChromeOptions();
        if (cfg.isHeadless()) chrome.addArguments("--headless=new");
        chrome.addArguments(
                "--window-size=1920,1080",
                "--disable-notifications",
                "--disable-popup-blocking",
                "--disable-gpu",
                "--no-sandbox",
                "--disable-dev-shm-usage",
                "--remote-allow-origins=*");
        Map<String, Object> prefs = new HashMap<>();
        prefs.put("download.default_directory", DG_DOWNLOAD_DIR.toString());
        prefs.put("download.prompt_for_download", false);
        prefs.put("download.directory_upgrade", true);
        prefs.put("plugins.always_open_pdf_externally", true);
        chrome.setExperimentalOption("prefs", prefs);

        try {
            System.out.println("DG: opening official eSamudra site in Chrome");
            return new ChromeDriver(chrome);
        } catch (Exception chromeError) {
            System.out.println("DG: Chrome unavailable - using Firefox fallback");

            FirefoxOptions firefox = new FirefoxOptions();
            if (cfg.isHeadless()) firefox.addArguments("-headless");
            firefox.addPreference("browser.download.folderList", 2);
            firefox.addPreference("browser.download.dir", DG_DOWNLOAD_DIR.toString());
            firefox.addPreference("browser.download.useDownloadDir", true);
            firefox.addPreference("browser.download.alwaysOpenPanel", false);
            firefox.addPreference("browser.download.manager.showWhenStarting", false);
            firefox.addPreference("browser.helperApps.alwaysAsk.force", false);
            firefox.addPreference("browser.helperApps.neverAsk.saveToDisk",
                    "application/pdf,application/x-pdf,application/octet-stream,application/force-download");
            firefox.addPreference("pdfjs.disabled", true);
            return new FirefoxDriver(firefox);
        }
    }

    /**
     * Reads the official DG "View Sea Service and Acknowledge" page.
     *
     * This method is read-only: it never clicks an individual Acknowledge link.
     * The page is the preferred source for RPSL/company, vessel, rank, flag and
     * service dates. Technical vessel details are later merged from the printable
     * seafarer profile.
     */
    private List<ResumeEntryData.VesselInfo> readDgSeaServiceRecords(
            WebDriver driver,
            WebDriverWait wait
    ) {
        List<ResumeEntryData.VesselInfo> out = new ArrayList<>();
        String originalUrl = "";
        String originalWindow = "";
        Set<String> beforeHandles = new LinkedHashSet<>();

        try {
            originalUrl = nvl(driver.getCurrentUrl());
            originalWindow = driver.getWindowHandle();
            beforeHandles.addAll(driver.getWindowHandles());

            WebElement link = firstVisible(driver,
                    By.xpath("//a[contains(translate(normalize-space(.),'ABCDEFGHIJKLMNOPQRSTUVWXYZ','abcdefghijklmnopqrstuvwxyz'),'view sea service and acknowledge')]"),
                    By.xpath("//a[contains(translate(@href,'ABCDEFGHIJKLMNOPQRSTUVWXYZ','abcdefghijklmnopqrstuvwxyz'),'method=viewseaservice')]"),
                    By.xpath("//a[contains(translate(@href,'ABCDEFGHIJKLMNOPQRSTUVWXYZ','abcdefghijklmnopqrstuvwxyz'),'menu_web_seaservice')]"),
                    By.xpath("//*[contains(translate(normalize-space(.),'ABCDEFGHIJKLMNOPQRSTUVWXYZ','abcdefghijklmnopqrstuvwxyz'),'view sea service')]/ancestor::a[1]")
            );

            if (link == null) {
                System.out.println("DG SEA SERVICE | 'View Sea Service and Acknowledge' link not found - printable profile fallback will be used");
                return out;
            }

            click(driver, link);

            final String seaStartUrl = originalUrl;
            final int seaStartWindowCount = beforeHandles.size();
            try {
                new WebDriverWait(driver, Duration.ofSeconds(6)).until(d ->
                        d.getWindowHandles().size() > seaStartWindowCount
                                || !nvl(d.getCurrentUrl()).equals(seaStartUrl));
                for (String handle : driver.getWindowHandles()) {
                    if (!beforeHandles.contains(handle)) {
                        driver.switchTo().window(handle);
                        break;
                    }
                }
            } catch (Exception ignored) {}

            wait.until(d -> {
                try {
                    String body = nvl(d.findElement(By.tagName("body")).getText())
                            .toLowerCase(Locale.ROOT);
                    return body.contains("sea service details")
                            || body.contains("articles of agreement details")
                            || body.contains("earlier form iiia")
                            || body.contains("date of sign on ship")
                            || body.contains("rpsl/company name");
                } catch (Exception e) {
                    return false;
                }
            });

            out.addAll(parseDgSeaServiceTables(driver));
            System.out.println("DG SEA SERVICE | Rows read from 'View Sea Service and Acknowledge': " + out.size());

            if (!out.isEmpty()) {
                ResumeEntryData.VesselInfo latest = out.get(0);
                System.out.println("DG SEA SERVICE | First row | RPSL: " + latest.companyName()
                        + " | Vessel: " + latest.vesselName()
                        + " | Rank: " + latest.rank()
                        + " | From: " + latest.serviceFrom()
                        + " | To: " + latest.serviceTo());
            }
        } catch (Exception e) {
            System.out.println("DG SEA SERVICE | Could not read Sea Service page - "
                    + safeMessage(e) + " - continuing with printable profile");
        } finally {
            restoreDgHomeAfterReadOnlyPage(driver, originalWindow, originalUrl);
        }

        return out;
    }

    private List<ResumeEntryData.VesselInfo> parseDgSeaServiceTables(WebDriver driver) {
        LinkedHashMap<String, ResumeEntryData.VesselInfo> unique = new LinkedHashMap<>();

        for (WebElement table : driver.findElements(By.tagName("table"))) {
            List<WebElement> rows;
            try {
                rows = table.findElements(By.cssSelector("tr"));
            } catch (Exception ignored) {
                continue;
            }
            if (rows == null || rows.isEmpty()) continue;

            int headerRow = -1;
            List<String> headers = Collections.emptyList();
            for (int r = 0; r < rows.size(); r++) {
                List<WebElement> cells = rows.get(r).findElements(By.cssSelector("th,td"));
                if (cells.size() < 4) continue;

                List<String> candidate = new ArrayList<>();
                StringBuilder joined = new StringBuilder();
                for (WebElement cell : cells) {
                    String h = normalizeDgHeader(cell.getText());
                    candidate.add(h);
                    if (!h.isBlank()) joined.append(' ').append(h);
                }

                String all = joined.toString();
                boolean hasCompany = all.contains("rpsl company name") || all.contains("company name");
                boolean hasVessel = all.contains("vessel name");
                boolean hasRank = all.contains("rank");
                boolean hasDates = all.contains("sign on ship")
                        || all.contains("commencement of contract")
                        || all.contains("sign off ship");
                if (hasCompany && hasVessel && hasRank && hasDates) {
                    headerRow = r;
                    headers = candidate;
                    break;
                }
            }

            if (headerRow < 0) continue;

            int companyCol = dgHeaderIndex(headers,
                    "rpsl company name", "rpsl name", "company name");
            int rankCol = dgHeaderIndex(headers, "rank");
            int vesselCol = dgHeaderIndex(headers, "vessel name", "ship name");
            int flagCol = dgHeaderIndex(headers, "flag");
            int contractFromCol = dgHeaderIndex(headers,
                    "date of commencement of contract", "commencement of contract");
            int signOnDateCol = dgHeaderIndex(headers,
                    "date of sign on ship", "sign on ship");
            int signOnPortCol = dgHeaderIndex(headers, "sign on port");
            int signOnAckCol = dgHeaderIndex(headers, "acknowledge sign on", "acknowledgement sign on");
            int signOffDateCol = dgHeaderIndex(headers,
                    "date of sign off ship", "sign off ship");
            int signOffPortCol = dgHeaderIndex(headers, "sign off port");
            int contractToCol = dgHeaderIndex(headers,
                    "date of completion of contract arriving india",
                    "date of completion of contract",
                    "completion of contract arriving india");
            int signOffAckCol = dgHeaderIndex(headers,
                    "acknowledge sign off", "acknowledgement sign off");

            for (int r = headerRow + 1; r < rows.size(); r++) {
                List<WebElement> cells = rows.get(r).findElements(By.cssSelector("td"));
                if (cells.isEmpty()) continue;

                String company = dgCell(cells, companyCol);
                String rank = dgCell(cells, rankCol);
                String vessel = dgCell(cells, vesselCol);
                String flag = dgCell(cells, flagCol);

                if (blank(company) && blank(rank) && blank(vessel)) continue;
                String rowText = cleanNull(rows.get(r).getText()).toLowerCase(Locale.ROOT);
                if (rowText.contains("details not found")) continue;

                // Some DG tables repeat their headings as a TD row below the real TH
                // row. Do not save that pseudo-row as a voyage.
                String companyNorm = normalizeDgHeader(company);
                String rankNorm = normalizeDgHeader(rank);
                String vesselNorm = normalizeDgHeader(vessel);
                if ((companyNorm.equals("rpsl company name") || companyNorm.equals("company name") || companyNorm.equals("rank"))
                        && (rankNorm.equals("rank") || rankNorm.equals("vessel name") || rankNorm.equals("flag"))
                        && (vesselNorm.equals("vessel name") || vesselNorm.equals("flag") || vesselNorm.equals("rank"))) {
                    continue;
                }

                String contractFrom = displayDgDate(dgCell(cells, contractFromCol));
                String signOnDate = displayDgDate(dgCell(cells, signOnDateCol));
                String signOnPort = dgCell(cells, signOnPortCol);
                String signOnAck = dgCell(cells, signOnAckCol);
                String signOffDate = displayDgDate(dgCell(cells, signOffDateCol));
                String signOffPort = dgCell(cells, signOffPortCol);
                String contractTo = displayDgDate(dgCell(cells, contractToCol));
                String signOffAck = dgCell(cells, signOffAckCol);

                String serviceFrom = firstNonBlankText(signOnDate, contractFrom);
                String serviceTo = firstNonBlankText(signOffDate, contractTo);
                String remarks = dgSeaServiceRemarks(
                        contractFrom, contractTo,
                        signOnPort, signOnAck,
                        signOffPort, signOffAck,
                        serviceFrom, serviceTo);

                ResumeEntryData.VesselInfo info = new ResumeEntryData.VesselInfo(
                        company,
                        vessel,
                        "",          // Official No - enriched from printable profile
                        "",          // IMO No - enriched from printable profile
                        flag,
                        "",          // Port of Registry - enriched from printable profile
                        "",          // Ship Type
                        "",          // GT
                        "",          // Trade Area
                        rank,
                        "",          // Nature of Watch
                        serviceFrom,
                        serviceTo,
                        "",          // Article Months
                        "",          // Article Days
                        "",          // Propulsion Power (KW)
                        "",          // Propulsion Type
                        "",          // Propelling Days
                        remarks
                );

                String key = dgSeaServiceKey(info);
                if (!blank(key)) unique.putIfAbsent(key, info);
            }
        }

        return new ArrayList<>(unique.values());
    }

    private int dgHeaderIndex(List<String> headers, String... aliases) {
        if (headers == null || headers.isEmpty()) return -1;
        List<String> wanted = new ArrayList<>();
        for (String alias : aliases) wanted.add(normalizeDgHeader(alias));

        for (int i = 0; i < headers.size(); i++) {
            String h = normalizeDgHeader(headers.get(i));
            for (String w : wanted) {
                if (h.equals(w)) return i;
            }
        }
        for (int i = 0; i < headers.size(); i++) {
            String h = normalizeDgHeader(headers.get(i));
            for (String w : wanted) {
                if (!w.isBlank() && (h.contains(w) || w.contains(h))) return i;
            }
        }
        return -1;
    }

    private String dgCell(List<WebElement> cells, int index) {
        if (index < 0 || cells == null || index >= cells.size()) return "";
        try {
            return cleanNull(cells.get(index).getText());
        } catch (Exception ignored) {
            return "";
        }
    }

    private String normalizeDgHeader(String value) {
        return nvl(value).toLowerCase(Locale.ROOT)
                .replace('&', ' ')
                .replaceAll("[^a-z0-9]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private String dgSeaServiceRemarks(
            String contractFrom,
            String contractTo,
            String signOnPort,
            String signOnAck,
            String signOffPort,
            String signOffAck,
            String serviceFrom,
            String serviceTo
    ) {
        List<String> bits = new ArrayList<>();
        if (!blank(contractFrom) && !contractFrom.equals(serviceFrom)) {
            bits.add("Contract From: " + contractFrom);
        }
        if (!blank(contractTo) && !contractTo.equals(serviceTo)) {
            bits.add("Contract To: " + contractTo);
        }
        if (!blank(signOnPort) && !signOnPort.equalsIgnoreCase("N.A.")) {
            bits.add("Sign On Port: " + signOnPort);
        }
        if (!blank(signOffPort) && !signOffPort.equalsIgnoreCase("N.A.")) {
            bits.add("Sign Off Port: " + signOffPort);
        }
        if (!blank(signOnAck)) bits.add("Sign On Ack: " + signOnAck);
        if (!blank(signOffAck)) bits.add("Sign Off Ack: " + signOffAck);
        return String.join(" | ", bits);
    }

    private String dgSeaServiceKey(ResumeEntryData.VesselInfo v) {
        if (v == null || v.isEmpty()) return "";
        return String.join("|",
                        nvl(v.companyName()), nvl(v.vesselName()), nvl(v.rank()),
                        nvl(v.serviceFrom()), nvl(v.serviceTo()))
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9|]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private void restoreDgHomeAfterReadOnlyPage(
            WebDriver driver,
            String originalWindow,
            String originalUrl
    ) {
        if (driver == null) return;
        try {
            String currentWindow = driver.getWindowHandle();
            if (!blank(originalWindow) && !currentWindow.equals(originalWindow)) {
                try { driver.close(); } catch (Exception ignored) {}
                driver.switchTo().window(originalWindow);
                return;
            }

            if (blank(originalUrl) || nvl(driver.getCurrentUrl()).equals(originalUrl)) return;

            WebElement home = firstVisible(driver,
                    By.xpath("//a[normalize-space()='Home']"),
                    By.xpath("//*[self::a or self::button][contains(translate(normalize-space(.),'ABCDEFGHIJKLMNOPQRSTUVWXYZ','abcdefghijklmnopqrstuvwxyz'),'home')]"));
            if (home != null) {
                try {
                    click(driver, home);
                    new WebDriverWait(driver, Duration.ofSeconds(8)).until(d ->
                            pageContainsAny(d,
                                    "Update Seafarer Profile",
                                    "View Sea Service and Acknowledge",
                                    "Internal Reference Links"));
                    return;
                } catch (Exception ignored) {}
            }

            driver.navigate().back();
            Thread.sleep(700);
        } catch (Exception ignored) {}
    }

    private List<ResumeEntryData.CourseInfo> readDgElearningCourses(
            WebDriver driver,
            WebDriverWait wait
    ) {
        List<ResumeEntryData.CourseInfo> out = new ArrayList<>();
        String originalUrl = "";
        String originalWindow = "";
        Set<String> beforeHandles = new LinkedHashSet<>();
        try {
            originalUrl = nvl(driver.getCurrentUrl());
            originalWindow = driver.getWindowHandle();
            beforeHandles.addAll(driver.getWindowHandles());

            WebElement link = firstVisible(driver,
                    By.xpath("//*[self::a or self::button][contains(translate(normalize-space(.),'ABCDEFGHIJKLMNOPQRSTUVWXYZ','abcdefghijklmnopqrstuvwxyz'),'elearning details')]"),
                    By.xpath("//*[self::a or self::button][contains(translate(normalize-space(.),'ABCDEFGHIJKLMNOPQRSTUVWXYZ','abcdefghijklmnopqrstuvwxyz'),'e-learning details')]"),
                    By.xpath("//*[contains(translate(normalize-space(.),'ABCDEFGHIJKLMNOPQRSTUVWXYZ','abcdefghijklmnopqrstuvwxyz'),'elearning details')]/ancestor::a[1]"),
                    By.xpath("//*[contains(translate(normalize-space(.),'ABCDEFGHIJKLMNOPQRSTUVWXYZ','abcdefghijklmnopqrstuvwxyz'),'e-learning details')]/ancestor::a[1]"),
                    By.cssSelector("a[href*='eLearning'],a[href*='elearning'],a[href*='ELearning']")
            );
            if (link == null) {
                System.out.println("DG ELEARNING | Link not found - continuing with printable profile only");
                return out;
            }

            click(driver, link);

            // Lambda-captured values must be final/effectively final.
            final String elearningStartUrl = originalUrl;
            final int elearningStartWindowCount = beforeHandles.size();

            // Some DG pages open this section in another window/tab.
            try {
                new WebDriverWait(driver, Duration.ofSeconds(5)).until(d ->
                        d.getWindowHandles().size() > elearningStartWindowCount
                                || !nvl(d.getCurrentUrl()).equals(elearningStartUrl));
                for (String handle : driver.getWindowHandles()) {
                    if (!beforeHandles.contains(handle)) {
                        driver.switchTo().window(handle);
                        break;
                    }
                }
            } catch (Exception ignored) {}

            wait.until(d -> {
                try {
                    String body = d.findElement(By.tagName("body")).getText().toLowerCase(Locale.ROOT);
                    return body.contains("e-learning details")
                            || body.contains("elearning details")
                            || !d.findElements(By.xpath("//table//tr[td]")).isEmpty();
                } catch (Exception e) {
                    return false;
                }
            });

            List<WebElement> rows = driver.findElements(By.xpath("//table//tr[td]"));
            for (WebElement row : rows) {
                try {
                    List<WebElement> cells = row.findElements(By.cssSelector("td"));
                    if (cells.size() < 3) continue;

                    int offset = 0;
                    String first = cleanNull(cells.get(0).getText());
                    if (first.matches("\\d+")) offset = 1;
                    if (cells.size() - offset < 3) continue;

                    String name = cleanNull(cells.get(offset).getText());
                    String startDate = cleanNull(cells.get(offset + 1).getText());
                    String endDate = cleanNull(cells.get(offset + 2).getText());
                    if (blank(name) || name.toLowerCase(Locale.ROOT).contains("name of course")) continue;

                    out.add(new ResumeEntryData.CourseInfo(
                            name,
                            "",
                            "",
                            "",
                            "",
                            "",
                            "",
                            "",
                            "",
                            "",
                            displayDgDate(startDate),
                            displayDgDate(endDate),
                            "eLearning",
                            ""
                    ));
                } catch (Exception ignored) {}
            }

            System.out.println("DG ELEARNING | Courses read: " + out.size());
        } catch (Exception e) {
            System.out.println("DG ELEARNING | Could not read eLearning Details - "
                    + safeMessage(e) + " - continuing with printable profile");
        } finally {
            restoreDgHomeAfterReadOnlyPage(driver, originalWindow, originalUrl);
        }
        return out;
    }

    /**
     * Membership Lifetime only - official DG Shipping STCW Course checker.
     *
     * Confirmed controls from the live page:
     *   select[name='cmbSearch_by'] -> value CRSE (STCW Course)
     *   input[name='txtNo']          -> INDoS No
     *   input[name='txtDob']         -> DOB (readonly; populated by JS)
     *   input#driver[name='btnNext'][value='Search'] -> Search
     *
     * Every result row is retained. Repeated/refresher courses with different
     * certificate numbers remain separate rows in the Course Details sheet.
     */
    private List<ResumeEntryData.CourseInfo> readDgStcwCourses(
            WebDriver driver,
            WebDriverWait wait,
            String indos,
            String dob
    ) {
        List<ResumeEntryData.CourseInfo> out = new ArrayList<>();
        if (!cfg.isStcwCheckerEnabled() || blank(indos) || blank(dob)) {
            if (cfg.isStcwCheckerEnabled() && blank(dob)) {
                System.out.println("DG STCW CHECKER | DOB missing - checker skipped");
            }
            return out;
        }

        String originalUrl = nvl(driver.getCurrentUrl());
        String checkerDob = formatDobForDgChecker(dob);
        if (blank(checkerDob)) {
            System.out.println("DG STCW CHECKER | DOB format not usable: " + cleanNull(dob));
            return out;
        }

        try {
            driver.get(cfg.getStcwCheckerUrl());

            Select searchType = new Select(wait.until(
                    ExpectedConditions.presenceOfElementLocated(By.name("cmbSearch_by"))));
            searchType.selectByValue("CRSE");

            WebElement indosInput = wait.until(
                    ExpectedConditions.visibilityOfElementLocated(By.name("txtNo")));
            setInputValue(driver, indosInput, indos.trim());

            WebElement dobInput = wait.until(
                    ExpectedConditions.presenceOfElementLocated(By.name("txtDob")));
            ((JavascriptExecutor) driver).executeScript(
                    "var el=arguments[0],v=arguments[1];" +
                            "el.removeAttribute('readonly');" +
                            "var p=Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value');" +
                            "if(p&&p.set){p.set.call(el,v);}else{el.value=v;}" +
                            "['input','change','blur'].forEach(function(n){el.dispatchEvent(new Event(n,{bubbles:true}));});",
                    dobInput, checkerDob);

            WebElement searchButton = wait.until(ExpectedConditions.elementToBeClickable(
                    By.cssSelector("input#driver[name='btnNext'][value='Search']")));
            try {
                searchButton.click();
            } catch (Exception clickError) {
                ((JavascriptExecutor) driver).executeScript("arguments[0].click();", searchButton);
            }

            // Result page normally changes to StcwCourse.jsp. Wait for either the
            // Course Details table or a genuine no-record response.
            WebElement resultTable = null;
            Instant resultDeadline = Instant.now().plusSeconds(Math.max(25, cfg.getDgWaitSeconds()));
            while (Instant.now().isBefore(resultDeadline)) {
                resultTable = findDgStcwResultTable(driver);
                if (resultTable != null) break;

                String body = safeBodyText(driver).toLowerCase(Locale.ROOT);
                if (body.contains("no record")
                        || body.contains("record not found")
                        || body.contains("no data found")) {
                    System.out.println("DG STCW CHECKER | No course record found | INDoS: " + maskIndos(indos));
                    return out;
                }
                sleep(300);
            }

            if (resultTable == null) {
                System.out.println("DG STCW CHECKER | Course Details table not found before timeout");
                return out;
            }

            out.addAll(parseDgStcwResultTable(resultTable, driver.getCurrentUrl()));
            System.out.println("DG STCW CHECKER | Courses read: " + out.size()
                    + " | INDoS: " + maskIndos(indos));

        } catch (Exception e) {
            System.out.println("DG STCW CHECKER | Could not read STCW Course details - "
                    + safeMessage(e) + " - continuing with DG profile");
        } finally {
            // Restore the logged-in DG page so the remaining profile workflow can
            // continue in the same DG browser/session.
            if (!blank(originalUrl)) {
                try {
                    driver.get(originalUrl);
                    sleep(500);
                } catch (Exception ignored) {}
            }
        }

        return out;
    }

    private WebElement findDgStcwResultTable(WebDriver driver) {
        try {
            WebElement best = null;
            int bestLength = Integer.MAX_VALUE;
            for (WebElement table : driver.findElements(By.tagName("table"))) {
                String text = cleanNull(table.getText()).toLowerCase(Locale.ROOT);
                if (text.contains("institute name")
                        && text.contains("course name")
                        && text.contains("certificate no")
                        && text.contains("issue date")) {
                    // Old DG pages use nested layout tables. The smallest matching
                    // table is normally the actual Course Details grid.
                    if (text.length() < bestLength) {
                        best = table;
                        bestLength = text.length();
                    }
                }
            }
            return best;
        } catch (Exception ignored) {}
        return null;
    }

    private List<ResumeEntryData.CourseInfo> parseDgStcwResultTable(
            WebElement table,
            String pageUrl
    ) {
        List<ResumeEntryData.CourseInfo> out = new ArrayList<>();
        List<WebElement> rows = table.findElements(By.cssSelector("tr"));
        if (rows.isEmpty()) return out;

        int headerRow = -1;
        Map<String, Integer> headers = new LinkedHashMap<>();

        for (int r = 0; r < rows.size(); r++) {
            List<WebElement> cells = rows.get(r).findElements(By.cssSelector("th,td"));
            if (cells.isEmpty()) continue;

            Map<String, Integer> candidate = new LinkedHashMap<>();
            for (int c = 0; c < cells.size(); c++) {
                String key = normalizeStcwHeader(cells.get(c).getText());
                if (!blank(key)) candidate.putIfAbsent(key, c);
            }

            if (candidate.keySet().stream().anyMatch(k -> k.contains("institutename"))
                    && candidate.keySet().stream().anyMatch(k -> k.contains("coursename"))
                    && candidate.keySet().stream().anyMatch(k -> k.contains("certificateno"))) {
                headerRow = r;
                headers = candidate;
                break;
            }
        }

        if (headerRow < 0) return out;

        int instituteIx = stcwHeaderIndex(headers, "institutename");
        int courseIx = stcwHeaderIndex(headers, "coursename");
        int startIx = stcwHeaderIndex(headers, "startdate");
        int endIx = stcwHeaderIndex(headers, "enddate");
        int certIx = stcwHeaderIndex(headers, "certificateno", "certificatenumber");
        int issueIx = stcwHeaderIndex(headers, "issuedate", "dateofissue");
        int expiryIx = stcwHeaderIndex(headers, "expirydate", "dateofexpiry", "validupto");
        int digitalIx = stcwHeaderIndex(headers, "digitalcertificate", "certificateview");

        for (int r = headerRow + 1; r < rows.size(); r++) {
            List<WebElement> cells = rows.get(r).findElements(By.cssSelector("td"));
            if (cells.isEmpty()) continue;

            String institute = stcwCellText(cells, instituteIx);
            String course = stcwCellText(cells, courseIx);
            String start = displayDgDate(stcwCellText(cells, startIx));
            String end = displayDgDate(stcwCellText(cells, endIx));
            String cert = stcwCellText(cells, certIx);
            String issue = displayDgDate(stcwCellText(cells, issueIx));
            String expiry = displayDgDate(stcwCellText(cells, expiryIx));
            String digitalLink = stcwDigitalCertificateLink(cells, digitalIx, pageUrl);

            // Ignore decorative/empty rows and repeated header rows.
            if (blank(course) && blank(cert) && blank(institute)) continue;
            if (course.equalsIgnoreCase("Course Name")) continue;

            out.add(new ResumeEntryData.CourseInfo(
                    course,
                    cert,
                    issue,
                    expiry,
                    "",
                    "",
                    "",
                    institute,
                    start,
                    end,
                    "",
                    "",
                    "DG STCW Checker",
                    digitalLink
            ));
        }

        return out;
    }

    private String normalizeStcwHeader(String value) {
        return cleanNull(value).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "");
    }

    private int stcwHeaderIndex(Map<String, Integer> headers, String... wanted) {
        for (String key : wanted) {
            String normalizedWanted = normalizeStcwHeader(key);
            for (Map.Entry<String, Integer> entry : headers.entrySet()) {
                if (entry.getKey().equals(normalizedWanted)
                        || entry.getKey().contains(normalizedWanted)
                        || normalizedWanted.contains(entry.getKey())) {
                    return entry.getValue();
                }
            }
        }
        return -1;
    }

    private String stcwCellText(List<WebElement> cells, int index) {
        if (index < 0 || index >= cells.size()) return "";
        try { return cleanNull(cells.get(index).getText()); }
        catch (Exception e) { return ""; }
    }

    private String stcwDigitalCertificateLink(List<WebElement> cells, int index, String pageUrl) {
        if (index < 0 || index >= cells.size()) return "";
        try {
            WebElement cell = cells.get(index);
            List<WebElement> links = cell.findElements(By.cssSelector("a"));
            for (WebElement link : links) {
                String href = cleanNull(link.getAttribute("href"));
                if (!blank(href) && !href.toLowerCase(Locale.ROOT).startsWith("javascript:")) {
                    try {
                        return new URI(pageUrl).resolve(href).toString();
                    } catch (Exception ignored) {
                        return href;
                    }
                }

                // Older DG pages occasionally put the target in onclick instead
                // of href. Keep only URL/path-looking quoted values.
                String onclick = cleanNull(link.getAttribute("onclick"));
                Matcher m = Pattern.compile("['\"]([^'\"]+(?:\\.jsp|\\.do|\\.pdf|certificate|Certificate)[^'\"]*)['\"]")
                        .matcher(onclick);
                if (m.find()) {
                    String target = cleanNull(m.group(1));
                    try {
                        return new URI(pageUrl).resolve(target).toString();
                    } catch (Exception ignored) {
                        return target;
                    }
                }
            }
        } catch (Exception ignored) {}
        return "";
    }

    private String formatDobForDgChecker(String input) {
        String raw = cleanNull(input);
        if (blank(raw)) return "";

        String iso = CustomerProfilePdfParser.isoDate(raw);
        if (!blank(iso)) {
            try {
                return java.time.LocalDate.parse(iso)
                        .format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/uuuu"));
            } catch (Exception ignored) {}
        }

        // Already in the expected DG checker form.
        if (raw.matches("\\d{1,2}/\\d{1,2}/\\d{4}")) {
            String[] p = raw.split("/");
            try {
                return java.time.LocalDate.of(
                                Integer.parseInt(p[2]),
                                Integer.parseInt(p[1]),
                                Integer.parseInt(p[0]))
                        .format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/uuuu"));
            } catch (Exception ignored) {}
        }
        return "";
    }

    private String displayDgDate(String input) {
        String iso = CustomerProfilePdfParser.isoDate(cleanNull(input));
        if (blank(iso)) return cleanNull(input);
        try {
            return java.time.LocalDate.parse(iso)
                    .format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/uuuu"));
        } catch (Exception e) {
            return cleanNull(input);
        }
    }

    private CustomerProfileData readDgPageData(WebDriver driver) {
        try {
            String body = driver.findElement(By.tagName("body")).getText();
            return pdfParser.parseText(body);
        } catch (Exception e) {
            return new CustomerProfileData("", "", "", "", "", "",
                    CustomerProfileData.DocumentInfo.empty(),
                    CustomerProfileData.DocumentInfo.empty(),
                    CustomerProfileData.DocumentInfo.empty(),
                    CustomerProfileData.DocumentInfo.empty(),
                    "", "", "", "",
                    "", "", "", "", "", "", "");
        }
    }

    private CustomerProfileData.DocumentInfo chooseDocument(
            CustomerProfileData.DocumentInfo primary,
            CustomerProfileData.DocumentInfo fallback) {
        CustomerProfileData.DocumentInfo a = primary == null
                ? CustomerProfileData.DocumentInfo.empty()
                : primary;
        CustomerProfileData.DocumentInfo b = fallback == null
                ? CustomerProfileData.DocumentInfo.empty()
                : fallback;

        return new CustomerProfileData.DocumentInfo(
                firstNonBlankText(a.number(), b.number()),
                firstNonBlankText(a.issuedDate(), b.issuedDate()),
                firstNonBlankText(a.expiryDate(), b.expiryDate())
        );
    }

    private String firstNonBlankText(String... values) {
        for (String value : values) {
            if (!blank(value)) return value.trim();
        }
        return "";
    }

    private String readDgDob(WebDriver driver) {
        String value = firstInputValue(driver,
                By.xpath("//input[contains(translate(@name,'ABCDEFGHIJKLMNOPQRSTUVWXYZ','abcdefghijklmnopqrstuvwxyz'),'dateofbirth')]"),
                By.xpath("//input[contains(translate(@name,'ABCDEFGHIJKLMNOPQRSTUVWXYZ','abcdefghijklmnopqrstuvwxyz'),'birthdate')]"),
                By.xpath("//input[contains(translate(@name,'ABCDEFGHIJKLMNOPQRSTUVWXYZ','abcdefghijklmnopqrstuvwxyz'),'dob')]"),
                By.xpath("//input[contains(translate(@id,'ABCDEFGHIJKLMNOPQRSTUVWXYZ','abcdefghijklmnopqrstuvwxyz'),'dateofbirth')]"),
                By.xpath("//input[contains(translate(@id,'ABCDEFGHIJKLMNOPQRSTUVWXYZ','abcdefghijklmnopqrstuvwxyz'),'birthdate')]"),
                By.xpath("//input[contains(translate(@id,'ABCDEFGHIJKLMNOPQRSTUVWXYZ','abcdefghijklmnopqrstuvwxyz'),'dob')]")
        );
        if (!blank(value)) return value;
        return textNearLabel(driver, "Date of Birth", "Date Of Birth", "DOB", "Birth Date");
    }

    private String readDgFatherName(WebDriver driver) {
        String value = firstInputValue(driver,
                By.xpath("//input[contains(translate(@name,'ABCDEFGHIJKLMNOPQRSTUVWXYZ','abcdefghijklmnopqrstuvwxyz'),'father')]"),
                By.xpath("//input[contains(translate(@id,'ABCDEFGHIJKLMNOPQRSTUVWXYZ','abcdefghijklmnopqrstuvwxyz'),'father')]")
        );
        if (!blank(value)) return value;
        return textNearLabel(driver,
                "Father Name", "Father's Name", "Name of Father",
                "Father / Guardian Name", "Father/Guardian Name", "Parent Name");
    }

    private String firstInputValue(WebDriver driver, By... locators) {
        for (By by : locators) {
            try {
                for (WebElement e : driver.findElements(by)) {
                    if (!displayed(e)) continue;
                    String value = cleanNull(nvl(e.getAttribute("value")));
                    if (!blank(value)) return value;
                }
            } catch (Exception ignored) {}
        }
        return "";
    }

    private void openDgLoginPage(WebDriver driver, Config cfg) {
        driver.get(cfg.getDgLoginUrl());
        // The configured URL supplied earlier was logOut.do?method=loadIndexPage. If it doesn't render
        // a login form, use the long-standing direct e-Governance login route.
        sleep(700);
        if (firstVisible(driver, By.cssSelector("input[type='password']")) == null
                && cfg.getDgLoginUrl().contains("/esamudraUI/")) {
            String base = cfg.getDgLoginUrl().substring(0, cfg.getDgLoginUrl().indexOf("/esamudraUI/") + "/esamudraUI".length());
            driver.get(base + "/well.do?method=loadPage");
        }
    }

    private void loginDg(WebDriver driver, WebDriverWait wait, String indos, String password) {
        wait.until(d -> firstVisible(d,
                By.cssSelector("input[name='userId']"),
                By.cssSelector("input[name='userid']"),
                By.cssSelector("input[name='userName']"),
                By.cssSelector("input[name='username']"),
                By.cssSelector("input[name='indosNo']"),
                By.xpath("//input[contains(translate(@id,'ABCDEFGHIJKLMNOPQRSTUVWXYZ','abcdefghijklmnopqrstuvwxyz'),'user')]")
        ) != null && firstVisible(d, By.cssSelector("input[type='password']")) != null);

        WebElement user = firstVisible(driver,
                By.cssSelector("input[name='userId']"),
                By.cssSelector("input[name='userid']"),
                By.cssSelector("input[name='userName']"),
                By.cssSelector("input[name='username']"),
                By.cssSelector("input[name='indosNo']"),
                By.xpath("//input[contains(translate(@id,'ABCDEFGHIJKLMNOPQRSTUVWXYZ','abcdefghijklmnopqrstuvwxyz'),'user')]")
        );
        WebElement pass = firstVisible(driver,
                By.cssSelector("input[name='password']"),
                By.cssSelector("input[type='password']")
        );
        if (user == null || pass == null) throw new IllegalStateException("DG login fields not found");

        // The DG login page is an older server-rendered form. Real keyboard input is
        // more reliable here than a JS-only value assignment because the form can read
        // the control state at submit time.
        user.click();
        user.sendKeys(Keys.chord(Keys.CONTROL, "a"));
        user.sendKeys(indos == null ? "" : indos.trim());
        pass.click();
        pass.sendKeys(Keys.chord(Keys.CONTROL, "a"));
        pass.sendKeys(password == null ? "" : password); // do NOT trim/change the stored password

        System.out.println("DG LOGIN | INDoS: " + maskIndos(indos)
                + " | Password chars: " + (password == null ? 0 : password.length()));

        WebElement login = firstVisible(driver,
                By.xpath("//input[@type='submit' and (contains(translate(@value,'ABCDEFGHIJKLMNOPQRSTUVWXYZ','abcdefghijklmnopqrstuvwxyz'),'login') or contains(translate(@value,'ABCDEFGHIJKLMNOPQRSTUVWXYZ','abcdefghijklmnopqrstuvwxyz'),'submit'))]"),
                By.xpath("//button[@type='submit']"),
                By.xpath("//*[self::button or self::input][contains(translate(normalize-space(@value),'ABCDEFGHIJKLMNOPQRSTUVWXYZ','abcdefghijklmnopqrstuvwxyz'),'login') or contains(translate(normalize-space(.),'ABCDEFGHIJKLMNOPQRSTUVWXYZ','abcdefghijklmnopqrstuvwxyz'),'login')]")
        );
        if (login == null) throw new IllegalStateException("DG Login button not found");
        click(driver, login);

        try {
            new WebDriverWait(driver, Duration.ofSeconds(20)).until(d ->
                    isDgLoginSuccess(d) || isWrongPasswordScreen(d));
        } catch (TimeoutException timeout) {
            if (isWrongPasswordScreen(driver)) {
                throw new WrongIndosPasswordException(
                        "DG explicitly rejected the INDoS/password for " + maskIndos(indos));
            }
            throw new IllegalStateException(
                    "DG login did not complete. Login form is still open or DG did not respond; password is NOT classified as wrong.",
                    timeout);
        }

        if (isWrongPasswordScreen(driver)) {
            throw new WrongIndosPasswordException(
                    "DG explicitly rejected the INDoS/password for " + maskIndos(indos));
        }

        if (!isDgLoginSuccess(driver)) {
            throw new IllegalStateException(
                    "DG login result could not be confirmed; password is NOT classified as wrong.");
        }

        System.out.println("DG LOGIN SUCCESS | INDoS: " + maskIndos(indos)
                + " | Opening DG profile from the authenticated DG Shipping home page");
    }

    private boolean isDgLoginSuccess(WebDriver driver) {
        try {
            // This is the authenticated DG Shipping home page shown after login.
            if (pageContainsAny(driver,
                    "Update Seafarer Profile",
                    "View Sea Service and Acknowledge",
                    "SMO-Other Activities",
                    "Logout")) {
                return true;
            }

            // A successful login can briefly land on an intermediate Back to Home page.
            if (pageContainsAny(driver, "Back to Home")) return true;

            // If the login form has disappeared and we are still within eSamudra,
            // treat it as authenticated/intermediate rather than calling the password wrong.
            boolean userStillVisible = firstVisible(driver,
                    By.cssSelector("input[name='userId']"),
                    By.cssSelector("input[name='userid']"),
                    By.cssSelector("input[name='userName']"),
                    By.cssSelector("input[name='username']"),
                    By.xpath("//input[contains(translate(@id,'ABCDEFGHIJKLMNOPQRSTUVWXYZ','abcdefghijklmnopqrstuvwxyz'),'user')]")
            ) != null;
            boolean passStillVisible = firstVisible(driver, By.cssSelector("input[type='password']")) != null;
            return !(userStillVisible && passStillVisible)
                    && nvl(driver.getCurrentUrl()).toLowerCase(Locale.ROOT).contains("esamudraui");
        } catch (Exception e) {
            return false;
        }
    }
    private boolean isWrongPasswordScreen(WebDriver driver) {
        try {
            String body = nvl(
                    driver.findElement(By.tagName("body")).getText()
            ).toLowerCase(Locale.ROOT);

            // Only call it WRONG PASSWORD when DG explicitly says
            // the userid/password/credentials are incorrect.
            return body.contains("invalid user id/password")
                    || body.contains("invalid userid/password")
                    || body.contains("invalid user/password")
                    || body.contains("incorrect user id/password")
                    || body.contains("incorrect userid/password")
                    || body.contains("wrong password")
                    || body.contains("password does not match")
                    || body.contains("invalid credentials")
                    || body.contains("authentication failed");

        } catch (Exception e) {
            return false;
        }
    }
    /**
     * DG Shipping can land on an intermediate page after a successful login.
     * That page shows a "Back to Home" button and does not contain the
     * Update Seafarer Profile menu. If it appears, click it before continuing.
     */
    private void handleDgBackToHome(WebDriver driver, WebDriverWait wait) {
        try {
            WebElement backToHome = firstVisible(driver,
                    By.xpath("//input[contains(translate(@value,'ABCDEFGHIJKLMNOPQRSTUVWXYZ','abcdefghijklmnopqrstuvwxyz'),'back to home')]"),
                    By.xpath("//button[contains(translate(normalize-space(.),'ABCDEFGHIJKLMNOPQRSTUVWXYZ','abcdefghijklmnopqrstuvwxyz'),'back to home')]"),
                    By.xpath("//a[contains(translate(normalize-space(.),'ABCDEFGHIJKLMNOPQRSTUVWXYZ','abcdefghijklmnopqrstuvwxyz'),'back to home')]")
            );

            if (backToHome == null) {
                System.out.println("DG: Back to Home page not shown - continuing");
                return;
            }

            System.out.println("DG: Intermediate page detected - clicking Back to Home");
            String oldUrl = driver.getCurrentUrl();

            try {
                ((JavascriptExecutor) driver).executeScript(
                        "arguments[0].scrollIntoView({block:'center'});", backToHome);
            } catch (Exception ignored) {}

            click(driver, backToHome);

            try {
                wait.until(d ->
                        !Objects.equals(d.getCurrentUrl(), oldUrl)
                                || pageContainsAny(d,
                                "Update Seafarer Profile",
                                "Update Seafarers Profile",
                                "Home",
                                "Logout"));
            } catch (TimeoutException ignored) {
                // The old DG site can navigate without changing much visible text.
                // openUpdateSeafarerProfile() below will perform the definitive check.
            }

            System.out.println("DG: Back to Home completed");
        } catch (Exception e) {
            System.out.println("DG: Back to Home handling skipped: " + safeMessage(e));
        }
    }

    private void openUpdateSeafarerProfile(WebDriver driver, WebDriverWait wait) {
        // Old DG pages sometimes leave the browser on a Sea Service/eLearning
        // child page. Recover to Home before waiting on the Update Profile link.
        WebElement update = findUpdateSeafarerProfileLink(driver);
        if (update == null) {
            recoverDgHomeForUpdate(driver);
            update = findUpdateSeafarerProfileLink(driver);
        }

        if (update == null) {
            try {
                update = new WebDriverWait(driver, Duration.ofSeconds(20)).until(
                        this::findUpdateSeafarerProfileLink);
            } catch (Exception ignored) {}
        }

        if (update == null) {
            throw new IllegalStateException(
                    "DG Update Seafarer Profile link not found after Home recovery");
        }

        click(driver, update);
        wait.until(d -> pageContainsAny(d,
                "Click to View and Print Your Profile", "Seafarer Details",
                "Educational Details", "Professional Training Details", "Documents"));
    }

    private WebElement findUpdateSeafarerProfileLink(WebDriver driver) {
        return firstVisible(driver,
                By.xpath("//a[contains(translate(normalize-space(.),'ABCDEFGHIJKLMNOPQRSTUVWXYZ','abcdefghijklmnopqrstuvwxyz'),'update seafarer profile')]"),
                By.xpath("//a[contains(translate(normalize-space(.),'ABCDEFGHIJKLMNOPQRSTUVWXYZ','abcdefghijklmnopqrstuvwxyz'),'update seafarers profile')]"),
                By.xpath("//*[self::a or self::button][contains(translate(normalize-space(.),'ABCDEFGHIJKLMNOPQRSTUVWXYZ','abcdefghijklmnopqrstuvwxyz'),'update seafarer')]")
        );
    }

    private void recoverDgHomeForUpdate(WebDriver driver) {
        for (int attempt = 0; attempt < 3; attempt++) {
            if (findUpdateSeafarerProfileLink(driver) != null) return;

            WebElement backHome = firstVisible(driver,
                    By.xpath("//input[contains(translate(@value,'ABCDEFGHIJKLMNOPQRSTUVWXYZ','abcdefghijklmnopqrstuvwxyz'),'back to home')]"),
                    By.xpath("//*[self::a or self::button][contains(translate(normalize-space(.),'ABCDEFGHIJKLMNOPQRSTUVWXYZ','abcdefghijklmnopqrstuvwxyz'),'back to home')]")
            );
            if (backHome != null) {
                try { click(driver, backHome); sleep(900); continue; } catch (Exception ignored) {}
            }

            WebElement home = firstVisible(driver,
                    By.xpath("//a[normalize-space()='Home']"),
                    By.xpath("//*[self::a or self::button][contains(translate(normalize-space(.),'ABCDEFGHIJKLMNOPQRSTUVWXYZ','abcdefghijklmnopqrstuvwxyz'),'home')]")
            );
            if (home != null) {
                try { click(driver, home); sleep(900); continue; } catch (Exception ignored) {}
            }

            try {
                driver.navigate().back();
                sleep(900);
            } catch (Exception ignored) {}
        }

        if (findUpdateSeafarerProfileLink(driver) != null) return;

        // Last recovery uses the authenticated e-Samudra home route in the same
        // browser session. It does not re-enter credentials or bypass login.
        try {
            String url = nvl(driver.getCurrentUrl());
            int marker = url.indexOf("/esamudraUI/");
            if (marker >= 0) {
                String base = url.substring(0, marker + "/esamudraUI".length());
                driver.get(base + "/well.do?method=loadPage");
                sleep(1200);
            }
        } catch (Exception ignored) {}
    }

    private Path openAndSavePrintableProfile(WebDriver driver, WebDriverWait wait,
                                             String customerId, String indos) throws Exception {
        WebElement printProfile = wait.until(d -> firstVisible(d,
                By.xpath("//*[self::a or self::button][contains(translate(normalize-space(.),'ABCDEFGHIJKLMNOPQRSTUVWXYZ','abcdefghijklmnopqrstuvwxyz'),'click to view and print your profile')]"),
                By.xpath("//*[self::a or self::button][contains(translate(normalize-space(.),'ABCDEFGHIJKLMNOPQRSTUVWXYZ','abcdefghijklmnopqrstuvwxyz'),'view and print your profile')]"),
                By.xpath("//*[self::a or self::button][contains(translate(normalize-space(.),'ABCDEFGHIJKLMNOPQRSTUVWXYZ','abcdefghijklmnopqrstuvwxyz'),'print your profile')]"),
                By.xpath("//*[self::a or self::button][contains(translate(normalize-space(.),'ABCDEFGHIJKLMNOPQRSTUVWXYZ','abcdefghijklmnopqrstuvwxyz'),'seafarer profile') and contains(translate(normalize-space(.),'ABCDEFGHIJKLMNOPQRSTUVWXYZ','abcdefghijklmnopqrstuvwxyz'),'print')]")
        ));
        if (printProfile == null) throw new IllegalStateException("DG 'Click to View and Print Your Profile' link not found");

        Set<String> windowsBefore = new HashSet<>(driver.getWindowHandles());
        Set<Path> filesBefore = currentPdfFiles();
        String href = nvl(printProfile.getAttribute("href"));
        click(driver, printProfile);

        // 1) If DG directly downloads a PDF, use it.
        Path downloaded = waitForNewPdf(filesBefore, 10);
        if (downloaded != null) return renameDgPdf(downloaded, customerId, indos);

        // 2) If the printable profile opens in a new tab/window, switch to it.
        waitForNewWindowOrProfile(driver, windowsBefore, 12);
        Set<String> now = driver.getWindowHandles();
        if (now.size() > windowsBefore.size()) {
            String newWindow = now.stream().filter(w -> !windowsBefore.contains(w)).findFirst().orElse(null);
            if (newWindow != null) driver.switchTo().window(newWindow);
        }

        // 3) Another short chance for a browser download.
        downloaded = waitForNewPdf(filesBefore, 5);
        if (downloaded != null) return renameDgPdf(downloaded, customerId, indos);

        // 4) Old DG normally opens an HTML printable profile. Save that rendered page as PDF.
        // DG now runs in Firefox; Selenium's WebDriver print endpoint is used there.
        wait.until(d -> pageContainsAny(d, "Seafarer Profile", "Personal Details", "Authorised Documents", "INDoS No"));
        return printCurrentPageToPdf(driver, customerId, indos);
    }

    @SuppressWarnings("unchecked")
    private Path printCurrentPageToPdf(WebDriver driver, String customerId, String indos) throws IOException {
        String file = "DG-Profile-" + safeFilePart(customerId) + "-" + safeFilePart(indos) + ".pdf";
        Path out = unique(DG_DOWNLOAD_DIR.resolve(file));

        // Firefox supports the W3C WebDriver print command. This keeps the DG workflow
        // in Firefox while still producing the PDF required by DgProfilePdfParser.
        if (driver instanceof FirefoxDriver && driver instanceof PrintsPage printsPage) {
            Pdf pdf = printsPage.print(new PrintOptions());
            String encoded = pdf.getContent();
            if (encoded == null || encoded.isBlank()) {
                throw new IllegalStateException("Firefox could not create the DG profile PDF");
            }
            Files.write(out, Base64.getDecoder().decode(encoded));
            return out;
        }

        // Keep the old Chrome path as a fallback in case this method is reused elsewhere.
        if (driver instanceof ChromeDriver chrome) {
            Map<String, Object> result = chrome.executeCdpCommand("Page.printToPDF", Map.of(
                    "printBackground", true,
                    "preferCSSPageSize", true,
                    "paperWidth", 8.27,
                    "paperHeight", 11.69,
                    "marginTop", 0.25,
                    "marginBottom", 0.25,
                    "marginLeft", 0.25,
                    "marginRight", 0.25
            ));
            Object data = result.get("data");
            if (!(data instanceof String encoded) || encoded.isBlank()) {
                throw new IllegalStateException("Chrome could not create the DG profile PDF");
            }
            Files.write(out, Base64.getDecoder().decode(encoded));
            return out;
        }

        throw new IllegalStateException("Browser does not support automatic PDF printing");
    }

    private Path waitForNewPdf(Set<Path> before, int seconds) {
        Instant end = Instant.now().plusSeconds(seconds);
        while (Instant.now().isBefore(end)) {
            try {
                Set<Path> now = currentPdfFiles();
                Path newest = now.stream().filter(p -> !before.contains(p))
                        .filter(p -> !p.getFileName().toString().endsWith(".crdownload"))
                        .max(Comparator.comparingLong(this::lastModified)).orElse(null);
                if (newest != null && Files.size(newest) > 1000) return newest;
            } catch (Exception ignored) {}
            sleep(400);
        }
        return null;
    }

    private Set<Path> currentPdfFiles() {
        Set<Path> out = new HashSet<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(DG_DOWNLOAD_DIR, "*.pdf")) {
            for (Path p : stream) out.add(p.toAbsolutePath());
        } catch (IOException ignored) {}
        return out;
    }

    private Path renameDgPdf(Path original, String customerId, String indos) throws IOException {
        Path target = unique(DG_DOWNLOAD_DIR.resolve(
                "DG-Profile-" + safeFilePart(customerId) + "-" + safeFilePart(indos) + ".pdf"));
        if (original.toAbsolutePath().equals(target.toAbsolutePath())) return original;
        return Files.move(original, target, StandardCopyOption.REPLACE_EXISTING);
    }

    private void waitForNewWindowOrProfile(WebDriver driver, Set<String> before, int seconds) {
        Instant end = Instant.now().plusSeconds(seconds);
        while (Instant.now().isBefore(end)) {
            if (driver.getWindowHandles().size() > before.size()) return;
            if (pageContainsAny(driver, "Seafarer Profile", "Authorised Documents", "Personal Details")) return;
            sleep(300);
        }
    }

    // ---------------------------------------------------------------------
    // PAGINATION + SHARED HELPERS
    // ---------------------------------------------------------------------

    private PageState readPageState(WebDriver driver) {
        try {
            String body = driver.findElement(By.tagName("body")).getText();

            Matcher pageInfo = PAGE_INFO.matcher(body);
            if (pageInfo.find()) {
                return new PageState(
                        Integer.parseInt(pageInfo.group(1)),
                        Integer.parseInt(pageInfo.group(2)));
            }

            // Current AG Grid often shows only: "1 to 10 of 123".
            Matcher range = ROW_RANGE.matcher(body);
            if (range.find()) {
                int first = Integer.parseInt(range.group(1).replace(",", ""));
                int last = Integer.parseInt(range.group(2).replace(",", ""));
                int total = Integer.parseInt(range.group(3).replace(",", ""));
                int pageSize = Math.max(1, last - first + 1);
                int page = ((Math.max(1, first) - 1) / pageSize) + 1;
                int totalPages = Math.max(1, (total + pageSize - 1) / pageSize);
                return new PageState(page, totalPages);
            }
        } catch (Exception ignored) {}
        return new PageState(1, 1);
    }

    private WebElement findNextPageButton(WebDriver driver) {
        // Current AG Grid footer uses a DIV, not a <button>:
        // <div data-ref="btNext" class="ag-button ag-paging-button"
        //      role="button" aria-label="Next Page" aria-disabled="false">...</div>
        WebElement e = firstVisible(driver,
                By.cssSelector("[data-ref='btNext'][aria-label='Next Page']"),
                By.cssSelector("[data-ref='btNext']"),
                By.cssSelector(".ag-paging-button[aria-label*='Next']"),
                By.cssSelector("button[aria-label*='Next'],button[aria-label*='next']"),
                By.xpath("//*[contains(@class,'ag-icon-next')]/ancestor::*[@role='button' or self::button][1]")
        );
        if (e != null) return e;

        try {
            List<WebElement> candidates = driver.findElements(By.xpath(
                    "//*[contains(normalize-space(.),'Page ') and contains(normalize-space(.),' of ')]/following::*[@role='button' or self::button]"));
            for (WebElement b : candidates) {
                if (displayed(b)
                        && !isAriaDisabled(b)
                        && (nvl(b.getAttribute("aria-label")).toLowerCase(Locale.ROOT).contains("next")
                            || !b.findElements(By.cssSelector(".ag-icon-next")).isEmpty())) {
                    return b;
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    private void closeExtraTabsAndReturnToMm(WebDriver driver) {
        try {
            List<String> windows = new ArrayList<>(driver.getWindowHandles());
            if (windows.isEmpty()) return;
            String mm = windows.get(0);
            for (String w : windows) {
                if (w.equals(mm)) continue;
                try { driver.switchTo().window(w); driver.close(); } catch (Exception ignored) {}
            }
            driver.switchTo().window(mm);
        } catch (Exception ignored) {}
    }

    private void click(WebDriver driver, WebElement e) {
        try { e.click(); }
        catch (Exception ex) { ((JavascriptExecutor) driver).executeScript("arguments[0].click();", e); }
    }

    /** Set React/MUI controlled input and fire both input + change events. */
    private void setInputValue(WebDriver driver, WebElement input, String value) {
        try {
            input.click();
            input.sendKeys(Keys.chord(Keys.CONTROL, "a"));
            input.sendKeys(value);
            input.sendKeys(Keys.TAB);
            if (value.equals(nvl(input.getAttribute("value")))) return;
        } catch (Exception ignored) {}

        ((JavascriptExecutor) driver).executeScript(
                "var el=arguments[0],v=arguments[1];" +
                "var p=Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value');" +
                "if(p&&p.set){p.set.call(el,v);}else{el.value=v;}" +
                "el.dispatchEvent(new Event('input',{bubbles:true}));" +
                "el.dispatchEvent(new Event('change',{bubbles:true}));" +
                "el.blur();", input, value);
    }

    private WebElement firstVisible(WebDriver driver, By... locators) {
        for (By by : locators) {
            try {
                for (WebElement e : driver.findElements(by)) if (displayed(e)) return e;
            } catch (Exception ignored) {}
        }
        return null;
    }

    private boolean displayed(WebElement e) {
        try { return e != null && e.isDisplayed(); }
        catch (Exception ex) { return false; }
    }

    private String attributeValue(WebDriver d, By by, String attr) {
        try {
            WebElement e = firstVisible(d, by);
            return e == null ? "" : nvl(e.getAttribute(attr)).trim();
        } catch (Exception e) { return ""; }
    }

    /**
     * Read a value from a specific MM document section on the View Customer page.
     * The page repeats generic labels (Issued / Renewed Date, Expiry Date), so
     * searches are constrained to the block beginning at SID/CDC/COP/COC.
     */
    private String readDocumentSectionValue(WebDriver driver, String section, String... labels) {
        if (driver == null || blank(section) || labels == null) return "";

        // DOM-first: find the smallest visible ancestor around the document
        // section marker/number label, then inspect matching labels only there.
        try {
            Object value = ((JavascriptExecutor) driver).executeScript(
                    "var section=String(arguments[0]||'').toLowerCase();" +
                    "var labels=arguments[1]||[];labels=labels.map(function(x){return String(x||'').toLowerCase();});" +
                    "function vis(e){if(!e)return false;var r=e.getBoundingClientRect();var st=getComputedStyle(e);return r.width>0&&r.height>0&&st.display!=='none'&&st.visibility!=='hidden';}" +
                    "var nodes=Array.from(document.querySelectorAll('div,section,fieldset,article,tr,td'));" +
                    "var best=null;" +
                    "for(var i=0;i<nodes.length;i++){var n=nodes[i];if(!vis(n))continue;var t=(n.innerText||'').toLowerCase();" +
                    " if(t.indexOf(section+' number')<0&&t.indexOf(section+' no')<0)continue;" +
                    " var has=false;for(var h=0;h<labels.length;h++){if(t.indexOf(labels[h])>=0){has=true;break;}}if(!has)continue;" +
                    " if(!best||n.innerText.length<best.innerText.length)best=n;" +
                    "}" +
                    "if(!best)return '';" +
                    "var candidates=Array.from(best.querySelectorAll('label,p,span,div,td,th,dt,dd'));" +
                    "for(var l=0;l<labels.length;l++){var want=labels[l];" +
                    " for(var j=0;j<candidates.length;j++){var e=candidates[j];if(!vis(e))continue;var et=(e.innerText||'').trim().toLowerCase();if(et!==want)continue;" +
                    "   var sib=e.nextElementSibling;if(sib){var v=('value' in sib?String(sib.value||''):'').trim();if(v)return v;var st=(sib.innerText||'').trim();if(st)return st;}" +
                    "   var parent=e.parentElement;if(parent){var arr=Array.from(parent.children);var ix=arr.indexOf(e);for(var z=ix+1;z<arr.length;z++){var q=arr[z];var qv=('value' in q?String(q.value||''):'').trim();if(qv)return qv;var qt=(q.innerText||'').trim();if(qt)return qt;}}" +
                    " }" +
                    "}" +
                    "return '';",
                    section, java.util.Arrays.asList(labels)
            );
            String v = cleanNull(value == null ? "" : String.valueOf(value));
            if (!blank(v)) return v;
        } catch (Exception ignored) {}

        // Text fallback for the current MM View layout:
        // SID -> SID Number -> value -> Issued / Renewed Date -> value -> Expiry Date -> value -> COP.
        try {
            String body = safeBodyText(driver).replace('\r', '\n');
            String upper = body.toUpperCase(Locale.ROOT);
            int start = findDocumentSectionStart(upper, section);
            if (start >= 0) {
                int end = findNextDocumentSectionStart(upper, section, start + 1);
                String block = body.substring(start, end > start ? end : body.length());
                for (String label : labels) {
                    String v = valueAfterExactLineLabel(block, label);
                    if (!blank(v)) return cleanNull(v);
                }
            }
        } catch (Exception ignored) {}
        return "";
    }

    private int findDocumentSectionStart(String upperBody, String section) {
        String s = nvl(section).toUpperCase(Locale.ROOT);
        Matcher m = Pattern.compile("(?m)^\\s*" + Pattern.quote(s) + "\\s*$").matcher(upperBody);
        if (m.find()) return m.start();
        m = Pattern.compile("(?m)^\\s*" + Pattern.quote(s) + "\\s+(?:NUMBER|NO\\.?)\\b").matcher(upperBody);
        return m.find() ? m.start() : -1;
    }

    private int findNextDocumentSectionStart(String upperBody, String current, int from) {
        int best = -1;
        for (String section : new String[]{"CDC", "SID", "COP", "COC"}) {
            if (section.equalsIgnoreCase(current)) continue;
            Matcher m = Pattern.compile("(?m)^\\s*" + section + "\\s*$").matcher(upperBody);
            while (m.find()) {
                if (m.start() >= from && (best < 0 || m.start() < best)) best = m.start();
            }
        }
        return best;
    }

    private String valueAfterExactLineLabel(String block, String label) {
        if (blank(block) || blank(label)) return "";
        String[] lines = block.split("\\n+");
        for (int i = 0; i < lines.length; i++) {
            if (!cleanNull(lines[i]).equalsIgnoreCase(cleanNull(label))) continue;
            for (int j = i + 1; j < Math.min(lines.length, i + 4); j++) {
                String candidate = cleanNull(lines[j]);
                if (blank(candidate)) continue;
                boolean anotherLabel = candidate.equalsIgnoreCase("SID Number")
                        || candidate.equalsIgnoreCase("CDC Number")
                        || candidate.equalsIgnoreCase("COP Number")
                        || candidate.equalsIgnoreCase("COC Number")
                        || candidate.equalsIgnoreCase("Issued / Renewed Date")
                        || candidate.equalsIgnoreCase("Expiry Date")
                        || candidate.equalsIgnoreCase("Status");
                if (!anotherLabel) return candidate;
            }
        }
        return "";
    }

    /**
     * Read a value adjacent to a label. The MM View page is not consistent: for Email the value may
     * follow the label, while for Phone the value can appear immediately before the "Phone" label.
     */
    private String textNearLabel(WebDriver d, String... labels) {
        for (String label : labels) {
            try {
                List<WebElement> exact = d.findElements(By.xpath(
                        "//*[self::p or self::span or self::div or self::label or self::td or self::th or self::dt or self::dd][normalize-space()='" + escapeXpath(label) + "']"));
                for (WebElement l : exact) {
                    String v = nearestValueAroundLabel(l, label);
                    if (!blank(v)) return v;
                }
            } catch (Exception ignored) {}
        }
        return "";
    }

    private String nearestValueAroundLabel(WebElement label, String labelText) {
        String[] xpaths = {
                "following-sibling::*[1]",
                "preceding-sibling::*[1]",
                "../following-sibling::*[1]",
                "../preceding-sibling::*[1]",
                "following::input[1]",
                "following::textarea[1]"
        };
        for (String xp : xpaths) {
            try {
                WebElement e = label.findElement(By.xpath(xp));
                String value = cleanNull(nvl(e.getAttribute("value")));
                if (!blank(value) && !value.equalsIgnoreCase(labelText)) return value;
                String text = cleanNull(e.getText());
                if (!blank(text) && !text.equalsIgnoreCase(labelText)) return text;
            } catch (Exception ignored) {}
        }
        return "";
    }


    /**
     * Reject accidental neighbouring MM field text as a document number.
     * Example seen on the customer View page when Passport No is blank:
     * the old preceding-sibling fallback returned "Weight\nNull".
     */
    private String cleanDocumentNumberCandidate(String raw) {
        String value = cleanNull(raw);
        if (blank(value)) return "";

        String normalized = value.replace('\u00A0', ' ')
                .replaceAll("\\s+", " ")
                .trim()
                .toLowerCase(Locale.ROOT);

        if (normalized.matches("^(?:height|weight|hair colou?r|eye colou?r|complexion|identification marks?|passport(?: number| no\\.?)?|cdc(?: number| no\\.?)?|sid(?: number| no\\.?)?|bsid(?: number| no\\.?)?|cop(?: number| no\\.?)?|coc(?: number| no\\.?)?)\\s*(?:null|nil|none|n/?a|not provided|not available|undefined|-|--)?$")) {
            return "";
        }
        if (normalized.matches("^(?:height|weight|hair colou?r|eye colou?r|complexion|identification marks?)\\b.*")) {
            return "";
        }
        return value;
    }

    private String textAfterLabel(WebDriver d, String... labels) {
        for (String label : labels) {
            try {
                List<WebElement> exact = d.findElements(By.xpath(
                        "//*[self::p or self::span or self::div or self::label or self::td or self::th or self::dt or self::dd][normalize-space()='" + escapeXpath(label) + "']"));
                for (WebElement l : exact) {
                    String v = nearestFollowingValue(l);
                    if (!blank(v) && !v.equalsIgnoreCase(label)) return v;
                }
            } catch (Exception ignored) {}
        }
        return "";
    }

    private String nearestFollowingValue(WebElement label) {
        String[] xpaths = {"following-sibling::*[1]", "../following-sibling::*[1]", "following::*[1]"};
        for (String xp : xpaths) {
            try {
                WebElement e = label.findElement(By.xpath(xp));
                String text = e.getText().trim();
                if (!blank(text)) return text;
                String value = e.getAttribute("value");
                if (!blank(value)) return value;
            } catch (Exception ignored) {}
        }
        return "";
    }

    private boolean pageContains(WebDriver d, String text) {
        try { return d.getPageSource().toLowerCase(Locale.ROOT).contains(text.toLowerCase(Locale.ROOT)); }
        catch (Exception e) { return false; }
    }

    private boolean pageContainsAny(WebDriver d, String... texts) {
        for (String text : texts) if (pageContains(d, text)) return true;
        return false;
    }

    private String customerId(String url) {
        Matcher m = CUSTOMER_ID.matcher(nvl(url));
        return m.find() ? m.group(1) : "";
    }

    private boolean sameIndos(String a, String b) {
        return !blank(a) && !blank(b) && normalizeId(a).equals(normalizeId(b));
    }

    private String normalizeId(String s) {
        return nvl(s).replaceAll("[^A-Za-z0-9]", "").toUpperCase(Locale.ROOT);
    }

    private boolean equivalent(String a, String b) {
        String x = cleanNull(a);
        String y = cleanNull(b);
        if (x.equalsIgnoreCase(y)) return true;
        // Number-like fields can be represented as 178 vs 178.0.
        try { return Double.compare(Double.parseDouble(x), Double.parseDouble(y)) == 0; }
        catch (Exception ignored) {}
        return false;
    }

    private long lastModified(Path p) {
        try { return Files.getLastModifiedTime(p).toMillis(); }
        catch (IOException e) { return 0L; }
    }

    private Path unique(Path p) {
        if (!Files.exists(p)) return p;
        String name = p.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String stem = dot >= 0 ? name.substring(0, dot) : name;
        String ext = dot >= 0 ? name.substring(dot) : "";
        for (int i = 2; i < 10000; i++) {
            Path n = p.getParent().resolve(stem + "-" + i + ext);
            if (!Files.exists(n)) return n;
        }
        return p.getParent().resolve(stem + "-" + System.currentTimeMillis() + ext);
    }

    private String safeFilePart(String s) {
        String v = nvl(s).replaceAll("[^A-Za-z0-9._-]", "_");
        return v.isBlank() ? "unknown" : v;
    }

    private long modifiedTime(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (Exception e) {
            return 0L;
        }
    }

    private String safeMessage(Exception e) {
        String m = e.getMessage();
        if (blank(m)) return e.getClass().getSimpleName();
        String firstLine = m.split("\\R", 2)[0];
        String cleaned = firstLine
                .replaceAll("(?i)(password\\s*[=:]\\s*)\\S+", "$1***")
                .replaceAll("\\s+", " ")
                .trim();
        return cleaned.length() <= 260 ? cleaned : cleaned.substring(0, 260);
    }

    private String masked(String name) {
        if (blank(name)) return "customer";
        String n = name.trim();
        return n.length() <= 2 ? "**" : n.substring(0, 2) + "***";
    }

    private String maskIndos(String indos) {
        String s = nvl(indos).trim();
        return s.length() <= 4 ? "****" : s.substring(0, 2) + "***" + s.substring(s.length() - 2);
    }

    private String cleanNull(String v) {
        if (v == null) return "";
        String x = v.replace('\u00A0', ' ').trim();
        String normalized = x.toLowerCase(Locale.ROOT)
                .replaceAll("[._]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
        if (normalized.isEmpty()
                || normalized.equals("null")
                || normalized.equals("undefined")
                || normalized.equals("-")
                || normalized.equals("--")
                || normalized.equals("n/a")
                || normalized.equals("na")
                || normalized.equals("not provided")
                || normalized.equals("not available")
                || normalized.equals("no data")
                || normalized.equals("none")
                || normalized.equals("nil")) {
            return "";
        }
        return x;
    }

    private String trimSlash(String s) {
        String x = nvl(s).trim();
        while (x.endsWith("/")) x = x.substring(0, x.length() - 1);
        return x;
    }

    private void require(String value, String name) {
        if (blank(value) || value.startsWith("PUT_")) {
            throw new IllegalStateException("Missing " + name + " in config.properties");
        }
    }

    private String escapeXpath(String s) { return s.replace("'", ""); }
    private boolean blank(String s) { return s == null || s.trim().isEmpty(); }
    private String nvl(String s) { return s == null ? "" : s; }
    private void sleep(long ms) { try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }

    private static final class Config {
        private final boolean enabled;
        private final String dashboardUrl;
        private final String dashboardUsername;
        private final String dashboardPassword;
        private final String dgLoginUrl;
        private final boolean headless;
        private final boolean keepBrowserOpen;
        private final boolean keepDgPdf;
        private final int waitSeconds;
        private final int dgWaitSeconds;
        private final boolean sidCheckerEnabled;
        private final String sidCheckerUrl;
        private final int sidCaptchaWaitSeconds;
        private final boolean stcwCheckerEnabled;
        private final String stcwCheckerUrl;

        private Config(boolean enabled, String dashboardUrl, String dashboardUsername,
                       String dashboardPassword, String dgLoginUrl, boolean headless,
                       boolean keepBrowserOpen, boolean keepDgPdf, int waitSeconds, int dgWaitSeconds,
                       boolean sidCheckerEnabled, String sidCheckerUrl, int sidCaptchaWaitSeconds,
                       boolean stcwCheckerEnabled, String stcwCheckerUrl) {
            this.enabled = enabled;
            this.dashboardUrl = dashboardUrl;
            this.dashboardUsername = dashboardUsername;
            this.dashboardPassword = dashboardPassword;
            this.dgLoginUrl = dgLoginUrl;
            this.headless = headless;
            this.keepBrowserOpen = keepBrowserOpen;
            this.keepDgPdf = keepDgPdf;
            this.waitSeconds = waitSeconds;
            this.dgWaitSeconds = dgWaitSeconds;
            this.sidCheckerEnabled = sidCheckerEnabled;
            this.sidCheckerUrl = sidCheckerUrl;
            this.sidCaptchaWaitSeconds = sidCaptchaWaitSeconds;
            this.stcwCheckerEnabled = stcwCheckerEnabled;
            this.stcwCheckerUrl = stcwCheckerUrl;
        }

        static Config from(Properties p) {
            String dashboardEmail = firstNonBlank(
                    p.getProperty("customer.profile.sync.dashboard.email"),
                    p.getProperty("dashboard.email"));
            String dashboardPassword = firstNonBlank(
                    p.getProperty("customer.profile.sync.dashboard.password"),
                    p.getProperty("dashboard.password"));
            return new Config(
                    bool(p, "customer.profile.sync.enabled", true),
                    value(p, "customer.profile.sync.dashboard.url", "https://dashboard.marinersmentor.com"),
                    dashboardEmail,
                    dashboardPassword,
                    value(p, "customer.profile.sync.dg.login.url", "http://220.156.189.33/esamudraUI/logOut.do?method=loadIndexPage"),
                    bool(p, "customer.profile.sync.headless", true),
                    bool(p, "customer.profile.sync.keep.browser.open", false),
                    bool(p, "customer.profile.sync.keep.dg.pdf", false),
                    integer(p, "customer.profile.sync.wait.seconds", 25),
                    integer(p, "customer.profile.sync.dg.wait.seconds", 90),
                    bool(p, "customer.profile.sync.sid.checker.enabled", true),
                    value(p, "customer.profile.sync.sid.checker.url", "https://dgshippingbsid.in/"),
                    integer(p, "customer.profile.sync.sid.captcha.wait.seconds", 180),
                    bool(p, "membership.stcw.checker.enabled", true),
                    value(p, "membership.stcw.checker.url",
                            "http://220.156.189.33/esamudraUI/jsp/examination/checker/PP_IndosChecker.jsp"));
        }

        private static boolean bool(Properties p, String key, boolean fallback) {
            String s = p.getProperty(key);
            return s == null || s.isBlank() ? fallback : Boolean.parseBoolean(s.trim());
        }

        private static int integer(Properties p, String key, int fallback) {
            try {
                String s = p.getProperty(key);
                return s == null || s.isBlank() ? fallback : Integer.parseInt(s.trim());
            } catch (Exception e) {
                return fallback;
            }
        }

        private static String value(Properties p, String key, String fallback) {
            String s = p.getProperty(key);
            return s == null || s.isBlank() ? fallback : s.trim();
        }

        private static String firstNonBlank(String... values) {
            for (String s : values) if (s != null && !s.isBlank()) return s.trim();
            return "";
        }

        boolean isEnabled(){ return enabled; }
        String getDashboardUrl(){ return dashboardUrl; }
        String getDashboardUsername(){ return dashboardUsername; }
        String getDashboardPassword(){ return dashboardPassword; }
        String getDgLoginUrl(){ return dgLoginUrl; }
        boolean isHeadless(){ return headless; }
        boolean isKeepBrowserOpen(){ return keepBrowserOpen; }
        boolean isKeepDgPdf(){ return keepDgPdf; }
        int getWaitSeconds(){ return waitSeconds; }
        int getDgWaitSeconds(){ return dgWaitSeconds; }
        boolean isSidCheckerEnabled(){ return sidCheckerEnabled; }
        String getSidCheckerUrl(){ return sidCheckerUrl; }
        int getSidCaptchaWaitSeconds(){ return sidCaptchaWaitSeconds; }
        boolean isStcwCheckerEnabled(){ return stcwCheckerEnabled; }
        String getStcwCheckerUrl(){ return stcwCheckerUrl; }
    }

    private static final class WrongIndosPasswordException extends RuntimeException {
        WrongIndosPasswordException(String message) { super(message); }
    }

    private record MmCustomer(String customerName, String firstName, String surname, String dob,
                              String fatherName, String passportNo, String cdcNo, String sidNo,
                              String sidIssuedDate, String sidExpiryDate, String role, String vessel, String rpsl,
                              String indosNo, String phone, String email, String createdBy, String createdAt) {}
    private record DgResult(String mmWindow, String dgWindow, Path pdf, CustomerProfileData data, ResumeEntryData resumeData) {}
    private record SidLookupResult(boolean attempted, CustomerProfileData.DocumentInfo sid, String detail, String status) {
        static SidLookupResult notAttempted() {
            return new SidLookupResult(false, CustomerProfileData.DocumentInfo.empty(), "", "");
        }
    }
    private record UpdateResult(boolean changed, int changedFields) {}
    private record ChoiceResult(int changed, boolean optionMissing) {}
    private record PageState(int page, int totalPages) {}

    public interface ProgressListener {
        void onProgress(SyncProgress progress);
    }

    public record SyncProgress(
            int sourceCases,
            int customersFound,
            int alreadyHandled,
            int queuedForThisRun,
            int scanned,
            int dgProfilesRead,
            int updated,
            int noChange,
            int skipped
    ) {
        public int remaining() {
            return Math.max(0, queuedForThisRun - scanned);
        }
    }

    public record MissingCredential(String customerId, String customerName,
                                    String indosNo, String passwordStatus, String createdBy, String createdAt) {}

    public record MissingField(String customerId, String customerName, String indosNo,
                               String fields, String detail, String createdBy, String createdAt) {}

    public record SyncReport(int sourceCases, int customersFound, int alreadyHandled, int scanned,
                             int dgProfilesRead, int updated, int noChange, int skipped,
                             List<String> notes, List<MissingCredential> missingCredentials,
                             List<MissingField> missingFields) {
        public int newOrRetryCustomers() { return scanned; }
        public String summary() {
            return "MM ALL TIME Customers DG sync completed. All Time customers=" + customersFound
                    + ", Already handled=" + alreadyHandled
                    + ", New/retry processed=" + scanned
                    + ", DG profiles read=" + dgProfilesRead
                    + ", Updated=" + updated
                    + ", No change=" + noChange
                    + ", Skipped/Failed=" + skipped
                    + ", Missing profile rows=" + (missingFields == null ? 0 : missingFields.size());
        }
    }
}
