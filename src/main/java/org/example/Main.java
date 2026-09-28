package org.example;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.sheets.v4.Sheets;
import com.google.api.services.sheets.v4.SheetsScopes;
import com.google.api.services.sheets.v4.model.ValueRange;
import com.google.api.services.sheets.v4.model.AddSheetRequest;
import com.google.api.services.sheets.v4.model.BatchUpdateSpreadsheetRequest;
import com.google.api.services.sheets.v4.model.Request;
import com.google.api.services.sheets.v4.model.SheetProperties;
import com.google.api.services.sheets.v4.model.Spreadsheet;
import com.google.auth.http.HttpCredentialsAdapter;
import com.google.auth.oauth2.GoogleCredentials;

import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.Select;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import jakarta.annotation.PreDestroy;
import jakarta.servlet.http.HttpServletRequest;
@EnableScheduling
@SpringBootApplication
public class Main {

    private static final String SPREADSHEET_ID =
            "1ZhFXuWf8RLJ9CQL7AbbTJ0Xa9I42BPzWrF1_9S59ZRA";

    private static final String SERVICE_ACCOUNT_FILE = "service-account.json";

    private static final String CASES_API =
            "https://backend.marinersmentor.com/api/cases/?period=month";

    private static final String BIRTHDAY_CASES_API =
            "https://backend.marinersmentor.com/api/cases/?period=all";

    // Heavy period=all endpoint is shared by Birthday + Customer Profile.
    // One request is reused instead of both modules hitting the 7,000+ case endpoint together.
    private static final Object ALL_CASES_SNAPSHOT_LOCK = new Object();
    private static volatile List<CaseRow> ALL_CASES_MEMORY_CACHE = Collections.emptyList();
    private static volatile long ALL_CASES_MEMORY_CACHE_AT = 0L;
    private static final long ALL_CASES_MEMORY_TTL_MILLIS =
            Duration.ofHours(6).toMillis();
    private static final Path ALL_CASES_DISK_CACHE =
            resolveMmLocalStateDir().resolve("all-cases-period-all-cache.json");

    private static final String CUSTOMER_API_BASE =
            "https://backend.marinersmentor.com/api/customers/";

    private static final String CASE_DETAIL_API_BASE =
            "https://backend.marinersmentor.com/api/cases/";

    private static final String WELCOME_SHEET = "new welcome mail";

    private static final String BIRTHDAY_CASE_SHEET = "Birthday Cases";

    private static final String JSU_LOGIN_URL =
            "https://jsurpp.com/fmember_rppccas/login";

    private static final String SPFO_LOGIN_URL =
            "https://spfo.gov.in/spfo/login.html?logout=true";

    private static final String DEFAULT_PUBLIC_APP_URL =
            "http://localhost:18080";

    private static final long JSU_LINK_VALIDITY_MILLIS =
            Duration.ofHours(2).toMillis();

    private static final Pattern RPP_NUMBER_PATTERN =
            Pattern.compile("(?i)\\bRPP\\s*(?:NO\\.?|NUMBER)?\\s*:\\s*([A-Z0-9\\-/]+)");

    // Agent selection for any service written in case remarks, e.g. Agent : Udhayan
    private static final Pattern AGENT_PATTERN =
            Pattern.compile("(?i)\\bAGENT\\s*:\\s*([^\\r\\n;|]+)");

    // Name Correction remarks syntax, e.g. New Name : JOHN MICHAEL
    private static final Pattern NEW_NAME_PATTERN =
            Pattern.compile("(?i)\\b(?:NEW\\s+)?NAME\\s*:\\s*([^\\r\\n;|]+)");

    // MM Membership is triggered by the selected Case Service itself.
    // No ML text is required in Comments / Remarks.

    private static final Map<Long, JsuBalanceController.JsuBalanceResult> JSU_BALANCE_CACHE =
            new ConcurrentHashMap<>();
    private static final Map<Long, RuntimeException> JSU_BALANCE_FAILURE_CACHE =
            new ConcurrentHashMap<>();

    // SPFO candidate email is retryable until SMTP reports success.
    // Old permanent "attempted" markers must not block a candidate from
    // receiving the result. This set prevents duplicate sends inside one JVM.
    private static final Set<Long> SPFO_BALANCE_EMAIL_IN_FLIGHT =
            ConcurrentHashMap.newKeySet();

    private static final ZoneId INDIA_ZONE = ZoneId.of("Asia/Kolkata");

    private static final List<Object> WELCOME_HEADERS = List.of(
            "Case ID",
            "Customer Name",
            "Email",
            "Service",
            "Mail Status",
            "Date",
            "Assigned To",
            "Phone",
            "WhatsApp Status",
            "Remarks",
            "Reminder Set Status",
            "Reminder Location",
            "Reminder WhatsApp Status",
            "Reminder Mail Status",
            "Reminder Last Attempt"
    );

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final HttpClient HTTP_CLIENT =
            HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(30))
                    .build();

    private static Sheets sheetsService;
    private static Properties config;
    private static final boolean JSU_ONLY_RESEND_MODE = false;

    // One-time requested JSU Balance resend. Each channel gets its own persistent
    // completion marker, so restarting the app cannot send it twice.
    private static final Set<Long> MANUAL_JSU_RESEND_CASE_IDS = Set.of(7746L);
    private static volatile String dashboardAccessToken;

    private static final Object REMINDER_LOCK =
            new Object();

    private static final Object SENT_HISTORY_LOCK = new Object();
    private static final Object WELCOME_POLL_LOCK = new Object();

    private static final AtomicBoolean CUSTOMER_PROFILE_SYNC_RUNNING =
            new AtomicBoolean(false);

    // Membership cases have priority over the long historical/new-customer DG run.
    // The normal customer loop pauses after the current customer, lets the
    // Membership case run, then resumes from the persistent handled-state.
    private static final AtomicBoolean MEMBERSHIP_PRIORITY_PENDING =
            new AtomicBoolean(false);
    private static final AtomicBoolean CUSTOMER_BACKFILL_RESUME_REQUESTED =
            new AtomicBoolean(false);

    // Membership data work is different from candidate communication.
    // Mail/WhatsApp remain no-resend, but while the Membership case is OPEN
    // an incomplete DG/SID/Sheet/Customer sync is retried every 5 minutes.
    private static final Map<Long, Long> MM_MEMBERSHIP_LAST_ATTEMPT_MS =
            new ConcurrentHashMap<>();
    private static final long MM_MEMBERSHIP_RETRY_COOLDOWN_MS =
            Duration.ofMinutes(5).toMillis();

    // Console rows are state-based, not poll-based. The 5/10-second schedulers may
    // inspect the same case repeatedly, but an unchanged status is printed only once.
    private static final Map<Long, String> LAST_CASE_CONSOLE_SUMMARY =
            new ConcurrentHashMap<>();

    // Admin report rows are accumulated during the run and automatically
    // emailed once when the application stops. No browser/Gmail compose window is used.
    private static final Object ADMIN_REPORT_LOCK = new Object();
    private static final LinkedHashMap<Long, String> PENDING_ADMIN_REPORT_ROWS =
            new LinkedHashMap<>();
    private static volatile boolean SHUTTING_DOWN = false;

    // Strict startup pipeline requested by user:
    // CASES -> CUSTOMERS/DG -> SPFO INTEREST -> BIRTHDAY.
    // Scheduled jobs are held while this sequence is running so they cannot jump ahead.
    private static volatile boolean STARTUP_PIPELINE_RUNNING = true;
    private static volatile CustomerProfileBackfillService.SyncProgress LAST_CUSTOMER_SYNC_PROGRESS = null;

    /*
     * IMPORTANT: send history must NOT live inside the extracted project folder.
     * Users commonly replace/extract a new ZIP build, which would otherwise reset
     * the bot's memory and allow old cases to be sent again.
     *
     * Windows default:
     *   %LOCALAPPDATA%\MarinersMentor\MMcasesBot\
     *
     * Override if required with environment variable MMCASEBOT_STATE_DIR.
     */
    private static final Path LEGACY_SENT_HISTORY_FILE =
            Path.of("sent-welcome-cases.properties");
    private static final Path LEGACY_ATTEMPT_HISTORY_FILE =
            Path.of("attempted-welcome-cases.properties");

    private static final Path BOT_STATE_DIR = resolvePersistentStateDir();
    private static final Path SENT_HISTORY_FILE =
            BOT_STATE_DIR.resolve("sent-welcome-cases.properties");

    // Permanent at-most-once claims are stored outside the project folder too.
    private static final Path ATTEMPT_HISTORY_FILE =
            BOT_STATE_DIR.resolve("attempted-welcome-cases.properties");

    // Atomic per-case/channel claim files. Files.createFile() is atomic on the
    // same filesystem, so even two Java processes on this PC cannot both send
    // the same case/channel at the same time.
    private static final Path ATTEMPT_CLAIMS_DIR =
            BOT_STATE_DIR.resolve("attempt-claims");

    // TEMP TEST: only these case IDs may be reprocessed once per application start.
    // The one-minute poll will not keep resending them. All other cases stay once-per-day.
    private static final Set<Long> TEST_REPEAT_CASE_IDS = Set.of();
    private static final Set<Long> TEST_REPEATED_THIS_RUN = ConcurrentHashMap.newKeySet();

    // SPFO cases stay under follow-up until the Mariners Mentor case is closed.
    // Because SPFO CAPTCHA is manual, never reopen a browser every 5 seconds.
    // The cooldown is configurable with spfo.recheck.minutes (default 1440 minutes / once per day).
    private static final Map<Long, Long> SPFO_LAST_WORKFLOW_ATTEMPT_MS =
            new ConcurrentHashMap<>();

    private static final DateTimeFormatter REMINDER_DATE_FORMAT =
            DateTimeFormatter.ofPattern("dd/MM/uuuu")
                    .withResolverStyle(
                            java.time.format.ResolverStyle.STRICT
                    );

    private static final Pattern REMINDER_PATTERN =
            Pattern.compile(
                    "(?i)\\bREM\\s*:\\s*"
                            + "(\\d{2}/\\d{2}/\\d{4})"
                            + "\\s*@\\s*([A-Z]{3})\\b"
            );

    private static Path resolvePersistentStateDir() {
        String override = safe(System.getenv("MMCASEBOT_STATE_DIR")).trim();
        if (!override.isBlank()) {
            return Path.of(override).toAbsolutePath().normalize();
        }

        String localAppData = safe(System.getenv("LOCALAPPDATA")).trim();
        if (!localAppData.isBlank()) {
            return Path.of(localAppData, "MarinersMentor", "MMcasesBot")
                    .toAbsolutePath().normalize();
        }

        String userHome = safe(System.getProperty("user.home")).trim();
        if (!userHome.isBlank()) {
            return Path.of(userHome, ".mmcasesbot").toAbsolutePath().normalize();
        }

        return Path.of(".mmcasesbot-state").toAbsolutePath().normalize();
    }

    private static void ensurePersistentStateDir() throws IOException {
        Files.createDirectories(BOT_STATE_DIR);
    }

    private static boolean isSpfoTestOnlyMode() {
        return config != null && Boolean.parseBoolean(
                config.getProperty("spfo.test.only", "false").trim()
        );
    }

    private static boolean isCustomerOnlyMode() {
        return config != null && Boolean.parseBoolean(
                config.getProperty("customer.only.mode", "false").trim()
        );
    }

    /**
     * Fast local startup guard.
     * IntelliJ users sometimes leave an older MMcasesBot JVM running on port 8080.
     * Before starting a new local copy, stop only older Java processes whose command
     * line clearly belongs to MMcasesBot. Railway/cloud runs are never touched.
     */
    private static void stopOlderLocalMmCasesBotInstances() {
        if (System.getenv("RAILWAY_ENVIRONMENT") != null
                || System.getenv("RAILWAY_PROJECT_ID") != null
                || System.getenv("RAILWAY_SERVICE_ID") != null) {
            return;
        }

        long currentPid = ProcessHandle.current().pid();
        for (ProcessHandle process : ProcessHandle.allProcesses().toList()) {
            if (process.pid() == currentPid || !process.isAlive()) {
                continue;
            }

            ProcessHandle.Info info = process.info();
            String command = info.command().orElse("");
            String commandLine = info.commandLine().orElse("");
            String arguments = String.join(" ", info.arguments().orElse(new String[0]));
            String combined = (command + " " + commandLine + " " + arguments)
                    .toLowerCase(Locale.ROOT);

            boolean isJava = combined.contains("java");
            boolean isThisBot = combined.contains("org.example.main")
                    && combined.contains("mmcasesbot");

            if (!isJava || !isThisBot) {
                continue;
            }

            try {
                System.out.println("OLD MMCASEBOT FOUND | PID " + process.pid() + " | stopping it before restart...");
                process.destroy();
                try { Thread.sleep(900L); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
                if (process.isAlive()) {
                    process.destroyForcibly();
                    try { Thread.sleep(500L); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
                }
                System.out.println("OLD MMCASEBOT STOPPED | PID " + process.pid());
            } catch (Exception e) {
                System.err.println("OLD MMCASEBOT STOP FAILED | PID " + process.pid() + " | " + e.getMessage());
            }
        }
    }

    public static void main(String[] args) {
        // Keep the local console operator-friendly: one compact row per case,
        // plus Membership/SID/SPFO prompts and failures.
        if (!isRailwayRuntime()) {
            CompactConsole.install();
        }
        stopOlderLocalMmCasesBotInstances();

        /*
         * LOCAL WINDOWS MODE DOES NOT NEED TOMCAT.
         * This bot mainly uses scheduled jobs + Selenium + API calls. Running a web
         * server locally caused repeated 8080/18080 conflicts and made IntelliJ look
         * like the bot was broken. Keep the web server only on Railway/cloud, where
         * the public endpoints are actually needed. @Scheduled jobs still work in
         * WebApplicationType.NONE.
         */
        SpringApplication app = new SpringApplication(Main.class);
        boolean cloudWebMode = System.getenv("RAILWAY_ENVIRONMENT") != null
                || System.getenv("RAILWAY_PROJECT_ID") != null
                || System.getenv("RAILWAY_SERVICE_ID") != null;
        if (!cloudWebMode) {
            app.setWebApplicationType(WebApplicationType.NONE);
            System.out.println("LOCAL MODE | NO TOMCAT / NO PORT | port conflicts cannot stop MMcasesBot");
        }

        app.run(args);
        System.out.println("==============================================");
        System.out.println("MARINERS MENTOR MMCASEBOT STARTED");
        System.out.println("BUILD = 20260916 OPEN-SPFO-JSU + MEMBERSHIP-COMPACT");
        System.out.println("==============================================");

        try {
            validateConfiguration();

            config = loadConfigProperties();
            // Admin report recipient is controlled only by report.mail.account in config.properties.
            // Leave it blank to disable admin reports until an address is added later.
            ensurePersistentStateDir();
            System.out.println("CUSTOMER SYNC STATE DIR | " + BOT_STATE_DIR);

            // =========================================================
            // CUSTOMER-ONLY MODE
            // Do not read/process cases, SPFO, birthday or reminders.
            // Historical run: Customers -> ACTIVE -> All Time -> every Customer ID.
            // After historical run, the 5-minute watcher handles newly created customers.
            // =========================================================
            if (isCustomerOnlyMode()) {
                sheetsService = null;
                dashboardAccessToken = "";
                STARTUP_PIPELINE_RUNNING = true;
                System.out.println("==============================================");
                System.out.println("CUSTOMER-ONLY MODE ACTIVE");
                System.out.println("SOURCE = CUSTOMERS TAB | ACTIVE | PERIOD=ALL TIME");
                System.out.println("HISTORICAL = ALL CUSTOMER IDs | NEW CUSTOMER WATCH = EVERY 5 MINUTES");
                System.out.println("CASES / CASE WHATSAPP / BIRTHDAY / SPFO / REMINDERS = DISABLED");
                System.out.println("REPORT = Si.No | Customer ID | Created By | Created At | Status");
                System.out.println("==============================================");
                try {
                    runCustomerProfileBackfillBlocking("STARTUP-CUSTOMERS-ONLY", Collections.emptyList());
                } finally {
                    STARTUP_PIPELINE_RUNNING = false;
                }
                System.out.println("CUSTOMER-ONLY HISTORICAL RUN COMPLETE | 5-minute new-customer watch remains enabled.");
                return;
            }

            CaseRuleService.configure(config);
            // Load once now so startup clearly tells us whether the shared
            // MM Case Reminder Rules sheet is being used. Rules refresh every minute.
            CaseRuleService.getRules();
            System.out.println("CASE RULES | " + CaseRuleService.getLastLoadMessage());

            dashboardAccessToken = readAccessTokenIfAvailable();

            // HARD SAFE SPFO TEST MODE.
            // When spfo.test.only=true, normal case processing NEVER starts, even if
            // the test case ID is blank or invalid. This prevents accidental resend
            // of candidate email/WhatsApp while testing SPFO.
            boolean spfoTestOnly = Boolean.parseBoolean(
                    config.getProperty("spfo.test.only", "false").trim()
            );

            if (spfoTestOnly) {
                // A manual case ID is used ONLY in explicit SPFO test-only mode.
                // Production mode never needs a case ID in config.properties.
                String spfoTestCaseId = firstNonBlank(
                        System.getProperty("spfo.test.case.id"),
                        System.getenv("SPFO_TEST_CASE_ID"),
                        config.getProperty("spfo.test.case.id", "")
                );

                boolean spfoTestSendEnabled = Boolean.parseBoolean(
                        config.getProperty("spfo.test.send.result", "false").trim()
                );

                System.out.println("==============================================");
                System.out.println("SPFO HARD TEST-ONLY MODE | CASE " + spfoTestCaseId);
                System.out.println("NORMAL CASE LOOP DISABLED - ONLY THE TEST CASE WILL RUN");
                System.out.println(spfoTestSendEnabled
                        ? "TEST SEND ENABLED | EMAIL + WHATSAPP WILL SEND AFTER RESOLVED PDF IS FOUND"
                        : "SAFE PREVIEW | EMAIL + WHATSAPP DISABLED");
                System.out.println("==============================================");

                if (spfoTestCaseId.isBlank()) {
                    System.out.println("STOPPED SAFELY: test-only mode needs spfo.test.case.id.");
                    return;
                }

                try {
                    long testCaseId = Long.parseLong(spfoTestCaseId.trim());
                    runSpfoLoginTestFromCase(testCaseId);
                } catch (NumberFormatException badCaseId) {
                    System.out.println("STOPPED SAFELY: invalid spfo.test.case.id = " + spfoTestCaseId);
                    return;
                }

                System.out.println("SPFO TEST MODE FINISHED - normal case processing was NOT started.");
                return;
            }

            System.out.println("SPFO PRODUCTION AUTO MODE - NO CASE ID REQUIRED");
            System.out.println("TODAY\'S CASES + OPEN SPFO INTEREST PROBLEM CASES WILL BE CHECKED AUTOMATICALLY");
            System.out.println("SPFO INTEREST RECHECK MODE - ONCE PER DAY (PERSISTENT ACROSS RESTARTS)");

            // Railway is used only as the public JSU Open-Mail web server.
            // Case processing stays on the local PC so Railway and IntelliJ
            // cannot send the same case twice.
            if (isRailwayRuntime() && !isRailwayCaseProcessingEnabled()) {
                sheetsService = null;
                System.out.println(
                        "RAILWAY LINK-SERVER MODE - CASE MAIL/WHATSAPP PROCESSING DISABLED"
                );
                return;
            }

            boolean welcomeSheetEnabled =
                    Boolean.parseBoolean(
                            config.getProperty(
                                    "welcome.sheet.enabled",
                                    "false"
                            ).trim()
                    );

            /*
             * Reminder tracking uses the same Google Sheet even when the normal
             * welcome-sheet flow is disabled. Previously, welcome.sheet.enabled=false
             * forced sheetsService=null, so the 10:00 AM reminder scheduler skipped
             * every reminder before WhatsApp was reached.
             */
            boolean reminderEnabled =
                    Boolean.parseBoolean(
                            config.getProperty(
                                    "reminder.enabled",
                                    "true"
                            ).trim()
                    );

            boolean birthdayCaseSyncEnabled =
                    Boolean.parseBoolean(
                            config.getProperty(
                                    "birthday.case.sync.enabled",
                                    "false"
                            ).trim()
                    );

            if (welcomeSheetEnabled || reminderEnabled || birthdayCaseSyncEnabled) {
                sheetsService = createSheetsService();

                if (!welcomeSheetEnabled && (reminderEnabled || birthdayCaseSyncEnabled)) {
                    System.out.println(
                            "WELCOME SHEET FLOW DISABLED - GOOGLE SHEETS ACTIVE FOR REMINDERS/BIRTHDAY CASE SYNC."
                    );
                }
            } else {
                sheetsService = null;
                System.out.println(
                        "WELCOME SHEET, REMINDERS AND BIRTHDAY CASE SYNC DISABLED - service-account.json is not required."
                );
            }

            // Membership workbook self-repair. If the admin clears old rows or even
            // deletes row 1, recreate the three normalized headers immediately at startup.
            // This does NOT write ordinary/non-membership customer data.
            try {
                ResumeEntrySheetService membershipSheets = new ResumeEntrySheetService(config);
                if (membershipSheets.isEnabled()) {
                    membershipSheets.ensureStructure();
                    System.out.println(
                            "MEMBERSHIP SHEET STRUCTURE READY | Customer Profile + Course Details + Sea Service"
                    );
                }
            } catch (Exception membershipSheetStructureError) {
                System.err.println(
                        "MEMBERSHIP SHEET STRUCTURE CHECK FAILED | "
                                + safe(membershipSheetStructureError.getMessage())
                );
            }

            // =========================================================
            // STARTUP ORDER
            // 1) Read cases
            // 2) Process normal/current cases exactly as usual (mail + WhatsApp + SPFO rules)
            // 3) Send today's birthday wishes immediately (do not wait for 10k customer update)
            // 4) Start ALL-TIME customer DG profile update in the background
            // Scheduled case + birthday checks remain active while customer update runs.
            // =========================================================
            STARTUP_PIPELINE_RUNNING = true;
            System.out.println("==============================================");
            System.out.println("STARTUP PIPELINE PHASE 1/4 | READING CASES");
            System.out.println("ORDER: CASES -> NORMAL WHATSAPP/MAIL -> BIRTHDAY -> CUSTOMER DG (BACKGROUND)");
            System.out.println("==============================================");

            // FAST START: do not download the 7,000+ all-time case list on the UI/startup thread.
            // Birthday registry refresh remains scheduled and customer all-time backfill remains daily.
            List<CaseRow> allCases = fetchCases();

            System.out.println(
                    "STARTUP CASE READ COMPLETE"
                            + " | Current/month cases: " + allCases.size()
                            + " | Heavy all-time scan: DEFERRED"
            );

            // Keep the shared Sheet2 Agent syntax in sync for every service. Staff Chrome
            // extensions already refresh that central Sheet, so each Agent rule reaches
            // PCs in other locations without replacing their unpacked extension.
            CentralCaseRulesSync.ensureAgentRules(
                    config,
                    allCases.stream()
                            .map(caseRow -> caseRow.serviceName)
                            .filter(Objects::nonNull)
                            .toList()
            );

            List<CaseRow> selectedCases = new ArrayList<>(selectCasesForProcessing(allCases));
            if (JSU_ONLY_RESEND_MODE) {
                selectedCases.removeIf(c -> !isJsuBalanceQueryCase(c));
                System.out.println("JSU-ONLY RESEND MODE | ONLY JSU BALANCE MAIL + WHATSAPP");
            }

            // JSU BALANCE PRIORITY: process JSU Balance Query cases before every
            // other normal case so the balance lookup/mail/WhatsApp starts ASAP.
            // TimSort is stable, so all non-JSU cases keep their existing order.
            selectedCases.sort(Comparator.comparingInt(c -> isJsuBalanceQueryCase(c) ? 0 : 1));
            long jsuPriorityCount = selectedCases.stream().filter(Main::isJsuBalanceQueryCase).count();
            System.out.println("JSU BALANCE PRIORITY | queued first: " + jsuPriorityCount);

            System.out.println("Total cases received: " + allCases.size());
            System.out.println("Cases selected for processing: " + selectedCases.size());

            Map<Long, CustomerDetails> customerCache = new HashMap<>();

            if (!welcomeSheetEnabled) {
                System.out.println("==============================================");
                System.out.println("STARTUP PIPELINE PHASE 2/4 | CURRENT CASES - NORMAL FLOW");
                System.out.println("MAIL + WHATSAPP + AGENT ROUTING RUN AS USUAL; SPFO RULES ARE PRESERVED");
                System.out.println("==============================================");

                processWelcomeCasesWithoutSheet(
                        selectedCases,
                        customerCache
                );

                if (reminderEnabled) {
                    boolean reminderTestMode = Boolean.parseBoolean(
                            config.getProperty(
                                    "reminder.test.mode",
                                    "false"
                            ).trim()
                    );

                    LocalTime nowIndia = LocalTime.now(INDIA_ZONE);
                    LocalTime reminderTime = LocalTime.of(10, 0);

                    if (reminderTestMode || !nowIndia.isBefore(reminderTime)) {
                        System.out.println(
                                reminderTestMode
                                        ? "REMINDER TEST MODE ENABLED - RUNNING IMMEDIATELY"
                                        : "REMINDER STARTUP CATCH-UP CHECK - 10:00 AM HAS PASSED"
                        );
                        processDueReminders();
                    } else {
                        System.out.println(
                                "REMINDER STARTUP CHECK | Before 10:00 AM IST - waiting for scheduled 10:00 AM run"
                        );
                    }
                }

                // Birthday wishes are intentionally disabled.
                System.out.println("==============================================");
                System.out.println("STARTUP PIPELINE PHASE 3/4 | BIRTHDAY DISABLED");
                System.out.println("NO BIRTHDAY WHATSAPP / NO BIRTHDAY REGISTRY SEND");
                System.out.println("==============================================");

                STARTUP_PIPELINE_RUNNING = false;
                System.out.println("STARTUP PIPELINE COMPLETE | SCHEDULED CASE/BIRTHDAY CHECKS RELEASED");

                System.out.println("==============================================");
                System.out.println("STARTUP PIPELINE PHASE 4/4 | HEAVY CUSTOMER BACKFILL DEFERRED");
                System.out.println("FAST START ACTIVE | normal case + SID + SPFO watchers remain available");
                System.out.println("ALL-TIME CUSTOMER DG UPDATE = DAILY 01:30 IST / manual when needed");
                System.out.println("==============================================");

                // Keep startup light. New-customer watcher and daily full backfill remain scheduled.
                return;
            }

            int inserted = 0;
            int updated = 0;
            int skipped = 0;
            int failed = 0;

            for (CaseRow caseRow : selectedCases) {

                // SPFO Balance Error depends on the case remarks.
                // Load remarks from the case-detail API when the list API did not include them.
                caseRow = ensureCaseRemarksLoaded(caseRow);

                // Do not exclude Sweety cases from processing.
                // Their normal candidate welcome email is skipped; WhatsApp still runs.

                // Agent routing applies to EVERY service, independently of the
                // candidate welcome-service allow-list.
                sendAgentWhatsAppIfNeeded(caseRow, customerCache);

                if (!isAllowedService(caseRow.serviceName)) {
                    skipped++;
                    System.out.println(
                            "SKIPPED | " + caseRow.id + " | " + safe(caseRow.serviceName)
                    );
                    continue;
                }

                try {
                    CustomerDetails customer =
                            customerCache.computeIfAbsent(
                                    caseRow.customer,
                                    Main::fetchCustomerDetails
                            );

                    /*
                     * DUPLICATE PHONE RULE
                     *
                     * If this candidate phone number already exists in
                     * "new welcome mail" for ANOTHER case, completely skip:
                     * - NO new welcome sheet entry
                     * - NO welcome mail
                     * - NO WhatsApp
                     *
                     * The same Case ID is allowed to continue so an existing
                     * row can still be updated safely.
                     */
                    String currentPhone =
                            normalizePhone(customer.phone);

                    int duplicatePhoneRow =
                            findWelcomePhoneRowNumber(
                                    currentPhone,
                                    caseRow.id
                            );

                    if (duplicatePhoneRow > 0) {

                        skipped++;

                        System.out.println(
                                "DUPLICATE PHONE SKIPPED"
                                        + " | Case: " + caseRow.id
                                        + " | Phone: " + currentPhone
                                        + " | Existing Row: " + duplicatePhoneRow
                                        + " | NO NEW WELCOME ENTRY"
                                        + " | NO MAIL"
                                        + " | NO WHATSAPP"
                        );

                        continue;
                    }

                    int rowNumber = findWelcomeCaseRowNumber(caseRow.id);

                    String oldMailStatus = "";
                    String oldWhatsAppStatus = "";

                    if (rowNumber > 0) {
                        List<String> statuses = readWelcomeStatuses(rowNumber);
                        oldMailStatus = statuses.get(0);
                        oldWhatsAppStatus = statuses.get(1);

                        updateWelcomeRow(
                                rowNumber,
                                createWelcomeRow(
                                        caseRow,
                                        customer,
                                        oldMailStatus,
                                        oldWhatsAppStatus
                                )
                        );

                        updated++;
                        System.out.println(
                                "WELCOME UPDATED | " + caseRow.id + " | "
                                        + getDisplayServiceName(caseRow.serviceName)
                        );

                    } else {
                        appendWelcomeRow(
                                createWelcomeRow(caseRow, customer, "", "")
                        );

                        rowNumber = findWelcomeCaseRowNumber(caseRow.id);

                        if (rowNumber <= 0) {
                            throw new IOException(
                                    "Could not find newly inserted row for Case ID " + caseRow.id
                            );
                        }

                        inserted++;
                        System.out.println(
                                "WELCOME INSERTED | " + caseRow.id + " | "
                                        + getDisplayServiceName(caseRow.serviceName)
                        );
                    }

                    if (!isMailRequired(caseRow)) {
                        String skippedMailStatus = isSweetyCoordinator(caseRow)
                                ? "SKIPPED - SWEETY"
                                : "NOT REQUIRED - WHATSAPP ONLY";
                        updateWelcomeStatus(
                                rowNumber,
                                "E",
                                skippedMailStatus
                        );
                        System.out.println(
                                (isSweetyCoordinator(caseRow)
                                        ? "MAIL SKIPPED - SWEETY | "
                                        : "MAIL NOT REQUIRED | ")
                                        + caseRow.id
                                        + " | "
                                        + getDisplayServiceName(caseRow.serviceName)
                        );
                    } else if (shouldSendMailForCase(caseRow, oldMailStatus)) {
                        sendMailAndUpdateStatus(caseRow, customer, rowNumber);
                    } else {
                        System.out.println("MAIL ALREADY SENT | " + caseRow.id);
                    }

                    if (shouldSendWhatsAppForCase(caseRow, oldWhatsAppStatus)) {
                        sendWhatsAppAndUpdateStatus(caseRow, customer, rowNumber);
                    } else {
                        System.out.println("WHATSAPP ALREADY SENT | " + caseRow.id);
                    }


                } catch (Exception e) {
                    failed++;
                    System.err.println(
                            "FAILED | Case ID " + caseRow.id + " | " + shortStatus(e.getMessage())
                    );
                }
            }

            System.out.println("==============================================");
            System.out.println("COMPLETED");
            System.out.println("Inserted : " + inserted);
            System.out.println("Updated  : " + updated);
            System.out.println("Skipped  : " + skipped);
            System.out.println("Failed   : " + failed);
            System.out.println("==============================================");

            /*
             * Temporary immediate testing:
             *
             * reminder.test.mode=true
             *
             * runs the due-date reminder check immediately
             * without waiting for 10:00 AM. Change it to
             * false after testing.
             */
            if (Boolean.parseBoolean(
                    config.getProperty(
                            "reminder.test.mode",
                            "false"
                    ).trim()
            )) {

                System.out.println(
                        "REMINDER TEST MODE ENABLED - "
                                + "RUNNING IMMEDIATELY"
                );

                processDueReminders();
            }


        } catch (Exception e) {
            STARTUP_PIPELINE_RUNNING = false;
            System.err.println("Program stopped: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static void processWelcomeCasesWithoutSheet(
            List<CaseRow> selectedCases,
            Map<Long, CustomerDetails> customerCache
    ) throws Exception {
        // IMPORTANT: main() startup, the 5-second scheduler and the local
        // immediate trigger must never process the same cases concurrently.
        // synchronized is re-entrant, so scheduler/trigger callers that already
        // hold WELCOME_POLL_LOCK are safe too.
        synchronized (WELCOME_POLL_LOCK) {
            processWelcomeCasesWithoutSheetLocked(selectedCases, customerCache);
        }
    }

    private static void processWelcomeCasesWithoutSheetLocked(
            List<CaseRow> selectedCases,
            Map<Long, CustomerDetails> customerCache
    ) throws Exception {

        if (isSpfoTestOnlyMode()) {
            System.out.println("SPFO TEST MODE - NORMAL CASE PROCESSING BLOCKED");
            return;
        }

        Properties sentHistory = loadSentHistory();
        Properties attemptHistory = loadAttemptHistory();

        // Normal candidate work is completed first. Today's SPFO cases are queued.
        // Older cases stay queued only when they are SPFO Interest Problem follow-ups.
        List<CaseRow> pendingSpfoCases = new ArrayList<>();

        for (CaseRow originalCase : selectedCases) {
            if (SHUTTING_DOWN) {
                System.out.println("STOPPING - NO MORE CASES WILL BE PROCESSED");
                break;
            }

            CaseRow caseRow = ensureCaseRemarksLoaded(originalCase);
            String assigned = safe(caseRow.assignedToFullName);
            // Sweety cases remain in the flow; candidate welcome mail is skipped, WhatsApp still runs.

            // Agent routing applies to EVERY service, including services with
            // dedicated workflows such as SPFO. Candidate mail/WhatsApp rules
            // remain unchanged.
            sendAgentWhatsAppIfNeeded(caseRow, customerCache);

            // SPFO routing is action-specific:
            // - SPFO Balance: balance/passbook only (no grievance check).
            // - SPFO Interest Problem: initial balance once, then grievance follow-up
            //   Pending -> Closed -> Resolved until the final PDF is forwarded.
            boolean spfoProductionCase = isSpfoBalanceCase(caseRow)
                    || isSpfoBalanceQuery(caseRow.serviceName)
                    || needsSpfoGrievanceFollowUp(caseRow);
            if (spfoProductionCase) {
                if (isCaseStillOpen(caseRow)) {
                    pendingSpfoCases.add(caseRow);
                    boolean spfoInterestProblem = needsSpfoGrievanceFollowUp(caseRow);
                    // Interest Problem is grievance-only: old initial-balance markers must
                    // NOT make the case appear SENT while the grievance is still open.
                    boolean spfoMailSent = spfoInterestProblem
                            ? sentHistory.containsKey(caseRow.id + ".spfo.interest.mail")
                            : sentHistory.containsKey(caseRow.id + ".spfo.balance.mail");
                    boolean spfoWhatsappSent = spfoInterestProblem
                            ? sentHistory.containsKey(caseRow.id + ".spfo.interest.whatsapp")
                            : sentHistory.containsKey(caseRow.id + ".spfo.balance.whatsapp");
                    printCaseSummary(
                            caseRow,
                            spfoMailSent ? "SENT" : "WAITING",
                            spfoWhatsappSent ? "SENT" : "WAITING"
                    );
                }
                continue;
            }

            // Mariners Mentor Membership is a SPECIAL data-first workflow.
            // Never send the ordinary welcome mail/WhatsApp before DG + STCW + SID
            // + 3-sheet/customer-profile update has completed. The membership watcher
            // sends the candidate communication exactly once after the data is ready.
            if (isMmMembershipCase(caseRow)) {
                boolean membershipDone = sentHistory.containsKey(getMmMembershipDoneKey(caseRow));
                String membershipStatus = membershipDone ? "SENT" : "WAITING DATA";
                appendAdminReportRow(
                        null,
                        caseRow,
                        membershipDone ? "SENT" : "WAITING - MEMBERSHIP DATA",
                        membershipDone ? "SENT" : "WAITING - MEMBERSHIP DATA"
                );
                printCaseSummary(caseRow, membershipStatus, membershipStatus);
                continue;
            }

            if (!isAllowedService(caseRow.serviceName)) {
                continue;
            }

            String mailKey = getSentHistoryMailKey(caseRow);
            String whatsappKey = getSentHistoryWhatsAppKey(caseRow);

            boolean manualJsuResend = MANUAL_JSU_RESEND_CASE_IDS.contains(caseRow.id)
                    && isJsuBalanceQueryCase(caseRow);
            String manualMailDoneKey = caseRow.id + ".manual.jsu.balance.mail.done";
            String manualWhatsappDoneKey = caseRow.id + ".manual.jsu.balance.whatsapp.done";
            boolean forceManualMail = manualJsuResend && !sentHistory.containsKey(manualMailDoneKey);
            boolean forceManualWhatsapp = manualJsuResend && !sentHistory.containsKey(manualWhatsappDoneKey);

            boolean forceTestRepeat = (JSU_ONLY_RESEND_MODE && isJsuBalanceQueryCase(caseRow))
                    || (TEST_REPEAT_CASE_IDS.contains(caseRow.id)
                    && TEST_REPEATED_THIS_RUN.add(caseRow.id));

            boolean mailRequired = isMailRequired(caseRow);
            boolean mailDone = !mailRequired
                    || (!forceTestRepeat && !forceManualMail && sentHistory.containsKey(mailKey));
            boolean whatsappDone = !forceTestRepeat
                    && !forceManualWhatsapp
                    && sentHistory.containsKey(whatsappKey);

            AgentContact agent = resolveAgentContact(caseRow);
            boolean agentDone = agent == null
                    || sentHistory.containsKey(getAgentSentHistoryKey(caseRow, agent))
                    || wasEverAttempted(
                    loadAttemptHistory(),
                    caseRow,
                    getAgentAttemptChannel(agent)
            );

            if (mailDone && whatsappDone && agentDone) {
                printCaseSummary(
                        caseRow,
                        mailRequired ? "SENT" : "N/A",
                        "SENT"
                );
                continue;
            }

            CustomerDetails customer;
            try {
                customer = customerCache.computeIfAbsent(
                        caseRow.customer,
                        Main::fetchCustomerDetails
                );
            } catch (Exception e) {
                appendAdminReportRow(
                        null,
                        caseRow,
                        "FAILED - CUSTOMER DETAILS",
                        "FAILED - CUSTOMER DETAILS"
                );
                System.err.println(
                        "CUSTOMER DETAILS FAILED | Case: " + caseRow.id
                                + " | " + e.getMessage()
                );
                continue;
            }

            String mailStatus = isSweetyCoordinator(caseRow)
                    ? "-"
                    : "NOT REQUIRED";
            if (mailRequired) {
                if (!forceTestRepeat && !forceManualMail && sentHistory.containsKey(mailKey)) {
                    mailStatus = "SKIPPED - ALREADY SENT";
                } else if (isBlank(customer.email)) {
                    mailStatus = "FAILED - EMAIL MISSING";
                } else {
                    String mailAttemptChannel = isJsuBalanceQueryCase(caseRow)
                            ? "jsu.balance.mail"
                            : "mail";
                    boolean mailClaimed = forceTestRepeat
                            || forceManualMail
                            || claimAttemptOnce(caseRow, mailAttemptChannel);

                    if (!mailClaimed) {
                        mailStatus = "BLOCKED - ALREADY CLAIMED/ATTEMPTED (NO RESEND)";
                    } else {
                        try {
                            sendCaseMail(caseRow, customer, assigned);
                            mailStatus = isJsuBalanceQueryCase(caseRow)
                                    ? "SENT - JSU BALANCE"
                                    : "SENT - ZOHO";
                            rememberSent(sentHistory, mailKey);
                            if (forceManualMail) {
                                rememberSent(sentHistory, manualMailDoneKey);
                                System.out.println("MANUAL JSU RESEND MAIL COMPLETED | Case: " + caseRow.id);
                            }
                        } catch (Exception e) {
                            mailStatus = shortStatus("FAILED - LOCKED NO RESEND - " + e.getMessage());
                            System.err.println(
                                    "MAIL FAILED - PERMANENTLY LOCKED AGAINST AUTO RESEND"
                                            + " | Case: " + caseRow.id
                                            + " | " + e.getMessage()
                            );
                        }
                    }
                }
            }

            String whatsappStatus;
            String phone = normalizePhone(customer.phone);

            if (!forceTestRepeat && !forceManualWhatsapp && sentHistory.containsKey(whatsappKey)) {
                whatsappStatus = "SKIPPED - ALREADY SENT";
            } else if (isBlank(phone)) {
                whatsappStatus = "FAILED - PHONE MISSING";
            } else {
                String whatsappAttemptChannel = isJsuBalanceQueryCase(caseRow)
                        ? "jsu.balance.whatsapp"
                        : "whatsapp";
                boolean whatsappClaimed = forceTestRepeat
                        || forceManualWhatsapp
                        || claimAttemptOnce(caseRow, whatsappAttemptChannel);

                if (!whatsappClaimed) {
                    whatsappStatus = "BLOCKED - ALREADY CLAIMED/ATTEMPTED (NO RESEND)";
                } else {
                    try {
                        sendCaseWhatsApp(caseRow, customer, phone, assigned);
                        whatsappStatus = isJsuBalanceQueryCase(caseRow)
                                ? "SENT - JSU BALANCE"
                                : (isJsuRetirementCase(caseRow)
                                   ? "SENT - JSU OPEN MAIL"
                                   : "ACCEPTED BY META");
                        rememberSent(sentHistory, whatsappKey);
                        if (forceManualWhatsapp) {
                            rememberSent(sentHistory, manualWhatsappDoneKey);
                            System.out.println("MANUAL JSU RESEND WHATSAPP COMPLETED | Case: " + caseRow.id);
                        }
                    } catch (Exception e) {
                        whatsappStatus = shortStatus("FAILED - LOCKED NO RESEND - " + e.getMessage());
                        System.err.println(
                                "WHATSAPP FAILED - PERMANENTLY LOCKED AGAINST AUTO RESEND"
                                        + " | Case: " + caseRow.id
                                        + " | " + e.getMessage()
                        );
                    }
                }
            }


            appendAdminReportRow(null, caseRow, mailStatus, whatsappStatus);

            printCaseSummary(caseRow, mailStatus, whatsappStatus);
        }

        // Process SPFO only after normal cases so a manual CAPTCHA wait never blocks
        // the ordinary welcome/reminder flow. Only open SPFO Interest Problem cases
        // remain selected on future dates until the grievance is resolved.
        processPendingSpfoCases(pendingSpfoCases, customerCache);

    }

    /**
     * Unified SPFO production dispatcher.
     *
     * Rules:
     * 1) Every NEW SPFO case created today is picked automatically.
     * 2) SPFO BALANCE is balance/passbook only. It NEVER opens grievance tabs.
     * 3) Only SPFO INTEREST PROBLEM continues as an older OPEN follow-up case.
     * 4) For SPFO INTEREST PROBLEM, INITIAL BALANCE is sent at most once, then
     *    Pending -> Closed -> Resolution History is checked until Resolved.
     * 5) A Closed grievance is accepted only when Resolution History shows Resolved.
     * 6) The resolved PDF is emailed; BALANCE extracted from that PDF goes to WhatsApp.
     * 7) Until Resolved is found, the Interest Problem MM case stays open and is checked again.
     */
    private static void processPendingSpfoCases(
            List<CaseRow> spfoCases,
            Map<Long, CustomerDetails> customerCache
    ) {
        if (spfoCases == null || spfoCases.isEmpty() || SHUTTING_DOWN) {
            return;
        }

        long now = System.currentTimeMillis();
        long recheckMillis = getSpfoRecheckMillis();

        for (CaseRow caseRow : spfoCases) {
            if (caseRow == null || caseRow.id <= 0 || SHUTTING_DOWN) {
                continue;
            }

            try {
                caseRow = ensureCaseRemarksLoaded(caseRow);
                if (!isCaseStillOpen(caseRow)) {
                    continue;
                }

                CustomerDetails customer = customerCache.computeIfAbsent(
                        caseRow.customer,
                        Main::fetchCustomerDetails
                );

                boolean grievanceFollowUp = needsSpfoGrievanceFollowUp(caseRow);
                Properties sent = loadSentHistory();
                Properties attempts = loadAttemptHistory();

                if (sent.containsKey(caseRow.id + ".spfo.mm.case.closed")) {
                    continue;
                }

                // SPFO Interest Problem is grievance-only. It must not send an initial
                // balance. SPFO Balance keeps the existing one-time balance workflow.
                boolean balanceDone = grievanceFollowUp || isSpfoInitialBalanceStageComplete(
                        caseRow, customer, sent, attempts
                );
                boolean finalDone = !grievanceFollowUp
                        || isSpfoFinalInterestStageComplete(caseRow, customer, sent);

                // SPFO Balance ends after the one-time balance has been successfully
                // forwarded. Do not reopen SPFO and do not check grievance for it.
                if (!grievanceFollowUp && balanceDone) {
                    printCaseSummary(caseRow, "SENT", "SENT");
                    continue;
                }

                if (grievanceFollowUp && balanceDone && finalDone) {
                    printCaseSummary(caseRow, "SENT", "SENT");
                    closeMarinersMentorCaseAtMostOnce(caseRow);
                    continue;
                }

                // SPFO Interest Problem: while the grievance remains OPEN, only CHECK
                // SPFO once every configured interval (180 minutes by default). Do not
                // send the candidate anything during an open/pending grievance.

                Long lastAttempt = SPFO_LAST_WORKFLOW_ATTEMPT_MS.get(caseRow.id);
                if (lastAttempt != null && now - lastAttempt < recheckMillis) {
                    long waitSeconds = Math.max(1L, (recheckMillis - (now - lastAttempt)) / 1000L);
                    continue;
                }

                SPFO_LAST_WORKFLOW_ATTEMPT_MS.put(caseRow.id, now);

                System.out.println(
                        "SPFO | Case: " + caseRow.id
                                + " | " + (grievanceFollowUp ? "INTEREST CHECK" : "BALANCE CHECK")
                                + " | TYPE CAPTCHA AND CLICK LOGIN"
                );

                if (grievanceFollowUp) {
                    runSpfoInterestProductionCase(caseRow.id);
                } else {
                    runSpfoBalanceProductionCase(caseRow.id);
                }

            } catch (Exception e) {
                System.err.println(
                        "SPFO FAILED | Case: " + caseRow.id
                                + " | " + shortStatus(e.getMessage())
                );
            }
        }
    }

    private static long getSpfoRecheckMillis() {
        long minutes = 180L;
        try {
            minutes = Long.parseLong(
                    config.getProperty("spfo.recheck.minutes", "180").trim()
            );
        } catch (Exception ignored) {
        }
        // Never allow the 5-second scheduler to open SPFO browsers continuously.
        minutes = Math.max(5L, minutes);
        return Duration.ofMinutes(minutes).toMillis();
    }

    private static boolean isSpfoInitialBalanceStageComplete(
            CaseRow caseRow,
            CustomerDetails customer,
            Properties sent,
            Properties attempts
    ) {
        boolean emailRequired = customer != null && !isBlank(customer.email);
        boolean whatsappRequired = customer != null
                && !isBlank(normalizePhone(customer.phone));

        // IMPORTANT: an ATTEMPT is not the same as a successful send.
        // The initial balance stage is complete only when each required channel
        // has a confirmed SENT marker. This prevents the bot from moving straight
        // to grievance after a failed/unfinished balance send.
        boolean emailDone = !emailRequired
                || sent.containsKey(caseRow.id + ".spfo.balance.mail");
        boolean whatsappDone = !whatsappRequired
                || sent.containsKey(caseRow.id + ".spfo.balance.whatsapp");

        return emailDone && whatsappDone;
    }

    private static boolean isSpfoFinalInterestStageComplete(
            CaseRow caseRow,
            CustomerDetails customer,
            Properties sent
    ) {
        if (caseRow == null || customer == null
                || isBlank(customer.email)
                || isBlank(normalizePhone(customer.phone))) {
            return false;
        }

        return sent.containsKey(caseRow.id + ".spfo.interest.mail")
                && sent.containsKey(caseRow.id + ".spfo.interest.whatsapp");
    }

    /*
     * Automatically checks the central Mariners Mentor Cases API every 5 seconds.
     * Only this one MMCaseBot needs to remain running, even when staff create cases
     * from PCs on other networks. Sent-history prevents repeat email and WhatsApp sends.
     */
    @Scheduled(fixedDelay = 5000, initialDelay = 5000)
    public void pollForNewWelcomeCases() {
        if (isCustomerOnlyMode()) return;
        synchronized (WELCOME_POLL_LOCK) {
            try {
                if (SHUTTING_DOWN || config == null) {
                    return;
                }

                if (STARTUP_PIPELINE_RUNNING) {
                    return;
                }

                // HARD SAFETY: Spring scheduling may already be alive while a manual
                // SPFO CAPTCHA test is running. Never process normal cases in this mode.
                if (isSpfoTestOnlyMode()) {
                    return;
                }

                if (isRailwayRuntime() && !isRailwayCaseProcessingEnabled()) {
                    return;
                }

                boolean welcomeSheetEnabled = Boolean.parseBoolean(
                        config.getProperty(
                                "welcome.sheet.enabled",
                                "false"
                        ).trim()
                );

                if (welcomeSheetEnabled) {
                    return;
                }

                List<CaseRow> selectedCases =
                        selectCasesForProcessing(fetchCases());

                processWelcomeCasesWithoutSheet(
                        selectedCases,
                        new HashMap<>()
                );
            } catch (Exception e) {
                System.err.println(
                        "AUTOMATIC WELCOME CHECK FAILED | " + e.getMessage()
                );
            }
        }
    }

    /**
     * Atomically claims a case/channel BEFORE any network send.
     *
     * Why this exists:
     * 1) main() and the scheduler used to be able to reach the same case together.
     * 2) two MMcasesBot Java processes on the same PC can have separate JVM locks.
     *
     * Files.createFile() gives us an atomic permanent marker on this filesystem.
     * Once the claim exists, that case/channel is never automatically sent again.
     */
    private static boolean claimAttemptOnce(
            CaseRow caseRow,
            String channel
    ) throws IOException {

        String key = getAttemptHistoryKey(caseRow, channel);

        synchronized (SENT_HISTORY_LOCK) {
            ensurePersistentStateDir();
            Files.createDirectories(ATTEMPT_CLAIMS_DIR);

            // Respect all old attempt-history entries too.
            Properties latestAttempts = loadPersistentHistory(
                    ATTEMPT_HISTORY_FILE,
                    LEGACY_ATTEMPT_HISTORY_FILE,
                    "MMcasesBot permanent attempt history - do not delete"
            );

            if (wasEverAttempted(latestAttempts, caseRow, channel)) {
                return false;
            }

            String safeName = key.replaceAll("[^A-Za-z0-9._-]", "_");
            Path claimFile = ATTEMPT_CLAIMS_DIR.resolve(safeName + ".claim");

            try {
                // Atomic across threads AND separate Java processes that use the
                // same BOT_STATE_DIR on this PC/filesystem.
                Files.createFile(claimFile);
            } catch (java.nio.file.FileAlreadyExistsException alreadyClaimed) {
                return false;
            }

            String timestamp = LocalDateTime.now(INDIA_ZONE).format(
                    DateTimeFormatter.ofPattern("dd/MM/yyyy hh:mm:ss a")
            );
            latestAttempts.setProperty(key, timestamp);

            try (java.io.OutputStream output = Files.newOutputStream(ATTEMPT_HISTORY_FILE)) {
                latestAttempts.store(
                        output,
                        "MMcasesBot permanent attempt history - do not delete"
                );
            } catch (IOException historyWriteError) {
                // The .claim file already safely prevents a duplicate. Do not
                // remove it just because the readable properties mirror failed.
                System.err.println(
                        "WARNING: attempt history mirror write failed, atomic claim retained"
                                + " | " + historyWriteError.getMessage()
                );
            }

            System.out.println(
                    "ATOMIC SEND CLAIM CREATED"
                            + " | Case: " + caseRow.id
                            + " | Channel: " + channel
            );

            return true;
        }
    }

    private static Properties loadAttemptHistory() throws IOException {
        synchronized (SENT_HISTORY_LOCK) {
            return loadPersistentHistory(
                    ATTEMPT_HISTORY_FILE,
                    LEGACY_ATTEMPT_HISTORY_FILE,
                    "MMcasesBot permanent attempt history - do not delete"
            );
        }
    }

    private static String getAttemptHistoryKey(CaseRow caseRow, String channel) {
        // PERMANENT AT-MOST-ONCE GUARD.
        // Do not use the date here: a timeout after the provider accepted a message
        // must never cause an automatic resend tomorrow or after a restart.
        return caseRow.id + "." + safe(channel);
    }

    private static boolean wasEverAttempted(
            Properties history,
            CaseRow caseRow,
            String channel
    ) {
        String permanentKey = getAttemptHistoryKey(caseRow, channel);
        if (history.containsKey(permanentKey)) {
            return true;
        }

        try {
            String safeName = permanentKey.replaceAll("[^A-Za-z0-9._-]", "_");
            if (Files.exists(ATTEMPT_CLAIMS_DIR.resolve(safeName + ".claim"))) {
                return true;
            }
        } catch (Exception ignored) {
            // Fall back to properties history below.
        }

        // Backward compatibility with older daily keys such as
        // 20260828.7058.mail / 20260828.7058.whatsapp.
        String suffix = "." + caseRow.id + "." + safe(channel);
        for (Object keyObject : history.keySet()) {
            if (String.valueOf(keyObject).endsWith(suffix)) {
                return true;
            }
        }
        return false;
    }

    private static Properties loadPersistentHistory(
            Path persistentFile,
            Path legacyFile,
            String comment
    ) throws IOException {
        ensurePersistentStateDir();

        Properties history = new Properties();
        if (Files.exists(persistentFile)) {
            try (InputStream input = Files.newInputStream(persistentFile)) {
                history.load(input);
            }
        }

        // One-time/ongoing safe migration from older ZIP/project-local history.
        // Existing persistent values win; legacy values only fill missing keys.
        boolean changed = false;
        Path legacyAbsolute = legacyFile.toAbsolutePath().normalize();
        Path persistentAbsolute = persistentFile.toAbsolutePath().normalize();
        if (!legacyAbsolute.equals(persistentAbsolute) && Files.exists(legacyFile)) {
            Properties legacy = new Properties();
            try (InputStream input = Files.newInputStream(legacyFile)) {
                legacy.load(input);
            }
            for (String key : legacy.stringPropertyNames()) {
                if (!history.containsKey(key)) {
                    history.setProperty(key, legacy.getProperty(key));
                    changed = true;
                }
            }
        }

        if (changed || !Files.exists(persistentFile)) {
            try (java.io.OutputStream output = Files.newOutputStream(persistentFile)) {
                history.store(output, comment);
            }
        }
        return history;
    }

    private static void rememberAttempt(
            Properties history,
            String key
    ) throws IOException {
        synchronized (SENT_HISTORY_LOCK) {
            ensurePersistentStateDir();
            history.setProperty(
                    key,
                    LocalDateTime.now(INDIA_ZONE).format(
                            DateTimeFormatter.ofPattern("dd/MM/yyyy hh:mm:ss a")
                    )
            );
            try (java.io.OutputStream output = Files.newOutputStream(ATTEMPT_HISTORY_FILE)) {
                history.store(output, "MMcasesBot permanent attempt history - do not delete");
            }
        }
    }

    private static void forgetAttempt(
            Properties history,
            String key
    ) throws IOException {
        synchronized (SENT_HISTORY_LOCK) {
            ensurePersistentStateDir();

            // Remove the current permanent key, for example:
            //   7394.spfo.balance.whatsapp
            // and ALSO any older daily-format key, for example:
            //   20260830.7394.spfo.balance.whatsapp
            // Otherwise an old key can keep the case blocked after a confirmed
            // Meta rejection (132000 / 132001).
            java.util.List<String> keysToRemove = new java.util.ArrayList<>();
            String legacySuffix = "." + key;
            for (Object keyObject : history.keySet()) {
                String existingKey = String.valueOf(keyObject);
                if (existingKey.equals(key) || existingKey.endsWith(legacySuffix)) {
                    keysToRemove.add(existingKey);
                }
            }
            for (String removeKey : keysToRemove) {
                history.remove(removeKey);
            }

            try {
                Files.createDirectories(ATTEMPT_CLAIMS_DIR);
                String safeName = key.replaceAll("[^A-Za-z0-9._-]", "_");
                Files.deleteIfExists(ATTEMPT_CLAIMS_DIR.resolve(safeName + ".claim"));
            } catch (Exception e) {
                System.err.println("WARNING: could not remove attempt claim | " + e.getMessage());
            }

            try (java.io.OutputStream output = Files.newOutputStream(ATTEMPT_HISTORY_FILE)) {
                history.store(output, "MMcasesBot permanent attempt history - do not delete");
            }

            // Important: this project has backward-compatibility migration from the
            // old project-local attempted-welcome-cases.properties file. If the old
            // entry is left there it can be copied back into the permanent state on
            // the next restart. Remove only this channel from that legacy file too.
            try {
                if (Files.exists(LEGACY_ATTEMPT_HISTORY_FILE)) {
                    Properties legacy = new Properties();
                    try (InputStream input = Files.newInputStream(LEGACY_ATTEMPT_HISTORY_FILE)) {
                        legacy.load(input);
                    }

                    java.util.List<String> legacyKeysToRemove = new java.util.ArrayList<>();
                    for (Object keyObject : legacy.keySet()) {
                        String existingKey = String.valueOf(keyObject);
                        if (existingKey.equals(key) || existingKey.endsWith(legacySuffix)) {
                            legacyKeysToRemove.add(existingKey);
                        }
                    }
                    if (!legacyKeysToRemove.isEmpty()) {
                        for (String removeKey : legacyKeysToRemove) {
                            legacy.remove(removeKey);
                        }
                        try (java.io.OutputStream output = Files.newOutputStream(LEGACY_ATTEMPT_HISTORY_FILE)) {
                            legacy.store(output, "MMcasesBot legacy attempt history");
                        }
                    }
                }
            } catch (Exception e) {
                System.err.println(
                        "WARNING: could not clean legacy attempt history | " + e.getMessage()
                );
            }
        }
    }

    private static Properties loadSentHistory() throws IOException {
        synchronized (SENT_HISTORY_LOCK) {
            return loadPersistentHistory(
                    SENT_HISTORY_FILE,
                    LEGACY_SENT_HISTORY_FILE,
                    "MMcasesBot sent history - do not delete"
            );
        }
    }

    private static void rememberSent(
            Properties history,
            String key
    ) throws IOException {
        synchronized (SENT_HISTORY_LOCK) {
            ensurePersistentStateDir();
            history.setProperty(
                    key,
                    LocalDateTime.now(INDIA_ZONE).format(
                            DateTimeFormatter.ofPattern("dd/MM/yyyy hh:mm:ss a")
                    )
            );

            try (java.io.OutputStream output = Files.newOutputStream(SENT_HISTORY_FILE)) {
                history.store(output, "MMcasesBot sent history - do not delete");
            }
        }
    }

    private static void appendAdminReportRow(
            StringBuilder rows,
            CaseRow caseRow,
            String mailStatus,
            String whatsappStatus
    ) {
        if (JSU_ONLY_RESEND_MODE || caseRow == null) { return; }

        // Report only these coordinators. Processing itself is NOT changed.
        String assigned = safe(caseRow.assignedToFullName)
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z]", "");
        boolean allowedCoordinator = assigned.contains("jeya")
                || assigned.contains("jaya")
                || assigned.contains("priya")
                || assigned.contains("sugayna")
                || assigned.contains("salo")
                || assigned.contains("subha");
        if (!allowedCoordinator) { return; }

        // Never show Sweety, Mariners Mentor Membership, or CDC services in this report.
        if (isSweetyCoordinator(caseRow)) { return; }
        String service = normalizeServiceName(getCaseDisplayServiceName(caseRow));
        if (service.contains("MARINERS MENTOR MEMBERSHIP")
                || service.contains("MEMBERSHIP")
                || service.contains("CDC")) {
            return;
        }

        ReminderInfo reminder = parseReminder(caseRow.remarks);
        String reminderStatus = reminder == null
                ? "NO"
                : "SET " + reminder.eventDate().format(REMINDER_DATE_FORMAT);

        String rowHtml = "<tr>"
                + reportCell(String.valueOf(caseRow.id))
                + reportCell(safe(caseRow.createdBy).isBlank() ? "-" : caseRow.createdBy)
                + reportCell(compactReportStatus(whatsappStatus))
                + reportCell(reminderStatus)
                + reportCell(compactReportStatus(mailStatus))
                + "</tr>";

        if (rows != null) {
            rows.append(rowHtml);
        }
        synchronized (ADMIN_REPORT_LOCK) {
            PENDING_ADMIN_REPORT_ROWS.put(caseRow.id, rowHtml);
        }
    }

    private static String reportCell(String value) {
        return "<td style='padding:8px'>"
                + safe(value)
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;")
                + "</td>";
    }

    private static String compactReportStatus(String status) {
        String value = safe(status).trim();
        if (value.isBlank() || value.equalsIgnoreCase("PENDING")) return "FAILED(unknown)";
        String upper = value.toUpperCase(Locale.ROOT);
        if (upper.contains("SENT")
                || upper.contains("SUCCESS")
                || upper.contains("ACCEPTED BY META")) return "SENT";
        if (upper.contains("FAILED") || upper.contains("ERROR")) {
            String reason = value.replaceFirst("(?i)^.*?(FAILED|ERROR)\\s*[-:|]*\\s*", "").trim();
            reason = shortStatus(reason);
            if (reason.isBlank()) reason = "unknown";
            return "FAILED(" + reason + ")";
        }
        return value;
    }

    private void sendScheduledAdminReport(String slot) {
        if (isCustomerOnlyMode() || JSU_ONLY_RESEND_MODE || isSpfoTestOnlyMode() || config == null) return;
        String combinedRows;
        synchronized (ADMIN_REPORT_LOCK) {
            StringBuilder rows = new StringBuilder();
            for (String row : PENDING_ADMIN_REPORT_ROWS.values()) rows.append(row);
            combinedRows = rows.toString();
        }
        if (combinedRows.isBlank()) {
            System.out.println("ADMIN REPORT " + slot + " | NO CASE RESULTS");
            return;
        }
        try {
            ZohoMailService.sendAdminWelcomeReport(config, combinedRows);
            synchronized (ADMIN_REPORT_LOCK) { PENDING_ADMIN_REPORT_ROWS.clear(); }
            System.out.println("ADMIN REPORT SENT | " + slot);
        } catch (Exception e) {
            System.err.println("ADMIN REPORT FAILED | " + slot + " | " + shortStatus(e.getMessage()));
        }
    }

    @Scheduled(cron = "0 0 14 * * *", zone = "Asia/Kolkata")
    public void sendAdminReportAt2Pm() { sendScheduledAdminReport("2:00 PM"); }

    @Scheduled(cron = "0 30 18 * * *", zone = "Asia/Kolkata")
    public void sendAdminReportAt630Pm() { sendScheduledAdminReport("6:30 PM"); }

    // Shutdown hook remains only as a safety net for unsent rows after the last scheduled report.

    @PreDestroy
    public void sendCombinedAdminReportOnStop() {
        SHUTTING_DOWN = true;
        if (JSU_ONLY_RESEND_MODE) {
            System.out.println("JSU-ONLY RESEND MODE | ADMIN REPORT DISABLED");
            return;
        }

        if (config == null) {
            return;
        }

        if (isSpfoTestOnlyMode()) {
            System.out.println("SPFO TEST MODE - FINAL ADMIN EMAIL BLOCKED");
            return;
        }

        // Customer-only build: never send the normal case/admin report.
        // If the user stops during the long All-Time run, send the latest
        // customer credential table gathered so far instead.
        if (isCustomerOnlyMode()) {
            if (CUSTOMER_PROFILE_SYNC_RUNNING.get()) {
                CustomerProfileBackfillService.SyncReport partial =
                        CustomerProfileBackfillService.getLastPartialReport();
                if (partial != null) {
                    try {
                        ZohoMailService.sendCustomerProfileSyncReport(
                                config, "STOPPED / PARTIAL", partial
                        );
                        System.out.println("CUSTOMER PARTIAL TABLE REPORT SENT ON STOP");
                    } catch (Exception partialTableError) {
                        System.err.println("CUSTOMER PARTIAL TABLE REPORT FAILED | "
                                + safe(partialTableError.getMessage()));
                    }
                } else if (LAST_CUSTOMER_SYNC_PROGRESS != null) {
                    try {
                        ZohoMailService.sendCustomerProfileProgressReport(
                                config, "STOPPED / PARTIAL", "STOPPED / PARTIAL", LAST_CUSTOMER_SYNC_PROGRESS
                        );
                    } catch (Exception ignored) {}
                }
            }
            System.out.println("CUSTOMER-ONLY BOT STOPPED - NORMAL CASE ADMIN REPORT NOT SENT");
            return;
        }

        if (CUSTOMER_PROFILE_SYNC_RUNNING.get() && LAST_CUSTOMER_SYNC_PROGRESS != null
                && Boolean.parseBoolean(config.getProperty(
                "customer.profile.sync.progress.mail.enabled", "false").trim())) {
            try {
                ZohoMailService.sendCustomerProfileProgressReport(
                        config, "STARTUP", "STOPPED / PARTIAL", LAST_CUSTOMER_SYNC_PROGRESS
                );
            } catch (Exception partialReportError) {
                System.err.println(
                        "CUSTOMER PROFILE PARTIAL SHUTDOWN REPORT FAILED | "
                                + safe(partialReportError.getMessage())
                );
            }
        }

        String combinedRows;

        synchronized (ADMIN_REPORT_LOCK) {
            StringBuilder rows = new StringBuilder();
            for (String row : PENDING_ADMIN_REPORT_ROWS.values()) {
                rows.append(row);
            }
            combinedRows = rows.toString();
        }

        if (combinedRows.isBlank()) {
            System.out.println(
                    "PROGRAM STOPPED - NO ADMIN REPORT ROWS TO SEND"
            );
            return;
        }

        try {
            System.out.println(
                    "PROGRAM STOPPING - SENDING ONE COMBINED ADMIN REPORT..."
            );

            ZohoMailService.sendAdminWelcomeReport(
                    config,
                    combinedRows
            );

            synchronized (ADMIN_REPORT_LOCK) {
                PENDING_ADMIN_REPORT_ROWS.clear();
            }

            System.out.println(
                    "PROGRAM STOPPED - FINAL ADMIN REPORT SENT TO marinersmentor@gmail.com"
            );
        } catch (Exception e) {
            System.err.println(
                    "FINAL ADMIN REPORT SEND FAILED | " + e.getMessage()
            );
        }
    }

    /*
     * Keeps a persistent birthday registry from Mariners Mentor cases.
     * MMcasesBot itself reads this registry and sends today's birthday wishes.
     */

    /*
     * Birthday wishes from case-linked customers.
     *
     * Browser stays hidden:
     *   1. MMcasesBot logs into Mariners Mentor dashboard using headless Chrome.
     *   2. It fetches the current case list and refreshes the Birthday Cases registry.
     *   3. It checks the full registry for birthdays matching today's day/month.
     *   4. It sends the approved birthday WhatsApp template with a generated poster.
     *   5. It records a daily phone-level sent key so the same person is not messaged twice.
     */
    @Scheduled(fixedDelay = 300000, initialDelay = 20000)
    public void sendTodayBirthdayWishesScheduled() {
        if (isCustomerOnlyMode()) return;

        synchronized (WELCOME_POLL_LOCK) {

            try {

                if (SHUTTING_DOWN
                        || config == null) {
                    return;
                }

                if (STARTUP_PIPELINE_RUNNING) {
                    return;
                }

                if (!Boolean.parseBoolean(
                        config.getProperty(
                                "birthday.auto.enabled",
                                "true"
                        ).trim()
                )) {
                    return;
                }

                if (isSpfoTestOnlyMode()) {
                    return;
                }

                if (isRailwayRuntime()
                        && !isRailwayCaseProcessingEnabled()) {
                    return;
                }

                /*
                 * get/fetch below automatically performs the hidden dashboard
                 * login when a token is missing or expired.
                 */
                // Keep the five-minute birthday sender lightweight.
                // Birthday registry synchronization has its own scheduled job; doing the
                // full all-time sync here as well caused duplicate customer/API work and
                // could make a normal 16 GB office PC feel frozen.
                sendTodayBirthdayWishesFromRegistry();

            } catch (Exception e) {

                System.err.println(
                        "BIRTHDAY AUTO CHECK FAILED | "
                                + safe(
                                        e.getMessage()
                                )
                );
            }
        }
    }

    private static void sendTodayBirthdayWishesFromRegistry()
            throws Exception {

        if (sheetsService == null) {

            System.err.println(
                    "BIRTHDAY AUTO CHECK SKIPPED - Google Sheets service is not available."
            );

            return;
        }

        ensureBirthdayCaseSheetExists();

        ValueRange response =
                sheetsService.spreadsheets()
                        .values()
                        .get(
                                SPREADSHEET_ID,
                                quoteSheetName(
                                        BIRTHDAY_CASE_SHEET
                                ) + "!A2:F"
                        )
                        .execute();

        List<List<Object>> rows =
                response.getValues();

        LocalDate today =
                LocalDate.now(
                        INDIA_ZONE
                );

        String dateKey =
                today.format(
                        DateTimeFormatter.BASIC_ISO_DATE
                );

        Properties sentHistory =
                loadSentHistory();

        Set<String> checkedPhones =
                new HashSet<>();

        int birthdayCount = 0;
        int sentCount = 0;
        int alreadySentCount = 0;
        int failedCount = 0;

        if (rows != null) {

            for (List<Object> row : rows) {

                if (row == null
                        || row.size() < 4) {
                    continue;
                }

                String customerId =
                        safe(
                                row.get(0)
                        ).trim();

                String name =
                        safe(
                                row.get(1)
                        )
                                .replaceAll(
                                        "\\s+",
                                        " "
                                )
                                .trim();

                String rawDob =
                        safe(
                                row.get(2)
                        ).trim();

                String phone =
                        normalizePhone(
                                safe(
                                        row.get(3)
                                )
                        );

                LocalDate dob =
                        parseFlexibleDob(
                                rawDob
                        );

                if (dob == null
                        || dob.getDayOfMonth()
                        != today.getDayOfMonth()
                        || dob.getMonthValue()
                        != today.getMonthValue()) {

                    continue;
                }

                birthdayCount++;

                if (name.isBlank()) {
                    name = "Seafarer";
                }

                if (phone.isBlank()) {

                    failedCount++;

                    System.err.println(
                            "BIRTHDAY WHATSAPP SKIPPED - PHONE MISSING"
                                    + " | Customer: "
                                    + customerId
                                    + " | Name: "
                                    + name
                    );

                    continue;
                }

                if (!checkedPhones.add(phone)) {
                    continue;
                }

                String sentKey =
                        "birthday."
                                + dateKey
                                + "."
                                + phone;

                if (sentHistory.containsKey(
                        sentKey
                )) {

                    alreadySentCount++;

                    System.out.println(
                            "BIRTHDAY ALREADY SENT TODAY"
                                    + " | Name: "
                                    + name
                                    + " | Phone: "
                                    + phone
                    );

                    continue;
                }

                try {

                    WhatsAppService.sendBirthdayWish(
                            config,
                            phone,
                            name,
                            today
                    );

                    rememberSent(
                            sentHistory,
                            sentKey
                    );

                    sentCount++;

                } catch (Exception sendError) {

                    failedCount++;

                    System.err.println(
                            "BIRTHDAY WHATSAPP FAILED"
                                    + " | Name: "
                                    + name
                                    + " | Phone: "
                                    + phone
                                    + " | "
                                    + safe(
                                            sendError.getMessage()
                                    )
                    );
                }
            }
        }

        System.out.println(
                "BIRTHDAY CHECK COMPLETED"
                        + " | Date: "
                        + today.format(
                                DateTimeFormatter.ofPattern(
                                        "dd/MM/yyyy"
                                )
                        )
                        + " | Birthdays found: "
                        + birthdayCount
                        + " | Sent: "
                        + sentCount
                        + " | Already sent: "
                        + alreadySentCount
                        + " | Failed: "
                        + failedCount
        );
    }


    @Scheduled(fixedDelay = 600000, initialDelay = 600000)
    public void syncBirthdayCasesScheduled() {
        if (isCustomerOnlyMode()) return;

        synchronized (WELCOME_POLL_LOCK) {

            try {

                if (SHUTTING_DOWN || config == null || sheetsService == null) {
                    return;
                }

                if (STARTUP_PIPELINE_RUNNING) {
                    return;
                }

                if (isSpfoTestOnlyMode()) {
                    return;
                }

                if (isRailwayRuntime() && !isRailwayCaseProcessingEnabled()) {
                    return;
                }

                boolean enabled =
                        Boolean.parseBoolean(
                                config.getProperty(
                                        "birthday.case.sync.enabled",
                                        "true"
                                ).trim()
                        );

                if (!enabled) {
                    return;
                }

                syncBirthdayCasesFromCases(
                        fetchAllCasesForBirthday()
                );

            } catch (Exception e) {

                System.err.println(
                        "BIRTHDAY CASE SYNC FAILED | "
                                + e.getMessage()
                );
            }
        }
    }




    private static CustomerProfileBackfillService.SyncReport runCustomerProfileBackfillBlocking(
            String reason,
            List<CaseRow> customerSourceCases
    ) {

        if (config == null || SHUTTING_DOWN || isSpfoTestOnlyMode()) {
            return null;
        }

        if (isRailwayRuntime()) {
            return null;
        }

        if (!Boolean.parseBoolean(
                config.getProperty("customer.profile.sync.enabled", "true").trim())) {
            return null;
        }

        if (!CUSTOMER_PROFILE_SYNC_RUNNING.compareAndSet(false, true)) {
            System.out.println("CUSTOMER PROFILE SYNC ALREADY RUNNING - " + reason + " BLOCKING RUN SKIPPED");
            return null;
        }

        try {
            System.out.println("==============================================");
            System.out.println("CUSTOMER PROFILE BACKFILL STARTED | " + reason + " | BLOCKING/SEQUENTIAL");
            System.out.println("FIELDS: DOB | FATHER NAME | PASSPORT | HEIGHT | WEIGHT | CDC | SID | COP | COC + DATES | ROLE | VESSEL | RPSL");
            System.out.println("SOURCE: CUSTOMERS TAB | ACTIVE | PERIOD=ALL TIME | ALL 10,659+ CUSTOMERS");
            System.out.println("SEA SERVICE: ROLE | VESSEL | RPSL | RPSL HISTORY | RPSL COMMENTS WHEN DG PROVIDES IT");
            System.out.println("CUSTOMER-ONLY BUILD: CASES / BIRTHDAY / SPFO / REMINDERS STAY DISABLED");
            System.out.println("==============================================");

            int sourceCount = customerSourceCases == null ? 0 : customerSourceCases.size();

            System.out.println(
                    "CUSTOMER SOURCE READY"
                            + " | CUSTOMERS TAB"
                            + " | ACTIVE"
                            + " | PERIOD=ALL TIME"
                            + " | CUSTOMER IDs ONLY"
            );

            int progressEvery = 250;
            try {
                progressEvery = Math.max(
                        1,
                        Integer.parseInt(
                                config.getProperty(
                                        "customer.profile.sync.progress.report.every",
                                        "250"
                                ).trim()
                        )
                );
            } catch (Exception ignored) {
            }

            System.setProperty(
                    "mm.customer.progress.every",
                    String.valueOf(progressEvery)
            );

            final int progressStep = progressEvery;

            CustomerProfileBackfillService.ProgressListener listener = progress -> {
                LAST_CUSTOMER_SYNC_PROGRESS = progress;

                boolean progressMailEnabled = Boolean.parseBoolean(
                        config.getProperty("customer.profile.sync.progress.mail.enabled", "false").trim()
                );
                if (!progressMailEnabled) {
                    return;
                }

                boolean sendProgress =
                        progress.scanned() == 0
                                || (progress.scanned() > 0
                                && progress.scanned() < progress.queuedForThisRun()
                                && progress.scanned() % progressStep == 0);

                if (!sendProgress) {
                    return;
                }

                String status = progress.scanned() == 0 ? "STARTED" : "PROGRESS";
                try {
                    ZohoMailService.sendCustomerProfileProgressReport(
                            config, reason, status, progress
                    );
                } catch (Exception progressMailError) {
                    System.err.println(
                            "CUSTOMER PROFILE " + status + " MAIL FAILED | "
                                    + safe(progressMailError.getMessage())
                    );
                }
            };

            CustomerProfileBackfillService.SyncReport report =
                    new CustomerProfileBackfillService(config).syncCustomers(
                            null,
                            sourceCount,
                            listener
                    );

            System.out.println(report.summary());
            for (String note : report.notes()) {
                System.out.println("PROFILE SYNC | " + note);
            }

            try {
                ZohoMailService.sendCustomerProfileSyncReport(
                        config,
                        reason,
                        report
                );
            } catch (Exception reportError) {
                System.err.println(
                        "CUSTOMER PROFILE FINAL MAIL REPORT FAILED | "
                                + safe(reportError.getMessage())
                );
            }

            return report;

        } catch (Exception e) {
            System.err.println(
                    "CUSTOMER PROFILE BACKFILL FAILED | " + safe(e.getMessage())
            );
            e.printStackTrace();

            CustomerProfileBackfillService.SyncProgress progress =
                    LAST_CUSTOMER_SYNC_PROGRESS;

            if (progress != null) {
                try {
                    ZohoMailService.sendCustomerProfileProgressReport(
                            config,
                            reason,
                            "FAILED / PARTIAL",
                            progress
                    );
                } catch (Exception mailError) {
                    System.err.println(
                            "CUSTOMER PROFILE PARTIAL FAILURE REPORT FAILED | "
                                    + safe(mailError.getMessage())
                    );
                }
            }

            return null;
        } finally {
            CUSTOMER_PROFILE_SYNC_RUNNING.set(false);
        }
    }


    private static void startCustomerProfileBackfillAsync(String reason) {

        if (config == null || SHUTTING_DOWN || isSpfoTestOnlyMode()) {
            return;
        }

        if (isRailwayRuntime()) {
            return;
        }

        if (!Boolean.parseBoolean(
                config.getProperty("customer.profile.sync.enabled", "true").trim())) {
            return;
        }

        if ("STARTUP".equalsIgnoreCase(reason)
                && !Boolean.parseBoolean(
                config.getProperty("customer.profile.sync.startup.enabled", "true").trim())) {
            return;
        }

        if ("DAILY".equalsIgnoreCase(reason)
                && !Boolean.parseBoolean(
                config.getProperty("customer.profile.sync.daily.enabled", "true").trim())) {
            return;
        }

        if (!CUSTOMER_PROFILE_SYNC_RUNNING.compareAndSet(false, true)) {
            System.out.println("CUSTOMER PROFILE SYNC ALREADY RUNNING - " + reason + " SKIPPED");
            return;
        }

        Thread worker = new Thread(() -> {
            try {
                System.out.println("==============================================");
                System.out.println("CUSTOMER PROFILE BACKFILL STARTED | " + reason);
                System.out.println("FIELDS: DOB | FATHER NAME | PASSPORT | HEIGHT | WEIGHT | CDC | SID | COP | COC + DATES | ROLE | VESSEL | RPSL");
                System.out.println("SOURCE = CUSTOMERS TAB | ACTIVE | PERIOD=ALL TIME | ALL PAGES");
                System.out.println("NON-ML FLOW = DG + SID CHECKER (WHEN NEEDED) -> MM CUSTOMER ONLY");
                System.out.println("ML FLOW = DG + SID CHECKER -> 3 SHEETS -> RPSL LINKS -> MM CUSTOMER -> MAIL + WHATSAPP");
                System.out.println("MISSING REPORT = INDoS/PASSWORD + PASSPORT/SID/CDC/DOB/FATHER/ROLE/VESSEL/RPSL");
                System.out.println("ONCE SUCCESSFULLY HANDLED = LEAVE | DAILY = CHECK NEW/RETRY ENTRIES ONLY");
                System.out.println("==============================================");

                CustomerProfileBackfillService.SyncReport report =
                        new CustomerProfileBackfillService(config).syncCustomers(
                                null, 0);

                System.out.println(report.summary());
                for (String note : report.notes()) {
                    System.out.println("PROFILE SYNC | " + note);
                }

                // If a Membership service case arrived while this long run was active,
                // the customer service stops after the current customer and asks us to
                // resume after the priority Membership case. Do not send a misleading
                // "completed" report for that intentionally paused run.
                if (MEMBERSHIP_PRIORITY_PENDING.get()) {
                    System.out.println(
                            "CUSTOMER PROFILE BACKFILL PAUSED FOR PRIORITY MEMBERSHIP"
                                    + " | Reason: " + reason
                                    + " | Processed this pass: " + report.scanned()
                    );
                } else {
                    try {
                        ZohoMailService.sendCustomerProfileSyncReport(
                                config,
                                reason,
                                report
                        );
                    } catch (Exception reportError) {
                        System.err.println(
                                "CUSTOMER PROFILE MAIL REPORT FAILED | "
                                        + safe(reportError.getMessage())
                        );
                    }
                }
            } catch (Exception e) {
                System.err.println(
                        "CUSTOMER PROFILE BACKFILL FAILED | " + safe(e.getMessage()));
                e.printStackTrace();
            } finally {
                CUSTOMER_PROFILE_SYNC_RUNNING.set(false);
            }
        }, "MM-Customer-Profile-Backfill");

        worker.setDaemon(true);
        worker.start();
    }

    @Scheduled(fixedDelay = 300000, initialDelay = 300000)
    public void watchForNewCustomers() {
        if (config == null || SHUTTING_DOWN || isSpfoTestOnlyMode() || isRailwayRuntime()) return;
        if (!Boolean.parseBoolean(
                config.getProperty("customer.profile.sync.new.watch.enabled", "true").trim())) return;

        // Membership service cases are priority work. Do not let the five-minute
        // new-customer watcher grab the shared DG/MM pipeline first.
        if (hasPendingMmMembershipCase()) {
            MEMBERSHIP_PRIORITY_PENDING.set(true);
            System.out.println(
                    "NEW CUSTOMER WATCH WAITING - PRIORITY MEMBERSHIP SERVICE CASE PENDING"
            );
            return;
        }

        // Never compete with the historical/all-time customer run. As soon as
        // that run finishes, this lightweight first-page watcher picks up newly
        // created customer IDs every five minutes.
        if (!CUSTOMER_PROFILE_SYNC_RUNNING.compareAndSet(false, true)) {
            return;
        }

        Thread worker = new Thread(() -> {
            try {
                CustomerProfileBackfillService.SyncReport report =
                        new CustomerProfileBackfillService(config).syncNewestCustomers();
                if (report.scanned() > 0) {
                    System.out.println("NEW CUSTOMER PROFILE WATCH COMPLETE | Inspected: "
                            + report.customersFound()
                            + " | New/retry processed: " + report.scanned()
                            + " | Updated: " + report.updated()
                            + " | Failed/skipped: " + report.skipped());

                    boolean hasMissingCredentials = report.missingCredentials() != null
                            && !report.missingCredentials().isEmpty();
                    boolean hasMissingFields = report.missingFields() != null
                            && !report.missingFields().isEmpty();

                    if ((hasMissingCredentials || hasMissingFields)
                            && Boolean.parseBoolean(config.getProperty(
                            "customer.profile.sync.new.issue.mail.enabled", "true").trim())) {
                        try {
                            ZohoMailService.sendCustomerProfileSyncReport(
                                    config, "NEW CUSTOMER", report);
                        } catch (Exception mailError) {
                            System.err.println("NEW CUSTOMER MISSING REPORT FAILED | "
                                    + safe(mailError.getMessage()));
                        }
                    }
                }
            } catch (Exception e) {
                System.err.println("NEW CUSTOMER PROFILE WATCH FAILED | " + safe(e.getMessage()));
            } finally {
                CUSTOMER_PROFILE_SYNC_RUNNING.set(false);
            }
        }, "MM-New-Customer-Profile-Watch");
        worker.setDaemon(true);
        worker.start();
    }


    /**
     * MM MEMBERSHIP CASE TRIGGER
     *
     * Triggered by the Case Service:
     * "Mariner Mentor Membership Life time (one-time)".
     * Comments / Remarks are not used for this trigger.
     *
     * Flow:
     * Membership service case -> linked Customer ID -> MM customer INDoS/password
     * -> DG profile -> Customer Profile/Course Details/Sea Service sheets
     * -> update the same Mariners Mentor Customer profile.
     *
     * This watcher is allowed in either startup mode. After the DG -> Sheets ->
     * MM Customer sync succeeds, it sends the MM Membership email and WhatsApp
     * once using separate no-resend markers.
     */
    @Scheduled(fixedDelay = 10000, initialDelay = 5000)
    public void watchForMmMembershipCases() {
        // Disabled by request: Membership service cases are not read/processed by MMcasesBot.
    }


    static boolean isMembershipPriorityPending() {
        return MEMBERSHIP_PRIORITY_PENDING.get();
    }

    static void requestCustomerBackfillResumeAfterMembership() {
        CUSTOMER_BACKFILL_RESUME_REQUESTED.set(true);
    }

    private static boolean hasPendingMmMembershipCase() {
        try {
            List<CaseRow> cases = fetchCases();
            if (cases == null || cases.isEmpty()) {
                return false;
            }

            Properties sent = loadSentHistory();
            LocalDate today = LocalDate.now(INDIA_ZONE);

            for (CaseRow row : cases) {
                if (row == null || row.id <= 0 || row.customer <= 0) {
                    continue;
                }
                if (!isMmMembershipCase(row)) {
                    continue;
                }
                if (sent.containsKey(getMmMembershipDoneKey(row))) {
                    continue;
                }
                if (sent.containsKey(getMmMembershipIgnoredCredentialsKey(row))) {
                    continue;
                }
                if (!isCaseStillOpen(row)) {
                    continue;
                }
                long nowMs = System.currentTimeMillis();
                Long lastAttemptMs = MM_MEMBERSHIP_LAST_ATTEMPT_MS.get(row.id);
                if (lastAttemptMs != null
                        && nowMs - lastAttemptMs < MM_MEMBERSHIP_RETRY_COOLDOWN_MS) {
                    continue;
                }
                return true;
            }
        } catch (Exception e) {
            System.err.println(
                    "MEMBERSHIP PRIORITY CHECK FAILED | "
                            + safe(e.getMessage())
            );
        }
        return false;
    }

    private static String membershipServiceDisplayName(CaseRow caseRow) {
        if (caseRow == null) {
            return "Mariner Mentor Membership Life time (one-time)";
        }

        String service = safe(caseRow.serviceName).trim();
        if (!service.isBlank()) {
            return service;
        }

        // Some dashboard list/API versions expose the selected service in the
        // case Title column instead of service_name. Use it only as a fallback.
        String title = safe(caseRow.title).trim();
        if (!title.isBlank()) {
            return title;
        }

        return "Mariner Mentor Membership Life time (one-time)";
    }

    private static boolean isMmMembershipCase(CaseRow caseRow) {
        if (caseRow == null) {
            return false;
        }

        // The dashboard can expose this selection either as service_name or as title.
        // Combining both keeps the trigger service-based and never depends on comments.
        String service = normalizeServiceName(
                safe(caseRow.serviceName) + " " + safe(caseRow.title)
        );

        if (service.isBlank()) {
            return false;
        }

        // Current dashboard service:
        // "Mariner Mentor Membership Life time (one-time)"
        return service.contains("mariner mentor mem")
                || service.contains("mariners mentor mem")
                || service.contains("mariner mentor membership")
                || service.contains("mariners mentor membership")
                || service.contains("mentor membership life time")
                || service.contains("mentor membership lifetime")
                || service.equals("mm membership")
                || service.equals("mm membership lifetime")
                || service.equals("mm membership life time");
    }

    private static String getMmMembershipDoneKey(CaseRow caseRow) {
        // v3 intentionally re-runs any still-open Membership case once after the
        // STCW checker + SID issue/expiry update was added. Normal mail/WhatsApp
        // no-resend markers remain unchanged, so candidate communication is not duplicated.
        return caseRow.id + ".mm.membership.ml.done.v3.stcw-sid-dates";
    }

    private static String getMmMembershipIgnoredCredentialsKey(CaseRow caseRow) {
        // User rule: if the stored INDoS number/login is wrong, leave the customer
        // unchanged and do not repeatedly retry that Membership case.
        return caseRow.id + ".mm.membership.ml.ignored.bad-indos.v1";
    }

    private static String getMmMembershipDailyAttemptKey(
            CaseRow caseRow,
            LocalDate date
    ) {
        // v2 lets cases attempted by an older buggy build run once again after
        // the Membership sheet/customer immediate-flow fix.
        return caseRow.id
                + ".mm.membership.ml.attempt.v2."
                + (date == null
                ? LocalDate.now(INDIA_ZONE)
                : date)
                .format(DateTimeFormatter.BASIC_ISO_DATE);
    }


    private record MembershipCommunicationResult(
            String mailStatus,
            String whatsappStatus,
            boolean complete
    ) {}

    private static MembershipCommunicationResult sendMmMembershipCommunications(
            CaseRow membershipCase
    ) {

        if (membershipCase == null || membershipCase.customer <= 0) {
            return new MembershipCommunicationResult(
                    "FAILED - INVALID CASE",
                    "FAILED - INVALID CASE",
                    false
            );
        }

        String mailStatus = "PENDING";
        String whatsappStatus = "PENDING";

        try {
            CustomerDetails customer = fetchCustomerDetails(membershipCase.customer);
            Properties sent = loadSentHistory();

            // Reuse the NORMAL CASE no-resend markers. The Membership service is
            // already part of the usual case mail/WhatsApp flow, so this prevents
            // a second message after DG/SID work. If the normal case message did
            // not go, this completion step sends the missing channel once.
            String mailKey = getSentHistoryMailKey(membershipCase);
            String whatsappKey = getSentHistoryWhatsAppKey(membershipCase);
            String serviceName = membershipServiceDisplayName(membershipCase);

            // Membership is a special service: after DG/STCW/SID/customer-profile
            // update succeeds, send the Membership candidate email regardless of
            // coordinator. The generic Sweety welcome-mail exception does not suppress
            // this Membership completion email.
            if (sent.containsKey(mailKey)) {
                mailStatus = "ALREADY SENT - NORMAL CASE FLOW";
            } else if (customer != null && !isBlank(customer.email)) {
                try {
                    ZohoMailService.sendWelcomeMail(
                            config,
                            safe(membershipCase.customerFullName),
                            customer.email,
                            serviceName,
                            getCoordinatorDisplayName(membershipCase.assignedToFullName),
                            ""
                    );
                    rememberSent(sent, mailKey);
                    mailStatus = "SENT";
                    System.out.println(
                            "MM MEMBERSHIP SERVICE MAIL SENT | Case: "
                                    + membershipCase.id
                                    + " | To: " + customer.email
                                    + " | Service: " + serviceName
                    );
                } catch (Exception mailError) {
                    mailStatus = shortStatus(
                            "FAILED - " + safe(mailError.getMessage())
                    );
                    System.err.println(
                            "MM MEMBERSHIP SERVICE MAIL FAILED | Case: "
                                    + membershipCase.id
                                    + " | " + safe(mailError.getMessage())
                    );
                }
            } else {
                mailStatus = "FAILED - EMAIL MISSING";
                System.err.println(
                        "MM MEMBERSHIP SERVICE MAIL FAILED | Case: "
                                + membershipCase.id
                                + " | Candidate email missing"
                );
            }

            sent = loadSentHistory();

            if (sent.containsKey(whatsappKey)) {
                whatsappStatus = "ALREADY SENT - NORMAL CASE FLOW";
            } else {
                String phone = customer == null ? "" : normalizePhone(customer.phone);

                if (!isBlank(phone)) {
                    try {
                        WhatsAppService.sendWelcomeMessage(
                                config,
                                phone,
                                safe(membershipCase.customerFullName),
                                serviceName,
                                getCoordinatorDisplayName(membershipCase.assignedToFullName)
                        );
                        rememberSent(sent, whatsappKey);
                        whatsappStatus = "ACCEPTED BY META";
                        System.out.println(
                                "MM MEMBERSHIP SERVICE WHATSAPP SENT | Case: "
                                        + membershipCase.id
                                        + " | To: " + phone
                                        + " | Service: " + serviceName
                        );
                    } catch (Exception whatsappError) {
                        whatsappStatus = shortStatus(
                                "FAILED - " + safe(whatsappError.getMessage())
                        );
                        System.err.println(
                                "MM MEMBERSHIP SERVICE WHATSAPP FAILED | Case: "
                                        + membershipCase.id
                                        + " | " + safe(whatsappError.getMessage())
                        );
                    }
                } else {
                    whatsappStatus = "FAILED - PHONE MISSING";
                    System.err.println(
                            "MM MEMBERSHIP SERVICE WHATSAPP FAILED | Case: "
                                    + membershipCase.id
                                    + " | Candidate phone missing"
                    );
                }
            }

        } catch (Exception e) {
            String error = shortStatus("FAILED - " + safe(e.getMessage()));
            if ("PENDING".equals(mailStatus)) {
                mailStatus = error;
            }
            if ("PENDING".equals(whatsappStatus)) {
                whatsappStatus = error;
            }
            System.err.println(
                    "MM MEMBERSHIP SERVICE COMMUNICATION FAILED | Case: "
                            + membershipCase.id
                            + " | " + safe(e.getMessage())
            );
        }

        boolean complete =
                !mailStatus.startsWith("FAILED")
                        && !whatsappStatus.startsWith("FAILED");

        return new MembershipCommunicationResult(
                mailStatus,
                whatsappStatus,
                complete
        );
    }



    @Scheduled(
            cron = "0 30 1 * * *",
            zone = "Asia/Kolkata"
    )
    public void runDailyCustomerProfileBackfill() {
        if (STARTUP_PIPELINE_RUNNING) {
            System.out.println("CUSTOMER PROFILE DAILY SKIPPED - STARTUP PIPELINE STILL RUNNING");
            return;
        }
        startCustomerProfileBackfillAsync("DAILY");
    }

    /*
     * The Spring Boot application must remain running.
     * This job starts every morning at exactly 10:00 AM IST.
     */
    @Scheduled(
            cron = "0 0 10 * * *",
            zone = "Asia/Kolkata"
    )
    public void runDailyReminderJob() {
        if (isCustomerOnlyMode()) return;

        synchronized (REMINDER_LOCK) {

            try {

                if (isSpfoTestOnlyMode()) {
                    System.out.println("SPFO TEST MODE - REMINDER JOB BLOCKED");
                    return;
                }

                if (config == null || sheetsService == null) {
                    System.out.println(
                            "Reminder skipped: application is still starting."
                    );
                    return;
                }

                processDueReminders();

            } catch (Exception e) {

                System.err.println(
                        "REMINDER JOB FAILED | "
                                + e.getMessage()
                );

                e.printStackTrace();
            }
        }
    }
    @RestController
    @CrossOrigin(origins = "*")
    public static class CaseCreatedTriggerController {

        @PostMapping("/api/process-new-cases")
        public ResponseEntity<Map<String, Object>> processNewCases(HttpServletRequest request) {
            String remote = safe(request.getRemoteAddr());
            boolean localRequest = remote.equals("127.0.0.1")
                    || remote.equals("::1")
                    || remote.equals("0:0:0:0:0:0:0:1");

            if (!localRequest) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                        "ok", false,
                        "message", "This trigger is available only from the same PC."
                ));
            }

            synchronized (WELCOME_POLL_LOCK) {
                try {
                    if (SHUTTING_DOWN) {
                        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                                "ok", false,
                                "message", "MMCaseBot is stopping."
                        ));
                    }

                    if (config == null) {
                        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                                "ok", false,
                                "message", "MMCaseBot is still starting."
                        ));
                    }

                    if (isSpfoTestOnlyMode()) {
                        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                                "ok", false,
                                "message", "SPFO test mode is active. Normal case processing is blocked."
                        ));
                    }

                    if (isRailwayRuntime() && !isRailwayCaseProcessingEnabled()) {
                        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                                "ok", false,
                                "message", "Case processing is disabled on Railway. Use the local MMCaseBot."
                        ));
                    }

                    boolean welcomeSheetEnabled = Boolean.parseBoolean(
                            config.getProperty("welcome.sheet.enabled", "false").trim()
                    );

                    if (welcomeSheetEnabled) {
                        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                                "ok", false,
                                "message", "Immediate Create Case trigger requires welcome.sheet.enabled=false."
                        ));
                    }

                    // Give the dashboard API a brief moment to expose the newly-created case.
                    try {
                        Thread.sleep(900L);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }

                    List<CaseRow> selectedCases = selectCasesForProcessing(fetchCases());
                    processWelcomeCasesWithoutSheet(selectedCases, new HashMap<>());

                    return ResponseEntity.ok(Map.of(
                            "ok", true,
                            "message", "New cases checked. Any unsent eligible case was processed immediately."
                    ));

                } catch (Exception e) {
                    System.err.println("CREATE CASE IMMEDIATE TRIGGER FAILED | " + e.getMessage());
                    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                            "ok", false,
                            "message", shortStatus("Immediate processing failed - " + e.getMessage())
                    ));
                }
            }
        }
    }

    @RestController
    public static class ReminderController {

        @GetMapping("/reminders")
        public String reminderPage() {
            return """
                <!DOCTYPE html>
                <html>
                <head>
                    <title>Mariners Mentor - Reminders</title>
                    <meta name="viewport" content="width=device-width, initial-scale=1">
                    <style>
                        body {
                            font-family: Arial, sans-serif;
                            text-align: center;
                            padding-top: 80px;
                            background: #f5f5f5;
                        }

                        .box {
                            background: white;
                            max-width: 500px;
                            margin: auto;
                            padding: 35px;
                            border-radius: 15px;
                            box-shadow: 0 4px 15px rgba(0,0,0,.15);
                        }

                        h2 {
                            color: #1e7f2d;
                        }

                        button {
                            background: #1e7f2d;
                            color: white;
                            border: none;
                            padding: 16px 28px;
                            font-size: 18px;
                            font-weight: bold;
                            border-radius: 8px;
                            cursor: pointer;
                        }

                        button:hover {
                            opacity: .9;
                        }
                    </style>
                </head>

                <body>

                <div class="box">

                    <h2>MARINERS MENTOR</h2>

                    <p>Send today's due reminders immediately.</p>

                    <form method="post" action="/send-reminders-now">

                        <button type="submit">
                            SEND REMINDERS NOW
                        </button>

                    </form>

                </div>

                </body>
                </html>
                """;
        }


        @PostMapping("/send-reminders-now")
        public String sendRemindersNow() {

            synchronized (REMINDER_LOCK) {

                try {

                    if (config == null || sheetsService == null) {
                        return """
                            <h2>Application is still starting.</h2>
                            <p>Please try again in a few seconds.</p>
                            <a href="/reminders">Back</a>
                            """;
                    }

                    processDueReminders();

                    return """
                        <div style="
                            font-family:Arial;
                            text-align:center;
                            margin-top:80px;
                        ">

                            <h2 style="color:#1e7f2d;">
                                Reminder Check Completed
                            </h2>

                            <p>
                                Today's due reminders have been processed.
                            </p>

                            <p>
                                Check the Google Sheet for SENT / FAILED status.
                            </p>

                            <br>

                            <a href="/reminders">
                                SEND / CHECK AGAIN
                            </a>

                        </div>
                        """;

                } catch (Exception e) {

                    return """
                        <div style="
                            font-family:Arial;
                            text-align:center;
                            margin-top:80px;
                        ">

                            <h2 style="color:red;">
                                Reminder Failed
                            </h2>

                            <p>%s</p>

                            <a href="/reminders">
                                Back
                            </a>

                        </div>
                        """.formatted(
                            safe(e.getMessage())
                    );
                }
            }
        }
    }
    private static void processDueReminders()
            throws Exception {

        LocalDate today =
                LocalDate.now(INDIA_ZONE);

        System.out.println();
        System.out.println("==============================================");
        System.out.println("10:00 AM REMINDER JOB STARTED");
        System.out.println("DATE : " + today.format(REMINDER_DATE_FORMAT));
        System.out.println("==============================================");

        ensureWelcomeHeaderExists();

        List<CaseRow> cases =
                fetchCases();

        Map<Long, CustomerDetails> customerCache =
                new HashMap<>();

        for (CaseRow listedCase : cases) {

            try {

                CaseRow caseRow =
                        ensureCaseRemarksLoaded(
                                listedCase
                        );

                // Reminder eligibility is based on REM date/service rules, not coordinator name.
                // Sweety reminders are therefore sent normally too.

                ReminderInfo reminder =
                        parseReminder(
                                caseRow.remarks
                        );

                if (reminder == null) {
                    continue;
                }

                LocalDate sendDate =
                        reminder.eventDate()
                                .minusDays(2);

                CustomerDetails customer =
                        customerCache.computeIfAbsent(
                                caseRow.customer,
                                Main::fetchCustomerDetails
                        );

                int rowNumber =
                        findWelcomeCaseRowNumber(
                                caseRow.id
                        );

                if (rowNumber <= 0) {

                    appendWelcomeRow(
                            createWelcomeRow(
                                    caseRow,
                                    customer,
                                    "",
                                    ""
                            )
                    );

                    rowNumber =
                            findWelcomeCaseRowNumber(
                                    caseRow.id
                            );
                }

                if (rowNumber <= 0) {
                    throw new IOException(
                            "Could not create reminder sheet row."
                    );
                }

                saveReminderDetails(
                        rowNumber,
                        caseRow.remarks,
                        reminder
                );

                /*
                 * Future reminder: keep it on hold until the two-days-before date.
                 *
                 * IMPORTANT: do not require today.equals(sendDate). If the PC was
                 * switched off on the exact send date, the old code missed the
                 * reminder forever. From sendDate through eventDate we now treat
                 * the reminder as due and send it once (sheet status prevents a
                 * duplicate). After the event date it is marked expired.
                 */
                if (today.isBefore(sendDate)) {

                    List<String> futureStatuses =
                            readReminderStatuses(
                                    rowNumber
                            );

                    String scheduledText =
                            "ON HOLD - "
                                    + sendDate.format(
                                    REMINDER_DATE_FORMAT
                            )
                                    + " 10:00 AM";

                    if (isBlank(futureStatuses.get(0))) {

                        updateWelcomeStatus(
                                rowNumber,
                                "M",
                                scheduledText
                        );
                    }

                    if (!isReminderMailRequired(
                            getDisplayServiceName(
                                    caseRow.serviceName
                            )
                    )) {

                        updateWelcomeStatus(
                                rowNumber,
                                "N",
                                "NOT REQUIRED - WHATSAPP ONLY"
                        );

                    } else if (isBlank(
                            futureStatuses.get(1)
                    )) {

                        updateWelcomeStatus(
                                rowNumber,
                                "N",
                                scheduledText
                        );
                    }

                    System.out.println(
                            "REMINDER SCHEDULED"
                                    + " | Case: "
                                    + caseRow.id
                                    + " | Event: "
                                    + reminder.eventDate()
                                    .format(REMINDER_DATE_FORMAT)
                                    + " | Send: "
                                    + sendDate.format(
                                    REMINDER_DATE_FORMAT
                            )
                    );

                    continue;
                }

                if (today.isAfter(reminder.eventDate())) {
                    List<String> expiredStatuses =
                            readReminderStatuses(rowNumber);

                    if (!isSent(expiredStatuses.get(0))) {
                        updateWelcomeStatus(
                                rowNumber,
                                "M",
                                "EXPIRED - EVENT PASSED"
                        );
                    }

                    if (!isReminderMailRequired(
                            getDisplayServiceName(caseRow.serviceName)
                    )) {
                        updateWelcomeStatus(
                                rowNumber,
                                "N",
                                "NOT REQUIRED - WHATSAPP ONLY"
                        );
                    } else if (!isSent(expiredStatuses.get(1))) {
                        updateWelcomeStatus(
                                rowNumber,
                                "N",
                                "EXPIRED - EVENT PASSED"
                        );
                    }

                    System.out.println(
                            "REMINDER EXPIRED - EVENT PASSED"
                                    + " | Case: " + caseRow.id
                                    + " | Event: "
                                    + reminder.eventDate().format(REMINDER_DATE_FORMAT)
                    );
                    continue;
                }

                if (today.isAfter(sendDate)) {
                    System.out.println(
                            "REMINDER CATCH-UP DUE"
                                    + " | Case: " + caseRow.id
                                    + " | Original Send: "
                                    + sendDate.format(REMINDER_DATE_FORMAT)
                                    + " | Event: "
                                    + reminder.eventDate().format(REMINDER_DATE_FORMAT)
                    );
                }

                List<String> reminderStatuses =
                        readReminderStatuses(
                                rowNumber
                        );

                String whatsappStatus =
                        reminderStatuses.get(0);

                String mailStatus =
                        reminderStatuses.get(1);

                String service =
                        getDisplayServiceName(
                                caseRow.serviceName
                        );

                String coordinator =
                        getCoordinatorDisplayName(
                                caseRow.assignedToFullName
                        );

                String action =
                        reminderActionForService(
                                service
                        );

                String location =
                        expandReminderLocation(
                                reminder.locationCode()
                        );

                boolean whatsappAttempted = false;
                boolean mailAttempted = false;

                if (!isSent(whatsappStatus)) {

                    whatsappAttempted = true;
                    sendReminderWhatsApp(
                            caseRow,
                            customer,
                            rowNumber,
                            action,
                            reminder,
                            location,
                            coordinator
                    );
                }

                if (!isReminderMailRequired(service)) {

                    updateWelcomeStatus(
                            rowNumber,
                            "N",
                            "NOT REQUIRED - WHATSAPP ONLY"
                    );

                } else if (!isSent(mailStatus)) {

                    mailAttempted = true;
                    sendReminderMail(
                            caseRow,
                            customer,
                            rowNumber,
                            action,
                            reminder,
                            location,
                            coordinator
                    );
                }

                // Send one office status mail whenever this run actually attempted
                // WhatsApp or candidate email. This tells the admin whether each
                // reminder was SENT or FAILED without having to inspect the console.
                if (whatsappAttempted || mailAttempted) {
                    try {
                        List<String> finalReminderStatuses =
                                readReminderStatuses(rowNumber);

                        ZohoMailService.sendReminderDeliveryStatusMail(
                                config,
                                caseRow.id,
                                safe(caseRow.customerFullName),
                                service,
                                reminder.eventDate().format(REMINDER_DATE_FORMAT),
                                location,
                                finalReminderStatuses.get(0),
                                finalReminderStatuses.get(1)
                        );
                    } catch (Exception reportError) {
                        System.err.println(
                                "REMINDER ADMIN STATUS MAIL FAILED | Case: "
                                        + caseRow.id
                                        + " | "
                                        + safe(reportError.getMessage())
                        );
                    }
                }

                updateWelcomeStatus(
                        rowNumber,
                        "O",
                        LocalDateTime.now(INDIA_ZONE)
                                .format(
                                        DateTimeFormatter.ofPattern(
                                                "dd/MM/yyyy hh:mm:ss a"
                                        )
                                )
                );

            } catch (Exception caseError) {

                System.err.println(
                        "REMINDER FAILED | Case "
                                + listedCase.id
                                + " | "
                                + caseError.getMessage()
                );
            }
        }

        System.out.println("10:00 AM REMINDER JOB FINISHED");
        System.out.println("==============================================");
    }

    private static void sendReminderWhatsApp(
            CaseRow caseRow,
            CustomerDetails customer,
            int rowNumber,
            String action,
            ReminderInfo reminder,
            String location,
            String coordinator
    ) throws IOException {

        String phone =
                normalizePhone(
                        customer.phone
                );

        if (isBlank(phone)) {

            updateWelcomeStatus(
                    rowNumber,
                    "M",
                    "FAILED - PHONE MISSING"
            );

            return;
        }

        try {

            WhatsAppService.sendReminderMessage(
                    config,
                    phone,
                    safe(caseRow.customerFullName),
                    action,
                    reminder.eventDate()
                            .format(REMINDER_DATE_FORMAT),
                    location,
                    coordinator
            );

            updateWelcomeStatus(
                    rowNumber,
                    "M",
                    "SENT - ACCEPTED BY META"
            );

            updateWelcomeStatus(
                    rowNumber,
                    "K",
                    "SENT - "
                            + LocalDateTime.now(INDIA_ZONE)
                            .format(
                                    DateTimeFormatter.ofPattern(
                                            "dd/MM/yyyy hh:mm a"
                                    )
                            )
            );

        } catch (Exception e) {

            updateWelcomeStatus(
                    rowNumber,
                    "M",
                    shortStatus(
                            "FAILED - " + e.getMessage()
                    )
            );
        }
    }

    private static void sendReminderMail(
            CaseRow caseRow,
            CustomerDetails customer,
            int rowNumber,
            String action,
            ReminderInfo reminder,
            String location,
            String coordinator
    ) throws IOException {

        if (isBlank(customer.email)) {

            updateWelcomeStatus(
                    rowNumber,
                    "N",
                    "FAILED - EMAIL MISSING"
            );

            return;
        }

        try {

            ZohoMailService.sendReminderMail(
                    config,
                    safe(caseRow.customerFullName),
                    customer.email,
                    getDisplayServiceName(
                            caseRow.serviceName
                    ),
                    action,
                    reminder.eventDate()
                            .format(REMINDER_DATE_FORMAT),
                    location,
                    coordinator
            );

            updateWelcomeStatus(
                    rowNumber,
                    "N",
                    "SENT"
            );

        } catch (Exception e) {

            updateWelcomeStatus(
                    rowNumber,
                    "N",
                    shortStatus(
                            "FAILED - " + e.getMessage()
                    )
            );
        }
    }

    private static boolean isReminderMailRequired(
            String serviceName
    ) {

        // Every valid REM : dd/MM/yyyy @ LOC reminder now gets BOTH
        // WhatsApp and email, including Passport Fresh/New, Passport Renewal,
        // SID Card Fresh and any other service using the reminder syntax.
        return true;
    }

    private static String reminderActionForService(
            String serviceName
    ) {

        String service =
                normalizeServiceName(
                        serviceName
                );

        if (service.equals("sid card fresh")
                || service.contains("sid card")) {

            return "receive your SID card";
        }

        if (service.equals("passport fresh")
                || service.equals("passport renewal")
                || service.contains("passport")) {

            return "attend your passport interview";
        }

        return "complete the scheduled activity for "
                + getDisplayServiceName(serviceName);
    }

    private static ReminderInfo parseReminder(
            String remarks
    ) {

        if (isBlank(remarks)) {
            return null;
        }

        Matcher matcher =
                REMINDER_PATTERN.matcher(
                        remarks
                );

        if (!matcher.find()) {
            return null;
        }

        try {

            LocalDate eventDate =
                    LocalDate.parse(
                            matcher.group(1),
                            REMINDER_DATE_FORMAT
                    );

            return new ReminderInfo(
                    eventDate,
                    matcher.group(2)
                            .toUpperCase(Locale.ROOT)
            );

        } catch (Exception e) {

            System.err.println(
                    "Invalid reminder syntax: "
                            + remarks
            );

            return null;
        }
    }

    private static String expandReminderLocation(
            String code
    ) {

        Map<String, String> locations =
                Map.ofEntries(
                        Map.entry("AHM", "Ahmedabad"),
                        Map.entry("AMR", "Amritsar"),
                        Map.entry("BLR", "Bengaluru"),
                        Map.entry("BHO", "Bhopal"),
                        Map.entry("BBS", "Bhubaneswar"),
                        Map.entry("CHD", "Chandigarh"),
                        Map.entry("CHN", "Chennai"),
                        Map.entry("COK", "Cochin"),
                        Map.entry("KOC", "Kochi"),
                        Map.entry("CBE", "Coimbatore"),
                        Map.entry("DEL", "Delhi"),
                        Map.entry("GOA", "Goa"),
                        Map.entry("HYD", "Hyderabad"),
                        Map.entry("JAI", "Jaipur"),
                        Map.entry("KOL", "Kolkata"),
                        Map.entry("LKO", "Lucknow"),
                        Map.entry("MDU", "Madurai"),
                        Map.entry("MUM", "Mumbai"),
                        Map.entry("NAG", "Nagpur"),
                        Map.entry("PAT", "Patna"),
                        Map.entry("PUN", "Pune"),
                        Map.entry("RAN", "Ranchi"),
                        Map.entry("SUR", "Surat"),
                        Map.entry("TRC", "Tiruchirappalli"),
                        Map.entry("TEN", "Tirunelveli"),
                        Map.entry("TVM", "Trivandrum"),
                        Map.entry("VIZ", "Visakhapatnam"),
                        Map.entry("VIJ", "Vijayawada"),
                        Map.entry("KAN", "Kandla"),
                        Map.entry("NOI", "Noida"),
                        Map.entry("TTN", "Tuticorin")


                );

        String upper =
                safe(code).toUpperCase(Locale.ROOT);

        /*
         * Any location can be added later in config.properties:
         *
         * reminder.location.TEN=Tirunelveli
         * reminder.location.MDU=Madurai
         *
         * This avoids changing Java coding for every new place.
         */
        if (config != null) {

            String configuredLocation =
                    config.getProperty(
                            "reminder.location." + upper,
                            ""
                    ).trim();

            if (!configuredLocation.isBlank()) {
                return configuredLocation;
            }
        }

        return locations.getOrDefault(
                upper,
                upper
        );
    }

    private record ReminderInfo(
            LocalDate eventDate,
            String locationCode
    ) {
    }

    // =====================================================
    // TEMPORARY: SEND ONE SPFO BALANCE ERROR CASE NOW
    // =====================================================
    private static void sendSpfoCaseNow(long caseId)
            throws Exception {

        System.out.println();
        System.out.println("==============================================");
        System.out.println("SPFO MANUAL SEND STARTED | Case: " + caseId);
        System.out.println("==============================================");

        // Fetch this case directly, so it does not depend on today's date filter.
        HttpResponse<String> response =
                sendAuthorizedGetWithRefresh(
                        CASE_DETAIL_API_BASE + caseId + "/",
                        "Case details API for ID " + caseId
                );

        JsonNode root =
                MAPPER.readTree(
                        response.body()
                );

        JsonNode caseNode =
                chooseCaseNode(
                        root
                );

        CaseRow caseRow =
                MAPPER.treeToValue(
                        caseNode,
                        CaseRow.class
                );

        // Some APIs may not repeat the ID inside the selected node.
        if (caseRow.id <= 0) {
            caseRow.id = caseId;
        }

        caseRow =
                ensureCaseRemarksLoaded(
                        caseRow
                );

        System.out.println(
                "SPFO CASE FOUND"
                        + " | Case: " + caseRow.id
                        + " | Name: " + safe(caseRow.customerFullName)
                        + " | Service: " + safe(caseRow.serviceName)
                        + " | Remarks: " + safe(caseRow.remarks)
        );

        if (!isSpfoBalanceError(caseRow)) {
            throw new IllegalStateException(
                    "Case " + caseId
                            + " is not Seaman Provident Fund with Balance Error remarks."
            );
        }

        CustomerDetails customer =
                fetchCustomerDetails(
                        caseRow.customer
                );

        if (isBlank(customer.email)) {
            throw new IllegalStateException(
                    "Candidate email is missing for case " + caseId
            );
        }

        int rowNumber =
                findWelcomeCaseRowNumber(
                        caseRow.id
                );

        if (rowNumber <= 0) {

            appendWelcomeRow(
                    createWelcomeRow(
                            caseRow,
                            customer,
                            "",
                            ""
                    )
            );

            rowNumber =
                    findWelcomeCaseRowNumber(
                            caseRow.id
                    );
        }

        if (rowNumber <= 0) {
            throw new IOException(
                    "Could not find/create welcome sheet row for case " + caseId
            );
        }

        // Force this one requested manual send even if an old sheet status exists.
        ZohoMailService.sendWelcomeMail(
                config,
                safe(caseRow.customerFullName),
                customer.email,
                getDisplayServiceName(caseRow.serviceName),
                safe(caseRow.assignedToFullName),
                getWelcomeActionUrl(caseRow, customer)
        );

        updateWelcomeStatus(
                rowNumber,
                "E",
                "SENT"
        );

        System.out.println(
                "SPFO MAIL SENT"
                        + " | Case: " + caseRow.id
                        + " | Email: " + customer.email
        );

        System.out.println("==============================================");
        System.out.println("SPFO MANUAL SEND FINISHED");
        System.out.println("==============================================");
        System.out.println();
    }


    private static void sendMailAndUpdateStatus(
            CaseRow caseRow,
            CustomerDetails customer,
            int rowNumber
    ) throws IOException {

        if (isBlank(customer.email)) {
            updateWelcomeStatus(rowNumber, "E", "FAILED - EMAIL MISSING");
            System.err.println("MAIL FAILED | " + caseRow.id + " | Email missing");
            return;
        }

        try {
            sendCaseMail(
                    caseRow,
                    customer,
                    safe(caseRow.assignedToFullName)
            );

            String mailSentStatus = isJsuBalanceQueryCase(caseRow)
                    ? "SENT - JSU BALANCE"
                    : "SENT";
            updateWelcomeStatus(rowNumber, "E", mailSentStatus);
            System.out.println("MAIL SENT | " + caseRow.id + " | " + customer.email);

        } catch (Exception e) {
            String status = shortStatus("FAILED - " + e.getMessage());
            updateWelcomeStatus(rowNumber, "E", status);
            System.err.println("MAIL FAILED | " + caseRow.id + " | " + e.getMessage());
        }
    }

    private static void sendWhatsAppAndUpdateStatus(
            CaseRow caseRow,
            CustomerDetails customer,
            int rowNumber
    ) throws IOException {

        String phone = normalizePhone(customer.phone);

        if (isBlank(phone)) {
            updateWelcomeStatus(rowNumber, "I", "FAILED - PHONE MISSING");
            System.err.println("WHATSAPP FAILED | " + caseRow.id + " | Phone missing");
            return;
        }

        try {
            sendCaseWhatsApp(
                    caseRow,
                    customer,
                    phone,
                    safe(caseRow.assignedToFullName)
            );

            String whatsAppSentStatus;
            if (isJsuBalanceQueryCase(caseRow)) {
                whatsAppSentStatus = "SENT - JSU BALANCE";
            } else if (isJsuRetirementCase(caseRow)) {
                whatsAppSentStatus = "SENT - JSU OPEN MAIL";
            } else {
                whatsAppSentStatus = "ACCEPTED BY META";
            }

            updateWelcomeStatus(rowNumber, "I", whatsAppSentStatus);
            System.out.println("WHATSAPP SENT | " + caseRow.id + " | " + phone);

        } catch (Exception e) {
            String status = shortStatus("FAILED - " + e.getMessage());
            updateWelcomeStatus(rowNumber, "I", status);
            System.err.println(
                    "WHATSAPP FAILED | " + caseRow.id + " | " + e.getMessage()
            );
        }
    }
    private static boolean shouldSendMailForCase(
            CaseRow caseRow,
            String oldStatus
    ) {
        if (isJsuBalanceQueryCase(caseRow)) {
            return !safe(oldStatus).toUpperCase(Locale.ROOT).contains("JSU BALANCE");
        }
        return !isSent(oldStatus);
    }

    private static boolean shouldSendWhatsAppForCase(
            CaseRow caseRow,
            String oldStatus
    ) {
        String status = safe(oldStatus).toUpperCase(Locale.ROOT);
        if (isJsuBalanceQueryCase(caseRow)) {
            return !status.contains("JSU BALANCE");
        }
        if (isJsuRetirementCase(caseRow)) {
            return !status.contains("JSU OPEN MAIL");
        }
        return !isSent(oldStatus);
    }

    private static String getSentHistoryMailKey(CaseRow caseRow) {
        if (isJsuBalanceQueryCase(caseRow)) {
            return caseRow.id + ".jsu.balance.mail";
        }
        return caseRow.id + ".mail";
    }

    private static String getSentHistoryWhatsAppKey(CaseRow caseRow) {
        if (isJsuBalanceQueryCase(caseRow)) {
            return caseRow.id + ".jsu.balance.whatsapp";
        }
        if (isJsuRetirementCase(caseRow)) {
            return caseRow.id + ".jsu.retirement.openmail.whatsapp";
        }
        return caseRow.id + ".whatsapp";
    }

    private static String getRunPhoneDedupKey(CaseRow caseRow, String phone) {
        String normalizedPhone = safe(phone);
        if (isJsuBalanceQueryCase(caseRow)) {
            return normalizedPhone + "|JSU_BALANCE";
        }
        if (isJsuRetirementCase(caseRow)) {
            return normalizedPhone + "|JSU_RETIREMENT";
        }
        return normalizedPhone;
    }

    private static boolean isClosedJsuOrSpfoNow(CaseRow caseRow) {
        if (caseRow == null) return false;
        boolean tracked = isJsuService(caseRow.serviceName)
                || isJsuBalanceQueryCase(caseRow)
                || isJsuRetirementCase(caseRow)
                || isSpfoBalanceCase(caseRow)
                || isSpfoBalanceQuery(caseRow.serviceName)
                || needsSpfoGrievanceFollowUp(caseRow)
                || isSpfoMailProblemCase(caseRow)
                || isSpfoRetirementCase(caseRow);
        if (!tracked) return false;
        String liveStatus = fetchMarinersMentorCaseStatus(caseRow.id);
        if (!liveStatus.isBlank()) caseRow.status = liveStatus;
        return !isCaseStillOpen(caseRow);
    }

    private static void sendCaseMail(
            CaseRow caseRow,
            CustomerDetails customer,
            String assignedTo
    ) throws Exception {

        if (isClosedJsuOrSpfoNow(caseRow)) {
            System.out.println("MAIL SKIPPED - CASE CLOSED | Case: " + caseRow.id);
            return;
        }

        // JSU BALANCE QUERY: send the checked balance by EMAIL as well as WhatsApp.
        if (caseRow != null && isJsuBalanceQueryCase(caseRow)) {
            JsuBalanceController.JsuBalanceResult result =
                    getJsuBalanceForCase(caseRow, customer);

            ZohoMailService.sendJsuBalanceMail(
                    config,
                    getSurnameFollowedByFirstName(caseRow, customer),
                    customer.email,
                    result.amount(),
                    toJsuMailContributionRows(result)
            );
            return;
        }

        // SPFO sub-actions are now identified from the shared Sheet. Their
        // dedicated external actions can be added later; until then the existing
        // normal Mariners Mentor mail flow is intentionally preserved.
        if (caseRow != null && (isSpfoBalanceCase(caseRow)
                || isSpfoMailProblemCase(caseRow)
                || isSpfoRetirementCase(caseRow))) {
            System.out.println(
                    "SPFO ACTION ROUTED | Case: " + caseRow.id
                            + " | Action: " + getCaseActionCode(caseRow)
            );
        }

        // JSU Retirement / Japan PF keeps its WhatsApp OPEN MAIL action,
        // and the candidate also receives the normal Zoho welcome email.
        ZohoMailService.sendWelcomeMail(
                config,
                safe(caseRow.customerFullName),
                customer.email,
                getCaseDisplayServiceName(caseRow),
                safe(assignedTo),
                getWelcomeActionUrl(caseRow, customer)
        );
    }

    private static void sendCaseWhatsApp(
            CaseRow caseRow,
            CustomerDetails customer,
            String phone,
            String assignedTo
    ) throws Exception {

        if (isClosedJsuOrSpfoNow(caseRow)) {
            System.out.println("WHATSAPP SKIPPED - CASE CLOSED | Case: " + caseRow.id);
            return;
        }

        if (isJsuBalanceQueryCase(caseRow)) {
            JsuBalanceController.JsuBalanceResult result =
                    getJsuBalanceForCase(caseRow, customer);

            WhatsAppService.sendJsuBalanceMessage(
                    config,
                    phone,
                    getSurnameFollowedByFirstName(caseRow, customer),
                    buildJsuBalanceWhatsAppText(result)
            );
            return;
        }

        if (isSpfoInitialAccountProblemCase(caseRow)) {
            String publicAppUrl = config.getProperty(
                    "app.public.url",
                    DEFAULT_PUBLIC_APP_URL
            ).trim().replaceAll("/+$", "");

            if (publicAppUrl.isBlank()
                    || publicAppUrl.contains("localhost")
                    || publicAppUrl.contains("127.0.0.1")) {
                throw new IllegalStateException(
                        "app.public.url must be the public Railway/domain URL for the SPFO Open Mail button."
                );
            }

            WhatsAppService.sendSpfoInitialMailMessage(
                    config,
                    phone,
                    getSurnameFollowedByFirstName(caseRow, customer),
                    String.valueOf(caseRow.id)
            );
            return;
        }

        if (isJsuRetirementCase(caseRow)) {
            String rppNumber = extractRppNumber(caseRow.remarks);

            if (rppNumber.isBlank()) {
                throw new IllegalStateException(
                        "RPP No is missing in case remarks. Required syntax: RPP NO : <number>"
                );
            }

            String publicAppUrl = config.getProperty(
                    "app.public.url",
                    DEFAULT_PUBLIC_APP_URL
            ).trim().replaceAll("/+$", "");

            if (publicAppUrl.isBlank()
                    || publicAppUrl.contains("localhost")
                    || publicAppUrl.contains("127.0.0.1")) {
                throw new IllegalStateException(
                        "app.public.url must be the public Railway/domain URL for the JSU Open Mail button."
                );
            }

            // Meta dynamic URL suffix. The public Railway endpoint also accepts
            // older signed tokens, but all new messages use the plain Case ID.
            String mailLinkSuffix = String.valueOf(caseRow.id);

            WhatsAppService.sendJsuRetirementMailMessage(
                    config,
                    phone,
                    getSurnameFollowedByFirstName(caseRow, customer),
                    rppNumber,
                    mailLinkSuffix
            );
            return;
        }

        // Name Correction: use the passport-style existing name (SURNAME + GIVEN NAME)
        // as the candidate name. The corrected name itself comes only from remarks:
        // Name : NEW CORRECT NAME
        String whatsappCandidateName = isNameCorrectionCase(caseRow)
                ? getSurnameFollowedByFirstName(caseRow, customer)
                : safe(caseRow.customerFullName);

        WhatsAppService.sendWelcomeMessage(
                config,
                phone,
                whatsappCandidateName,
                getWhatsAppServiceName(caseRow),
                getCoordinatorDisplayName(assignedTo)
        );
    }

    private static String buildJsuBalanceWhatsAppText(
            JsuBalanceController.JsuBalanceResult result
    ) {
        if (result == null) {
            return "Balance not available";
        }

        StringBuilder text = new StringBuilder();
        text.append(safe(result.amount()));

        if (result.contributions() != null && !result.contributions().isEmpty()) {
            for (JsuBalanceController.JsuContribution contribution : result.contributions()) {
                if (contribution == null) {
                    continue;
                }

                String vessel = safe(contribution.vesselName()).trim();
                String amount = normalizeJsuContributionAmount(contribution.amount());

                if (!vessel.isBlank() || !amount.isBlank()) {
                    // Meta template variables do not accept line breaks.
                    // Keep vessel-wise details, but separate them on one line.
                    text.append(" • ")
                            .append(vessel.isBlank() ? "Vessel" : vessel)
                            .append(" - ")
                            .append(amount.isBlank() ? "Not available" : amount);
                }
            }
        }

        return text.toString().trim();
    }

    private static java.util.List<String[]> toJsuMailContributionRows(
            JsuBalanceController.JsuBalanceResult result
    ) {
        java.util.List<String[]> rows = new java.util.ArrayList<>();

        if (result == null || result.contributions() == null) {
            return rows;
        }

        for (JsuBalanceController.JsuContribution contribution : result.contributions()) {
            if (contribution == null) {
                continue;
            }

            rows.add(new String[]{
                    safe(contribution.vesselName()).trim(),
                    safe(contribution.doo()).trim(),
                    safe(contribution.dod()).trim(),
                    normalizeJsuContributionAmount(contribution.amount())
            });
        }

        return rows;
    }

    private static String normalizeJsuContributionAmount(String value) {
        String amount = safe(value).replaceAll("\\s+", " ").trim();
        if (amount.isBlank()) {
            return "";
        }

        String upper = amount.toUpperCase(Locale.ROOT);
        if (!upper.contains("US $") && !upper.contains("US$") && !upper.contains("USD")) {
            amount = amount + " US $";
        }
        return amount;
    }

    private static String getCaseActionCode(CaseRow caseRow) {
        if (caseRow == null) {
            return CaseRuleService.ACTION_WELCOME;
        }

        return CaseRuleService.resolveActionCode(
                safe(caseRow.serviceName),
                safe(caseRow.remarks)
        );
    }

    private static boolean isJsuBalanceQueryCase(CaseRow caseRow) {
        if (caseRow == null) {
            return false;
        }

        return CaseRuleService.ACTION_JSU_BALANCE.equals(getCaseActionCode(caseRow))
                || isJsuBalanceQueryService(caseRow.serviceName);
    }

    private static boolean isJsuRetirementCase(CaseRow caseRow) {
        if (caseRow == null) {
            return false;
        }

        return CaseRuleService.ACTION_JSU_RETIREMENT.equals(getCaseActionCode(caseRow))
                || isJsuRetirementService(caseRow.serviceName);
    }

    private static boolean isSpfoBalanceCase(CaseRow caseRow) {
        return caseRow != null
                && CaseRuleService.ACTION_SPFO_BALANCE.equals(getCaseActionCode(caseRow));
    }

    private static boolean isSpfoInterestCase(CaseRow caseRow) {
        if (caseRow == null) {
            return false;
        }

        /*
         * HARD SAFETY RULE:
         * Grievance Pending/Closed checking belongs ONLY to an explicitly named
         * SPFO Interest Problem case. Do not trust a stale/shared Sheet action
         * mapping by itself, because that could accidentally send an ordinary
         * SPFO Balance case into the grievance Pending tab.
         */
        String combined = normalizeServiceName(
                safe(caseRow.serviceName) + " " + safe(caseRow.remarks)
        );

        boolean mentionsSpfo = combined.contains("spfo")
                || combined.contains("seaman provident fund")
                || combined.contains("seamen provident fund");
        boolean mentionsInterest = combined.contains("interest")
                || combined.contains("intrest");
        boolean mentionsProblem = combined.contains("problem");

        return mentionsSpfo && mentionsInterest && mentionsProblem;
    }

    private static boolean isSpfoInitialAccountProblemCase(CaseRow caseRow) {
        return caseRow != null
                && CaseRuleService.ACTION_SPFO_INITIAL_MAIL.equals(getCaseActionCode(caseRow));
    }

    private static boolean isSpfoMailProblemCase(CaseRow caseRow) {
        if (caseRow == null) {
            return false;
        }

        String action = getCaseActionCode(caseRow);
        return CaseRuleService.ACTION_SPFO_INITIAL_MAIL.equals(action)
                || CaseRuleService.ACTION_SPFO_INTEREST_MAIL.equals(action)
                || CaseRuleService.ACTION_SPFO_BALANCE_MAIL.equals(action);
    }

    private static boolean isSpfoRetirementCase(CaseRow caseRow) {
        return caseRow != null
                && CaseRuleService.ACTION_SPFO_RETIREMENT.equals(getCaseActionCode(caseRow));
    }

    private static boolean isJsuBalanceQueryService(String serviceName) {
        String service = normalizeServiceName(serviceName);
        return service.equals("jsu balance query")
                || service.equals("jsu balance");
    }

    private static boolean isJsuRetirementService(String serviceName) {
        String service = normalizeServiceName(serviceName);

        if (service.equals("jsu balance query")
                || service.equals("claim all")
                || service.equals("jsu claim all")) {
            return false;
        }

        return service.equals("jsu retired")
                || service.equals("jsu retire")
                || service.equals("jsu retirement")
                || service.equals("jsu retirement claim")
                || service.equals("jsu retired claim")
                || service.equals("jsu claim")
                || service.contains("japan pf")
                || service.contains("japan pension")
                || service.contains("retirement pay plan");
    }

    private static JsuBalanceController.JsuBalanceResult getJsuBalanceForCase(
            CaseRow caseRow,
            CustomerDetails customer
    ) {
        JsuBalanceController.JsuBalanceResult cached =
                JSU_BALANCE_CACHE.get(caseRow.id);

        if (cached != null) {
            return cached;
        }

        RuntimeException previousFailure = JSU_BALANCE_FAILURE_CACHE.get(caseRow.id);
        if (previousFailure != null) {
            throw previousFailure;
        }

        String displayName = getSurnameFollowedByFirstName(caseRow, customer);

        String name = displayName
                .replaceAll("\\s+", " ")
                .trim()
                .toUpperCase(Locale.ROOT);

        if (name.isBlank()) {
            throw new IllegalStateException(
                    "Candidate name is missing in case " + caseRow.id
            );
        }

        String rawDob = firstNonBlank(
                caseRow.dateOfBirth,
                customer == null ? "" : customer.dateOfBirth
        );

        LocalDate dob = parseFlexibleDob(rawDob);

        if (dob == null) {
            throw new IllegalStateException(
                    "DOB is missing/invalid for JSU Balance case "
                            + caseRow.id
                            + ". Expected DOB from case/customer data."
            );
        }

        String rppNumber = extractRppNumber(caseRow.remarks);

        // Emergency one-time data for Case 7746 supplied/verified manually.
        // Future JSU Balance cases should put: RPP NO : 123456 in Remarks.
        if (rppNumber.isBlank() && caseRow.id == 7746L) {
            rppNumber = "378573";
        }

        if (rppNumber.isBlank()) {
            throw new IllegalStateException(
                    "RPP No is missing for JSU Balance case " + caseRow.id
                            + ". Put in Remarks: RPP NO : <number>"
            );
        }

        System.out.println(
                "JSU BALANCE AUTO CHECK"
                        + " | Case: " + caseRow.id
                        + " | Name: " + name
                        + " | DOB: " + dob.format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))
                        + " | RPP No: " + rppNumber
        );

        try {
            JsuBalanceController.JsuBalanceResult result =
                    JsuBalanceController.fetchJsuBalance(name, dob, rppNumber);

            JSU_BALANCE_CACHE.put(caseRow.id, result);

            System.out.println(
                    "JSU BALANCE FOUND"
                            + " | Case: " + caseRow.id
                            + " | Amount: " + result.amount()
            );

            return result;
        } catch (RuntimeException e) {
            // One browser lookup per case per application run. If the JSU login
            // details are wrong or the site times out, do not launch a second
            // identical 40-second lookup for the other channel.
            JSU_BALANCE_FAILURE_CACHE.put(caseRow.id, e);
            throw e;
        }
    }

    private static String firstNonBlank(String... values) {
        if (values == null) return "";
        for (String value : values) {
            if (!isBlank(value)) return value.trim();
        }
        return "";
    }

    /**
     * JSU requires the passport-style name order: SURNAME followed by FIRST NAME.
     * Prefer the separate customer fields from the Mariners Mentor customer API.
     * Fall back to any separate case fields, then finally to customerFullName.
     */
    private static String getSurnameFollowedByFirstName(
            CaseRow caseRow,
            CustomerDetails customer
    ) {
        // Case 7746: verified directly from passport supplied for this manual resend.
        if (caseRow != null && caseRow.id == 7746L) {
            return "CHANDRABABU SAHAYA SUTHERSON";
        }

        String surname = firstNonBlank(
                customer == null ? "" : customer.surname,
                caseRow == null ? "" : caseRow.surname
        );

        String firstName = firstNonBlank(
                customer == null ? "" : customer.firstName,
                caseRow == null ? "" : caseRow.firstName
        );

        String ordered = (surname + " " + firstName)
                .replaceAll("\\s+", " ")
                .trim();

        if (!ordered.isBlank()) {
            return ordered;
        }

        return safe(caseRow == null ? "" : caseRow.customerFullName)
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static LocalDate parseFlexibleDob(String value) {
        String raw = safe(value);
        if (raw.isBlank()) return null;

        if (raw.length() >= 10 && raw.charAt(4) == '-' && raw.charAt(7) == '-') {
            raw = raw.substring(0, 10);
        }

        List<DateTimeFormatter> formats = List.of(
                DateTimeFormatter.ofPattern("dd/MM/uuuu"),
                DateTimeFormatter.ofPattern("dd-MM-uuuu"),
                DateTimeFormatter.ISO_LOCAL_DATE
        );

        for (DateTimeFormatter format : formats) {
            try {
                return LocalDate.parse(raw, format);
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private static String extractRppNumber(String remarks) {
        Matcher matcher = RPP_NUMBER_PATTERN.matcher(safe(remarks));
        if (!matcher.find()) return "";
        return safe(matcher.group(1)).toUpperCase(Locale.ROOT);
    }

    private static String createJsuRetirementMailToken(long caseId) {
        // Use the Case ID itself as the WhatsApp URL-button suffix.
        // The link is handled by the public Railway service, so this avoids
        // local-vs-Railway signing-secret mismatches causing "Invalid Link".
        return String.valueOf(caseId);
    }

    private static long validateAndReadJsuRetirementCaseId(String token) {
        String cleaned = safe(token);

        // Current format: plain positive Case ID from the WhatsApp button.
        try {
            long directCaseId = Long.parseLong(cleaned);
            if (directCaseId > 0) {
                return directCaseId;
            }
        } catch (Exception ignored) {
        }

        // Backward compatibility: also accept older signed links already sent.
        String[] parts = cleaned.split("\\.", 2);
        if (parts.length != 2) return -1L;

        try {
            long caseId = Long.parseLong(parts[0]);
            if (caseId <= 0) return -1L;

            byte[] supplied = parts[1].getBytes(StandardCharsets.UTF_8);
            byte[] expected = signJsuRetirementMailPayload(parts[0])
                    .getBytes(StandardCharsets.UTF_8);

            return MessageDigest.isEqual(supplied, expected)
                    ? caseId
                    : -1L;
        } catch (Exception e) {
            return -1L;
        }
    }

    private static String signJsuRetirementMailPayload(String payload) {
        try {
            String secret = config.getProperty("jsu.link.secret", "").trim();
            if (secret.length() < 32) {
                throw new IllegalStateException(
                        "jsu.link.secret must contain at least 32 characters"
                );
            }

            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(
                    secret.getBytes(StandardCharsets.UTF_8),
                    "HmacSHA256"
            ));

            return Base64.getUrlEncoder()
                    .withoutPadding()
                    .encodeToString(
                            mac.doFinal(payload.getBytes(StandardCharsets.UTF_8))
                    );
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Could not create JSU retirement mail link",
                    e
            );
        }
    }

    private static boolean isCdcService(String serviceName) {
        String service = normalizeServiceName(serviceName);
        return !service.isBlank() && service.contains("cdc");
    }
    private static AgentContact resolveAgentContact(CaseRow caseRow) {

        if (caseRow == null) {
            return null;
        }

        String remarks = safe(caseRow.remarks);

        Matcher matcher = AGENT_PATTERN.matcher(remarks);

        if (!matcher.find()) {
            return null;
        }

        String enteredName = safe(matcher.group(1)).trim();

        if (enteredName.isBlank()) {
            return null;
        }

        // Example:
        // Agent : Udhayan
        // becomes:
        // agent.udhayan.phone
        //
        // Agent : Noble - Salem
        // becomes:
        // agent.noble_salem.phone

        String key = enteredName
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "_")
                .replaceAll("_+", "_")
                .replaceAll("^_+|_+$", "");

        String propertyName =
                "agent." + key + ".phone";

        String phone =
                safe(config.getProperty(propertyName));

        // Old Muthu_Punnai compatibility
        if (phone.isBlank()
                && key.equals("muthu_punnai")) {

            key = "spm_computer_punnai";

            propertyName =
                    "agent.spm_computer_punnai.phone";

            phone =
                    safe(config.getProperty(propertyName));
        }

        if (phone.isBlank()) {

            System.err.println(
                    "AGENT PHONE NOT FOUND"
                            + " | Case: " + caseRow.id
                            + " | Agent: " + enteredName
                            + " | Expected config: "
                            + propertyName
            );

            return null;
        }

        System.out.println(
                "AGENT FOUND"
                        + " | Case: " + caseRow.id
                        + " | Agent: " + enteredName
                        + " | Phone: " + phone
        );

        return new AgentContact(
                key,
                enteredName,
                phone
        );
    }

    private static AgentContact agentContact(String key, String displayName, String defaultPhone) {
        String phone = config.getProperty("agent." + key + ".phone", defaultPhone).trim();
        return new AgentContact(key, displayName, phone);
    }

    private static String getAgentAttemptChannel(AgentContact agent) {
        return "cdc.agent.whatsapp." + agent.key(); // legacy key retained for no-resend compatibility
    }

    private static String getAgentSentHistoryKey(
            CaseRow caseRow,
            AgentContact agent
    ) {
        return caseRow.id + "." + getAgentAttemptChannel(agent);
    }

    private static void sendAgentWhatsAppIfNeeded(
            CaseRow caseRow,
            Map<Long, CustomerDetails> customerCache
    ) {
        if (resolveAgentContact(caseRow) == null) {
            return;
        }

        CustomerDetails customer = null;
        try {
            if (caseRow != null && caseRow.customer > 0 && customerCache != null) {
                customer = customerCache.computeIfAbsent(
                        caseRow.customer,
                        Main::fetchCustomerDetails
                );
            }
        } catch (Exception e) {
            System.err.println(
                    "AGENT CUSTOMER DETAILS WARNING"
                            + " | Case: " + (caseRow == null ? "-" : caseRow.id)
                            + " | " + safe(e.getMessage())
            );
        }

        sendAgentWhatsAppIfNeeded(caseRow, customer);
    }

    private static void sendAgentWhatsAppIfNeeded(
            CaseRow caseRow,
            CustomerDetails customer
    ) {

        AgentContact agent = resolveAgentContact(caseRow);

        if (agent == null) {
            return;
        }

        if (customer == null) {
            System.err.println(
                    "AGENT WHATSAPP SKIPPED - CUSTOMER DETAILS MISSING"
                            + " | Case: " + (caseRow == null ? "-" : caseRow.id)
                            + " | Agent: " + agent.displayName()
            );
            return;
        }

        try {
            Properties sent = loadSentHistory();
            String sentKey = getAgentSentHistoryKey(caseRow, agent);
            String channel = getAgentAttemptChannel(agent);

            if (sent.containsKey(sentKey)) {
                System.out.println(
                        "AGENT WHATSAPP ALREADY SENT"
                                + " | Case: " + caseRow.id
                                + " | Agent: " + agent.displayName()
                                + " | Phone: " + agent.phone()
                );
                return;
            }

            if (!claimAttemptOnce(caseRow, channel)) {
                System.out.println(
                        "AGENT WHATSAPP DUPLICATE BLOCKED"
                                + " | Case: " + caseRow.id
                                + " | Agent: " + agent.displayName()
                );
                return;
            }

            try {
                String agentPhone = normalizePhone(agent.phone());
                if (isBlank(agentPhone)) {
                    throw new IllegalStateException(
                            "Agent phone is missing/invalid for " + agent.displayName()
                    );
                }

                System.out.println(
                        "SENDING CANDIDATE WHATSAPP COPY TO AGENT"
                                + " | Case: " + caseRow.id
                                + " | Agent: " + agent.displayName()
                                + " | Phone: " + agentPhone
                                + " | Service: " + getCaseDisplayServiceName(caseRow)
                );

                // Send the SAME WhatsApp flow/template used for the candidate.
                // Only the destination phone number is changed to the selected agent.
                // No agent email is sent.
                sendCaseWhatsApp(
                        caseRow,
                        customer,
                        agentPhone,
                        safe(caseRow.assignedToFullName)
                );

                // Mark as sent only after the WhatsApp call succeeds.
                rememberSent(sent, sentKey);

                System.out.println(
                        "AGENT WHATSAPP SENT"
                                + " | Case: " + caseRow.id
                                + " | Agent: " + agent.displayName()
                                + " | Phone: " + agentPhone
                                + " | SAME CANDIDATE TEMPLATE"
                                + " | NO AGENT EMAIL"
                );

            } catch (Exception sendError) {
                String errorText = safe(sendError.getMessage());

                // A confirmed Meta template/config rejection means nothing was delivered.
                // Release this agent attempt so it can be retried after the template/config is fixed.
                if (errorText.contains("132001") || errorText.contains("132000")) {
                    forgetAttempt(
                            loadAttemptHistory(),
                            getAttemptHistoryKey(caseRow, channel)
                    );
                }

                System.err.println(
                        "AGENT WHATSAPP FAILED"
                                + " | Case: " + caseRow.id
                                + " | Agent: " + agent.displayName()
                                + " | Phone: " + agent.phone()
                                + " | Error: " + errorText
                );
                sendError.printStackTrace();
            }

        } catch (Exception e) {
            System.err.println(
                    "AGENT WHATSAPP ROUTING FAILED"
                            + " | Case: " + (caseRow == null ? "-" : caseRow.id)
                            + " | Error: " + safe(e.getMessage())
            );
            e.printStackTrace();
        }
    }

    private record AgentContact(
            String key,
            String displayName,
            String phone
    ) {
    }

    private static String getCoordinatorDisplayName(String assignedName) {

        String name = safe(assignedName)
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z]", "");

        if (name.contains("jaya")) {
            return "Miss. Jaya";
        }

        if (name.contains("priya")) {
            return "Mrs. Priya";
        }

        if (name.contains("subha")) {
            return "Ms. Subha";
        }

        if (name.contains("amal")) {
            return "Mr. Amal Roy";
        }
        return safe(assignedName);
    }

    private static boolean isMailRequired(CaseRow caseRow) {

        if (caseRow == null) {
            return false;
        }

        // JSU Balance is a dedicated result workflow and must send its result
        // email + WhatsApp even if the coordinator happens to be Sweety.
        if (isJsuBalanceQueryCase(caseRow)) {
            return true;
        }

        // Sweety cases are WhatsApp-only for the NORMAL candidate welcome flow.
        // Special result/completion workflows (such as JSU Balance and Membership)
        // are handled separately and are not suppressed here.
        if (isSweetyCoordinator(caseRow)) {
            return false;
        }

        return !normalizeServiceName(caseRow.serviceName).isBlank();
    }

    private static boolean isSweetyCoordinator(CaseRow caseRow) {
        if (caseRow == null) {
            return false;
        }

        String assigned = safe(caseRow.assignedToFullName)
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z]", "");

        return assigned.contains("sweety");
    }


    private static String getWelcomeActionUrl(
            CaseRow caseRow,
            CustomerDetails customer
    ) throws IOException {

        String service = normalizeServiceName(
                caseRow == null ? "" : caseRow.serviceName
        );

        // =====================================================
        // EXISTING JSU BALANCE QUERY - UNCHANGED
        // =====================================================
        if (isJsuBalanceQueryCase(caseRow)) {

            String publicAppUrl = config.getProperty(
                    "app.public.url",
                    DEFAULT_PUBLIC_APP_URL
            ).trim();

            String token = createJsuBalanceToken();

            return publicAppUrl.replaceAll("/+$", "")
                    + "/jsu-balance?token="
                    + token;
        }

        // =====================================================
        // SPFO BALANCE QUERY - RESERVED FOR NEXT STEP
        // =====================================================
        if (isSpfoBalanceQuery(service) || isSpfoBalanceCase(caseRow)) {
            return "";
        }

        // =====================================================
        // SPFO BALANCE ERROR
        // =====================================================
        if (isSpfoBalanceError(caseRow)) {

            if (customer == null || isBlank(customer.email)) {
                throw new IOException(
                        "SPFO secure link cannot be created because candidate email is missing."
                );
            }

            return createSpfoSecureLink(
                    caseRow,
                    customer
            );
        }

        return "";
    }


    private static boolean isSpfoBalanceQuery(String serviceName) {
        String service = normalizeServiceName(serviceName);

        return service.equals("spfo balance")
                || service.equals("spfo balance query")
                || service.equals("seaman provident fund balance")
                || service.equals("seaman provident fund balance query")
                || service.equals("seamen provident fund balance")
                || service.equals("seamen provident fund balance query");
    }


    private static boolean isSpfoBalanceError(
            CaseRow caseRow
    ) {

        if (caseRow == null) {
            return false;
        }

        String service =
                normalizeServiceName(
                        caseRow.serviceName
                );

        String remarks =
                safe(caseRow.remarks)
                        .trim()
                        .toLowerCase(Locale.ROOT)
                        .replaceAll("[^a-z0-9]+", " ")
                        .replaceAll("\\s+", " ")
                        .trim();

        boolean spfoService =
                service.equals("seaman provident fund")
                        || service.equals("seamen provident fund")
                        || service.contains("spfo");

        return spfoService
                && remarks.contains("balance error");
    }


    private static String createSpfoSecureLink(
            CaseRow caseRow,
            CustomerDetails customer
    ) throws IOException {

        String baseUrl =
                config.getProperty(
                        "spfo.form.url",
                        ""
                ).trim();

        if (baseUrl.isBlank()) {
            throw new IOException(
                    "spfo.form.url is missing in config.properties"
            );
        }

        String candidateEmail =
                safe(customer.email)
                        .trim()
                        .toLowerCase(Locale.ROOT);

        if (candidateEmail.isBlank()) {
            throw new IOException(
                    "Candidate email is missing for SPFO secure link."
            );
        }

        // Same token format used by the Apps Script helper:
        // two UUID values with hyphens removed.
        String token =
                UUID.randomUUID()
                        .toString()
                        .replace("-", "")
                        + UUID.randomUUID()
                        .toString()
                        .replace("-", "");

        OffsetDateTime createdAt =
                OffsetDateTime.now(
                        INDIA_ZONE
                );

        OffsetDateTime expiresAt =
                createdAt.plusHours(24);

        // Apps Script reads this sheet:
        // Token, Type, Case ID, Candidate Email, Candidate Name,
        // Mobile, INDoS, Created At, Expires At, Status, Used At
        List<Object> secureLinkRow =
                List.of(
                        token,
                        "SPFO",
                        String.valueOf(caseRow.id),
                        candidateEmail,
                        safe(caseRow.customerFullName),
                        safe(customer.phone),
                        "",
                        "",
                        createdAt.toString(),
                        expiresAt.toString(),
                        "UNUSED",
                        ""
                );

        ValueRange body =
                new ValueRange()
                        .setValues(
                                List.of(
                                        secureLinkRow
                                )
                        );

        sheetsService
                .spreadsheets()
                .values()
                .append(
                        SPREADSHEET_ID,
                        "'SECURE_LINKS'!A:L",
                        body
                )
                .setValueInputOption("USER_ENTERED")
                .setInsertDataOption("INSERT_ROWS")
                .execute();

        System.out.println(
                "SPFO SECURE LINK CREATED"
                        + " | Case: "
                        + caseRow.id
                        + " | Email: "
                        + candidateEmail
        );

        return baseUrl.replaceAll("/+$", "")
                + "?token="
                + token;
    }


    private static String createJsuBalanceToken() {
        long expiresAt = System.currentTimeMillis()
                + JSU_LINK_VALIDITY_MILLIS;

        return expiresAt + "." + signJsuExpiry(expiresAt);
    }

    private static boolean isValidJsuBalanceToken(String token) {
        try {
            String[] parts = safe(token).split("\\.", 2);

            if (parts.length != 2) {
                return false;
            }

            long expiresAt = Long.parseLong(parts[0]);

            if (System.currentTimeMillis() > expiresAt) {
                return false;
            }

            byte[] supplied = parts[1].getBytes(StandardCharsets.UTF_8);
            byte[] expected = signJsuExpiry(expiresAt)
                    .getBytes(StandardCharsets.UTF_8);

            return MessageDigest.isEqual(supplied, expected);

        } catch (Exception e) {
            return false;
        }
    }

    private static String signJsuExpiry(long expiresAt) {
        try {
            String secret = config.getProperty(
                    "jsu.link.secret",
                    ""
            ).trim();

            if (secret.length() < 32) {
                throw new IllegalStateException(
                        "jsu.link.secret must contain at least 32 characters"
                );
            }

            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(
                    secret.getBytes(StandardCharsets.UTF_8),
                    "HmacSHA256"
            ));

            return Base64.getUrlEncoder()
                    .withoutPadding()
                    .encodeToString(
                            mac.doFinal(
                                    String.valueOf(expiresAt)
                                            .getBytes(StandardCharsets.UTF_8)
                            )
                    );

        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Could not create JSU balance link",
                    e
            );
        }
    }

    private static Set<String> getWhatsAppOnlyServices() {

        return Set.of(
                "indos no",
                "pre sea courses fundamental",
                "octf dc apply",
                "ab cop certificate",
                "bharatkosh payment",
                "post sea modular courses specialized",
                "uniform accessories",
                "repeater gp rating exam",
                "pre sea training course",
                "passport updation dg profile",
                "post sea modular courses fundamental",
                "pre sea residential courses",
                "seafarer resume creation",
                "panama tasco course endrosement",
                "panama tasco course endorsement",
                "panama chemco course endrosement",
                "panama chemco course endorsement",
                "panama ecdis course",
                "panama arpa course",
                "panama frb course",
                "panama security awareness course",
                "bosiet course",
                "huet course",
                "container familiarization course",
                "ships cook course",
                "refresher ships cook course",
                "food handling course",
                "crisis management course",
                "crowd crisis management course",
                "crisis management human behaviour course",
                "crisis management human behavior course",
                "gmdss coc",
                "profile update",
                "cop basic gas dce",
                "watchkeeping tar book engine side e wk tb",
                "cop tar book engine",
                "panama pssr course",
                "class ii preparatory ii mate fg",
                "class iv preparatory iv engg fg",
                "class ii coc ii mate fg",
                "class i coc cheif mate fg",
                "class i coc chief mate fg",
                "coc tar book gme tme",
                "panama pst course",
                "panama fpff course",
                "class ii assessment deck",
                "class iv assessment engine",
                "eto assessment",
                "etr cop",
                "master coc fg",
                "cheif engineer coc",
                "chief engineer coc",
                "panama deck wk endrosement",
                "panama deck wk endorsement",
                "panama engine wk endrosement",
                "panama engine wk endorsement",
                "bst",
                "panama medical",
                "stsdsd",
                "panama efa course",
                "panama stsdsd course",
                "panama aff course",
                "panama mfa course",
                "panama pscrb course",
                "panama gmdss course endrosement",
                "panama gmdss course endorsement",
                "panama sso course endrosement",
                "panama sso course endorsement",
                "panama oil chemical course endrosement",
                "panama oil chemical course endorsement",
                "panama gas course endrosement",
                "panama gas course endorsement",
                "panama cook course endrosement",
                "panama cook course endorsement",
                "h2s course",
                "cop for igf",
                "pumpman course",
                "enclose space entry course",
                "enclosed space entry course",
                "food safety hygiene course",
                "hazmat course",
                "haccp course",
                "hydro blasting course",
                "streering test",
                "steering test",
                "tasco course",
                "chemco course",
                "maritime english",
                "security awareness course",
                "frb course",
                "medical care",
                "errm course",
                "rigging slinging course",
                "honduras third mate coc",
                "honduras chief mate coc",
                "honduras master coc",
                "honduras third engineer coc",
                "honduras second engineer coc",
                "honduras first engineer coc",
                "honduras chief engineer coc",
                "honduras eto coc",
                "cook islands third mate coc",
                "cook islands second mate coc",
                "cook islands chief mate coc",
                "cook islands master coc",
                "cook islands third engineer coc",
                "cook islands second engineer coc",
                "cook islands first engineer coc",
                "cook islands chief engineer coc",
                "cook islands eto coc",
                "cook coc",
                "advance d c e level ii",
                "advance dce level ii",
                "c1 d us visa",
                "seaman provident fund",
                "watchkeeping certificate",
                "e samudra",
                "b igf rnwl",
                "b igf fresh",
                "a igf rnwl",
                "a igf fresh",
                "jsu",
                "japan pf",
                "japan pension",
                "retirement pay plan",
                "gazette",
                "job application",
                "SID Card Fresh"
        );
    }

    private static boolean isAllowedService(String serviceName) {

        String service = normalizeServiceName(serviceName);

        // Every valid service received from the Cases API must be entered in
        // the sheet and receive its WhatsApp welcome message.
        return !service.isBlank();
    }


    private static boolean isNameCorrectionCase(CaseRow caseRow) {
        if (caseRow == null) return false;
        String normalized = normalizeServiceName(caseRow.serviceName);
        return normalized.contains("name correction")
                || normalized.contains("name correct")
                || normalized.equals("nc apply")
                || normalized.startsWith("nc ");
    }

    private static String getWhatsAppServiceName(CaseRow caseRow) {
        String service = getCaseDisplayServiceName(caseRow);

        if (caseRow == null) {
            return service;
        }

        if (!isNameCorrectionCase(caseRow)) {
            return service;
        }

        String newName = extractNewName(caseRow.remarks);
        if (newName.isBlank()) {
            return "NAME CORRECTION";
        }

        return "Name : " + newName.trim();
    }

    private static String extractNewName(String remarks) {
        Matcher matcher = NEW_NAME_PATTERN.matcher(safe(remarks));
        if (!matcher.find()) {
            return "";
        }

        return safe(matcher.group(1))
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static String getCaseDisplayServiceName(CaseRow caseRow) {
        if (caseRow == null) {
            return "";
        }

        String action = getCaseActionCode(caseRow);

        if (CaseRuleService.ACTION_JSU_BALANCE.equals(action)) {
            return "JSU BALANCE QUERY";
        }
        if (CaseRuleService.ACTION_JSU_RETIREMENT.equals(action)) {
            return "JSU RETIREMENT CLAIM";
        }
        if (CaseRuleService.ACTION_SPFO_BALANCE.equals(action)) {
            return "SPFO BALANCE";
        }
        if (CaseRuleService.ACTION_SPFO_INITIAL_MAIL.equals(action)) {
            return "SPFO INITIAL A/C PROBLEM";
        }
        if (CaseRuleService.ACTION_SPFO_INTEREST_MAIL.equals(action)) {
            return "SPFO INTEREST PROBLEM";
        }
        if (CaseRuleService.ACTION_SPFO_BALANCE_MAIL.equals(action)) {
            return "SPFO BALANCE PROBLEM";
        }
        if (CaseRuleService.ACTION_SPFO_RETIREMENT.equals(action)) {
            return "SPFO RETIREMENT CLAIM";
        }

        return getDisplayServiceName(caseRow.serviceName);
    }


    private static String getDisplayServiceName(String serviceName) {

        String service = normalizeServiceName(serviceName);

        if (service.contains("mariner mentor membership")
                || service.contains("mariners mentor membership")
                || service.equals("mm membership")
                || service.equals("mm membership lifetime")
                || service.equals("mm membership life time")) {
            return "Mariner Mentor Membership Life time (one-time)";
        }

        // Check exact normalized service names first
        String displayName = getServiceDisplayNames().get(service);

        if (displayName != null) {
            return displayName;
        }

        // Existing flexible matches
        if (service.contains("photo correction")) {
            return "PHOTO CORRECTION";
        }

        if (service.contains("name correction")) {
            return "NAME CORRECTION";
        }

        if (isEtrTarbook(service)) {
            return "ETR TARBOOK";
        }

        if (isEngineTarbook(service)) {
            return "ENGINE TARBOOK";
        }

        if (isDeckTarbook(service)) {
            return "CoP TAR BOOK - DECK (ABLE BODY SEAFARER)";
        }

        if (isSpfoBalanceQuery(service)) {
            return "SPFO BALANCE QUERY";
        }

        if (isSpfoServiceName(service)) {
            return "SPFO";
        }

        if (service.equals("jsu balance query")) {
            return "JSU BALANCE QUERY";
        }

        // JSU is now one umbrella dashboard service. The sub-action is read
        // from Remarks / MM Case Reminder Rules, so do not label bare JSU as Retirement.
        if (service.equals("jsu")) {
            return "JSU";
        }

        if (isJsuService(service)) {
            if (service.equals("claim all")
                    || service.equals("jsu claim all")) {
                return "CLAIM ALL";
            }
            return "JSU RETIREMENT CLAIM";
        }

        return safe(serviceName).toUpperCase(Locale.ROOT);
    }


    private static Map<String, String> getServiceDisplayNames() {

        Map<String, String> services = new HashMap<>();

        addService(
                services,
                "SPFO BALANCE QUERY",
                "spfo balance",
                "spfo balance query",
                "seaman provident fund balance",
                "seaman provident fund balance query",
                "seamen provident fund balance",
                "seamen provident fund balance query"
        );

        addService(
                services,
                "SPFO",
                "spfo",
                "seaman provident fund",
                "seamen provident fund"
        );

        addService(
                services,
                "JSU RETIREMENT CLAIM",
                "jsu retired",
                "jsu retire",
                "jsu retirement",
                "jsu retirement claim",
                "jsu retired claim",
                "jsu claim",
                "retirement pay plan",
                "japan pf",
                "japan pension"
        );

        addService(
                services,
                "CLAIM ALL",
                "claim all",
                "jsu claim all"
        );

        addService(
                services,
                "JSU BALANCE QUERY",
                "jsu balance query"
        );

        addService(
                services,
                "CoP TAR BOOK - DECK (ABLE BODY SEAFARER)",
                "cop tar book deck able body seafarer",
                "cop tarbook deck able body seafarer",
                "deck tar book",
                "deck tarbook"
        );

        addService(
                services,
                "PRE SEA RESIDENTIAL COURSES",
                "pre sea residential courses",
                "pre-sea residential courses",
                "pre sea residential course"
        );

        addService(
                services,
                "SEAFARER RESUME CREATION",
                "seafarer resume creation",
                "seafarer resume"
        );

        addService(
                services,
                "PANAMA TASCO COURSE + ENDORSEMENT",
                "panama tasco course endorsement",
                "panama tasco course endrosement",
                "panama tasco course"
        );

        addService(
                services,
                "PANAMA CHEMCO COURSE + ENDORSEMENT",
                "panama chemco course endorsement",
                "panama chemco course endrosement",
                "panama chemco course"
        );

        addService(
                services,
                "PANAMA ECDIS COURSE",
                "panama ecdis course"
        );

        addService(
                services,
                "PANAMA ARPA COURSE",
                "panama arpa course"
        );

        addService(
                services,
                "PANAMA FRB COURSE",
                "panama frb course"
        );

        addService(
                services,
                "PANAMA SECURITY AWARENESS COURSE",
                "panama security awareness course"
        );

        addService(
                services,
                "BOSIET COURSE",
                "bosiet course",
                "bosiet"
        );

        addService(
                services,
                "HUET COURSE",
                "huet course",
                "huet"
        );

        addService(
                services,
                "CONTAINER FAMILIARIZATION COURSE",
                "container familiarization course",
                "container familiarisation course"
        );

        addService(
                services,
                "SHIP'S COOK COURSE",
                "ships cook course",
                "ship cook course"
        );

        addService(
                services,
                "REFRESHER SHIP'S COOK COURSE",
                "refresher ships cook course",
                "refresher ship cook course"
        );

        addService(
                services,
                "FOOD HANDLING COURSE",
                "food handling course"
        );

        addService(
                services,
                "CRISIS MANAGEMENT COURSE",
                "crisis management course"
        );

        addService(
                services,
                "CROWD & CRISIS MANAGEMENT COURSE",
                "crowd crisis management course"
        );

        addService(
                services,
                "CRISIS MANAGEMENT & HUMAN BEHAVIOUR COURSE",
                "crisis management human behaviour course",
                "crisis management human behavior course"
        );

        addService(
                services,
                "GMDSS CoC",
                "gmdss coc"
        );

        addService(
                services,
                "CoP BASIC GAS DCE",
                "cop basic gas dce",
                "basic gas dce"
        );

        addService(
                services,
                "PANAMA PSSR COURSE",
                "panama pssr course"
        );

        addService(
                services,
                "CLASS II PREPARATORY (II MATE FG)",
                "class ii preparatory ii mate fg",
                "class 2 preparatory 2 mate fg"
        );

        addService(
                services,
                "CLASS IV PREPARATORY (IV ENGG FG)",
                "class iv preparatory iv engg fg",
                "class 4 preparatory 4 engg fg",
                "class iv preparatory iv engineer fg"
        );

        addService(
                services,
                "CLASS II CoC (II MATE FG)",
                "class ii coc ii mate fg",
                "class 2 coc 2 mate fg"
        );

        addService(
                services,
                "CLASS I CoC (CHIEF MATE FG)",
                "class i coc chief mate fg",
                "class 1 coc chief mate fg",
                "class i coc cheif mate fg",
                "class 1 coc cheif mate fg"
        );

        addService(
                services,
                "PANAMA PST COURSE",
                "panama pst course"
        );

        addService(
                services,
                "PANAMA FPFF COURSE",
                "panama fpff course"
        );

        addService(
                services,
                "CLASS II ASSESSMENT (DECK)",
                "class ii assessment deck",
                "class 2 assessment deck"
        );

        addService(
                services,
                "CLASS IV ASSESSMENT (ENGINE)",
                "class iv assessment engine",
                "class 4 assessment engine"
        );

        addService(
                services,
                "ETO ASSESSMENT",
                "eto assessment"
        );

        addService(
                services,
                "MASTER CoC (FG)",
                "master coc fg"
        );

        addService(
                services,
                "CHIEF ENGINEER CoC",
                "chief engineer coc",
                "cheif engineer coc"
        );

        addService(
                services,
                "PRE-SEA COURSES - FUNDAMENTAL",
                "pre sea courses fundamental"
        );

        addService(
                services,
                "OCTF DC APPLY",
                "octf dc apply"
        );

        addService(
                services,
                "AB CoP CERTIFICATE",
                "ab cop certificate"
        );

        addService(
                services,
                "BHARATKOSH PAYMENT",
                "bharatkosh payment"
        );

        addService(
                services,
                "POST-SEA MODULAR COURSES - SPECIALIZED",
                "post sea modular courses specialized"
        );

        addService(
                services,
                "UNIFORM & ACCESSORIES",
                "uniform accessories"
        );

        addService(
                services,
                "REPEATER GP RATING EXAM",
                "repeater gp rating exam"
        );

        addService(
                services,
                "PRE-SEA TRAINING COURSE",
                "pre sea training course"
        );

        addService(
                services,
                "PASSPORT UPDATION - DG PROFILE",
                "passport updation dg profile",
                "passport update dg profile"
        );

        addService(
                services,
                "ILO MEDICAL",
                "ilo medical"
        );

        addService(
                services,
                "POST-SEA MODULAR COURSES - FUNDAMENTAL",
                "post sea modular courses fundamental"
        );

        addService(
                services,
                "VALUE ADDED COURSES",
                "value added courses"
        );

        addService(
                services,
                "MOCK QUIZ",
                "mock quiz"
        );

        addService(
                services,
                "CoC TAR BOOK - ETO",
                "coc tar book eto",
                "coc tarbook eto"
        );

        addService(
                services,
                "CoP TAR BOOK - ENGINE",
                "cop tar book engine",
                "cop tarbook engine",
                "engine tar book",
                "engine tarbook"
        );

        addService(
                services,
                "CoP TAR BOOK - ETR",
                "cop tar book etr",
                "cop tarbook etr",
                "etr tar book",
                "etr tarbook"
        );

        addService(
                services,
                "E SAMUDRA",
                "e samudra",
                "esamudra"
        );

        addService(
                services,
                "PASSPORT RENEWAL",
                "passport renewal"
        );

        addService(
                services,
                "PASSPORT FRESH",
                "passport fresh",
                "fresh passport"
        );

        addService(
                services,
                "SIGN CORRECTION",
                "sign correction",
                "signature correction"
        );

        addService(
                services,
                "PROFILE UPDATE",
                "profile update"
        );

        addService(
                services,
                "WATCHKEEPING TAR BOOK - ENGINE SIDE (E WK TB)",
                "watchkeeping tar book engine side e wk tb",
                "watch keeping tar book engine side e wk tb"
        );

        addService(
                services,
                "PANAMA DECK WK + ENDORSEMENT",
                "panama deck wk endorsement",
                "panama deck wk endrosement"
        );

        addService(
                services,
                "PANAMA ENGINE WK + ENDORSEMENT",
                "panama engine wk endorsement",
                "panama engine wk endrosement"
        );

        addService(
                services,
                "BST",
                "bst"
        );

        addService(
                services,
                "PANAMA MEDICAL",
                "panama medical"
        );

        addService(
                services,
                "STSDSD",
                "stsdsd"
        );

        addService(
                services,
                "PANAMA EFA COURSE",
                "panama efa course"
        );

        addService(
                services,
                "PANAMA STSDSD COURSE",
                "panama stsdsd course"
        );

        addService(
                services,
                "PANAMA AFF COURSE",
                "panama aff course"
        );

        addService(
                services,
                "PANAMA MFA COURSE",
                "panama mfa course"
        );

        addService(
                services,
                "PANAMA PSCRB COURSE",
                "panama pscrb course"
        );

        addService(
                services,
                "PANAMA GMDSS COURSE + ENDORSEMENT",
                "panama gmdss course endorsement",
                "panama gmdss course endrosement"
        );

        addService(
                services,
                "PANAMA SSO COURSE + ENDORSEMENT",
                "panama sso course endorsement",
                "panama sso course endrosement"
        );

        addService(
                services,
                "PANAMA OIL & CHEMICAL COURSE + ENDORSEMENT",
                "panama oil chemical course endorsement",
                "panama oil chemical course endrosement"
        );

        addService(
                services,
                "PANAMA GAS COURSE + ENDORSEMENT",
                "panama gas course endorsement",
                "panama gas course endrosement"
        );

        addService(
                services,
                "PANAMA COOK COURSE + ENDORSEMENT",
                "panama cook course endorsement",
                "panama cook course endrosement"
        );

        addService(
                services,
                "H2S COURSE",
                "h2s course",
                "h 2 s course"
        );
        addService(
                services,
                "SID Card Fresh",
                "SID Card Fresh",
                "SID Card Fresh And Duplicate"
        );
        return services;
    }


    private static void addService(
            Map<String, String> services,
            String displayName,
            String... possibleNames
    ) {

        for (String possibleName : possibleNames) {
            services.put(
                    normalizeServiceName(possibleName),
                    displayName
            );
        }
    }


    private static boolean isEngineTarbook(String service) {

        return service.contains("cop tar book engine")
                || service.contains("cop tarbook engine")
                || service.contains("engine tar book")
                || service.contains("engine tarbook");
    }


    private static boolean isEtrTarbook(String service) {

        return service.contains("cop tar book etr")
                || service.contains("cop tarbook etr")
                || service.contains("etr tar book")
                || service.contains("etr tarbook");
    }


    private static boolean isDeckTarbook(String service) {

        return service.contains("cop tar book deck")
                || service.contains("cop tarbook deck")
                || service.contains("deck tar book")
                || service.contains("deck tarbook");
    }


    private static boolean isSpfoServiceName(String serviceName) {
        String service = normalizeServiceName(serviceName);

        return service.equals("spfo")
                || service.equals("seaman provident fund")
                || service.equals("seamen provident fund")
                || service.contains("seaman provident fund")
                || service.contains("seamen provident fund");
    }

    private static boolean isSpfoServiceCase(CaseRow caseRow) {
        if (caseRow == null) {
            return false;
        }

        if (isSpfoServiceName(caseRow.serviceName)) {
            return true;
        }

        String combined = normalizeServiceName(
                safe(caseRow.serviceName) + " " + safe(caseRow.remarks)
        );

        return combined.contains("spfo")
                || combined.contains("seaman provident fund")
                || combined.contains("seamen provident fund");
    }

    /**
     * Grievance follow-up belongs ONLY to SPFO Interest Problem cases.
     * Ordinary SPFO Balance cases must never enter Pending/Closed/Resolution History.
     */
    private static boolean needsSpfoGrievanceFollowUp(CaseRow caseRow) {
        return isSpfoInterestCase(caseRow);
    }


    private static boolean isJsuService(String serviceName) {
        String service = normalizeServiceName(serviceName);

        return service.equals("jsu")
                || service.equals("jsu retired")
                || service.equals("jsu retire")
                || service.equals("jsu retirement")
                || service.equals("jsu retirement claim")
                || service.equals("jsu retired claim")
                || service.equals("jsu claim")
                || service.equals("jsu claim all")
                || service.equals("claim all")
                || service.contains("japan pf")
                || service.contains("japan pension")
                || service.contains("retirement pay plan");
    }
    private static String normalizeServiceName(String serviceName) {
        if (isBlank(serviceName)) {
            return "";
        }

        return serviceName
                .trim()
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static void validateConfiguration() {
        if (isBlank(SPREADSHEET_ID)) {
            throw new IllegalStateException("SPREADSHEET_ID is missing.");
        }
    }

    private static String readAccessTokenIfAvailable() {
        String token = System.getenv("MM_ACCESS_TOKEN");

        if (isBlank(token)) {
            return "";
        }

        token = token.trim();

        if (token.toLowerCase(Locale.ROOT).startsWith("bearer ")) {
            token = token.substring(7).trim();
        }

        return token;
    }

    private static synchronized String getDashboardAccessToken()
            throws Exception {

        if (!isBlank(dashboardAccessToken)) {
            return dashboardAccessToken;
        }

        dashboardAccessToken = loginAndGetDashboardToken();
        return dashboardAccessToken;
    }

    private static synchronized String refreshDashboardAccessToken()
            throws Exception {

        System.out.println(
                "Dashboard token is missing or expired. Logging in automatically..."
        );

        dashboardAccessToken = loginAndGetDashboardToken();

        System.out.println(
                "New dashboard Bearer token captured successfully."
        );

        return dashboardAccessToken;
    }

    /**
     * SPFO TEST MODE ONLY.
     *
     * Fetches one Mariners Mentor case, finds its CDC number, opens the official
     * SPFO login page, selects Seafarer and fills only the User Id / CDC field.
     * Password and CAPTCHA are deliberately left for the operator to enter.
     */
    private static void runSpfoLoginTestFromCase(long caseId)
            throws Exception {
        // Test mode now auto-detects the SPFO action from the selected case.
        // Start with Interest mode OFF; runSpfoBalanceFlowFromCase() will switch
        // it ON automatically when the case is an SPFO Interest case.
        runSpfoBalanceFlowFromCase(caseId, false, false);
    }

    private static void runSpfoBalanceProductionCase(long caseId)
            throws Exception {
        runSpfoBalanceFlowFromCase(caseId, true, false);
    }

    private static void runSpfoInterestProductionCase(long caseId)
            throws Exception {
        runSpfoBalanceFlowFromCase(caseId, true, true);
    }

    private static void runSpfoBalanceFlowFromCase(
            long caseId,
            boolean productionSend,
            boolean interestMode
    ) throws Exception {

        if (caseId <= 0) {
            throw new IllegalArgumentException("SPFO case ID must be greater than zero.");
        }

        System.out.println();
        System.out.println("==============================================");
        if (productionSend) {
            System.out.println(
                    (interestMode ? "SPFO INTEREST PRODUCTION MODE | CASE " : "SPFO BALANCE PRODUCTION MODE | CASE ")
                            + caseId
            );
            System.out.println(interestMode
                    ? "CAPTCHA IS MANUAL - GRIEVANCE STATUS CHECK STARTS AFTER LOGIN"
                    : "CAPTCHA IS MANUAL - RESULT MAIL/WHATSAPP SEND AFTER LOGIN");
        } else {
            System.out.println("SPFO LOGIN TEST MODE | CASE " + caseId);
            boolean testWillSend = Boolean.parseBoolean(
                    config.getProperty("spfo.test.send.result", "false").trim()
            );
            System.out.println(testWillSend
                    ? "TEST SEND ENABLED - SPFO MAIL/WHATSAPP WILL SEND ONCE"
                    : "SAFE PREVIEW - NO MAIL / NO WHATSAPP WILL BE SENT");
        }
        System.out.println("==============================================");

        HttpResponse<String> caseResponse =
                sendAuthorizedGetWithRefresh(
                        CASE_DETAIL_API_BASE + caseId + "/",
                        "Case details API for SPFO test case " + caseId
                );

        JsonNode caseRoot = MAPPER.readTree(caseResponse.body());
        JsonNode caseNode = chooseCaseNode(caseRoot);
        CaseRow caseRow = MAPPER.treeToValue(caseNode, CaseRow.class);
        if (caseRow.id <= 0) {
            caseRow.id = caseId;
        }
        caseRow = ensureCaseRemarksLoaded(caseRow);

        // Route grievance ONLY for SPFO Interest Problem. SPFO Balance remains
        // a balance/passbook-only action. This also protects manual/test entry points.
        interestMode = interestMode || needsSpfoGrievanceFollowUp(caseRow);

        System.out.println(
                "SPFO CASE ROUTE | Case " + caseRow.id
                        + " | INTEREST_MODE=" + interestMode
                        + " | Service=" + safe(caseRow.serviceName)
                        + " | Remarks=" + safe(caseRow.remarks)
        );

        // IMPORTANT: For SPFO, CDC must come ONLY from the Mariners Mentor
        // "View Customer" profile. The dashboard View Customer screen is backed by
        // CUSTOMER_API_BASE, so read the CDC directly from that customer record.
        // Never use INDoS No. or a User Id written in the case remarks as SPFO CDC.
        if (caseRow.customer <= 0) {
            throw new IllegalStateException(
                    "Customer ID was not found for SPFO case " + caseId
                            + ". Cannot read CDC from View Customer."
            );
        }

        HttpResponse<String> customerResponse =
                sendAuthorizedGetWithRefresh(
                        CUSTOMER_API_BASE + caseRow.customer + "/",
                        "View Customer API for SPFO case " + caseId
                );

        JsonNode customerRoot = MAPPER.readTree(customerResponse.body());
        JsonNode customerNode = chooseCustomerNode(customerRoot);
        String cdcNo = findCdcNumber(customerNode);

        if (cdcNo.isBlank()) {
            throw new IllegalStateException(
                    "CDC No. was not found in View Customer for SPFO case " + caseId
                            + ". Open the customer profile and save CDC No. first."
            );
        }

        System.out.println(
                "SPFO CDC FOUND FROM VIEW CUSTOMER | " + maskCdcForLog(cdcNo)
        );
        System.out.println("SPFO CDC SOURCE = VIEW CUSTOMER ONLY");
        System.out.println("Opening SPFO login page...");

        WebDriver driver = createSpfoTestChromeDriver();

        try {
            driver.get(SPFO_LOGIN_URL);
            dumpSpfoDebugArtifacts(driver, caseId, "login-page");

            WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(30));

            // SPFO opens on the logged-out landing page first. The actual
            // User Type / User Id form is hidden until the orange Login
            // button is clicked. Open that panel before looking for fields.
            boolean loginFormAlreadyVisible = driver.findElements(By.tagName("select")).stream()
                    .anyMatch(element -> {
                        try {
                            return element.isDisplayed() && element.isEnabled();
                        } catch (Exception ignored) {
                            return false;
                        }
                    });

            if (!loginFormAlreadyVisible) {
                WebElement openLoginButton = wait.until(driverInstance -> {
                    List<By> loginLocators = List.of(
                            By.linkText("Login"),
                            By.xpath("//a[normalize-space()='Login']"),
                            By.xpath("//button[normalize-space()='Login']"),
                            By.xpath("//input[@type='button' and translate(@value,'abcdefghijklmnopqrstuvwxyz','ABCDEFGHIJKLMNOPQRSTUVWXYZ')='LOGIN']"),
                            By.xpath("//input[@type='submit' and translate(@value,'abcdefghijklmnopqrstuvwxyz','ABCDEFGHIJKLMNOPQRSTUVWXYZ')='LOGIN']")
                    );

                    for (By locator : loginLocators) {
                        for (WebElement element : driverInstance.findElements(locator)) {
                            try {
                                if (element.isDisplayed() && element.isEnabled()) {
                                    return element;
                                }
                            } catch (Exception ignored) {
                            }
                        }
                    }
                    return null;
                });

                try {
                    openLoginButton.click();
                } catch (Exception normalClickFailed) {
                    ((JavascriptExecutor) driver).executeScript("arguments[0].click();", openLoginButton);
                }

                System.out.println("SPFO LOGIN PANEL OPENED");
            }

            // Exact selectors confirmed from the current SPFO login HTML.
            WebElement userType = wait.until(
                    ExpectedConditions.elementToBeClickable(By.id("userType"))
            );
            Select select = new Select(userType);
            select.selectByValue("S"); // Seafarer

            WebElement userIdInput = wait.until(
                    ExpectedConditions.visibilityOfElementLocated(By.id("username"))
            );
            userIdInput.clear();
            userIdInput.sendKeys(cdcNo);

            // Test account password is constant for this SPFO test flow.
            WebElement passwordInput = wait.until(
                    ExpectedConditions.visibilityOfElementLocated(By.id("j_password"))
            );
            passwordInput.clear();
            passwordInput.sendKeys(config.getProperty("spfo.common.password", "test@1234").trim());

            // CAPTCHA itself stays manual. Make it much larger and move it into
            // the middle of the screen so it is easier to read and type.
            WebElement captchaImage = wait.until(
                    ExpectedConditions.visibilityOfElementLocated(By.id("captchaLabelId"))
            );
            WebElement captchaInput = wait.until(
                    ExpectedConditions.elementToBeClickable(By.id("captchaInputTxt"))
            );

            ((JavascriptExecutor) driver).executeScript(
                    "arguments[0].scrollIntoView({behavior:'smooth',block:'center'});" +
                            "arguments[0].style.height='95px';" +
                            "arguments[0].style.width='auto';" +
                            "arguments[0].style.maxWidth='360px';" +
                            "arguments[0].style.padding='8px';" +
                            "arguments[0].style.background='#ffffff';" +
                            "arguments[0].style.border='4px solid #ff6600';" +
                            "arguments[0].style.borderRadius='8px';" +
                            "arguments[0].style.boxShadow='0 0 18px rgba(0,0,0,.45)';" +
                            "arguments[0].style.imageRendering='auto';" +
                            "arguments[1].style.height='52px';" +
                            "arguments[1].style.fontSize='26px';" +
                            "arguments[1].style.fontWeight='700';" +
                            "arguments[1].style.width='260px';" +
                            "arguments[1].style.maxWidth='90vw';" +
                            "arguments[1].style.margin='14px auto 8px auto';" +
                            "arguments[1].style.border='3px solid #ff6600';" +
                            "arguments[1].style.textAlign='center';",
                    captchaImage, captchaInput
            );

            try {
                Thread.sleep(700);
            } catch (InterruptedException interruptedException) {
                Thread.currentThread().interrupt();
            }

            captchaInput.click();

            System.out.println("SPFO USER TYPE = SEAFARER");
            System.out.println("SPFO CDC / USER ID FILLED SUCCESSFULLY");
            System.out.println("SPFO PASSWORD FILLED SUCCESSFULLY");
            System.out.println("CAPTCHA ENLARGED - TYPE CAPTCHA ONLY, THEN CLICK LOGIN");
            System.out.println("SPFO browser will stay open for up to 10 minutes for CAPTCHA/login.");

            String initialUrl = driver.getCurrentUrl();
            WebDriverWait manualLoginWait = new WebDriverWait(driver, Duration.ofMinutes(10));

            try {
                manualLoginWait.until(driverInstance -> {
                    try {
                        String currentUrl = safe(driverInstance.getCurrentUrl());
                        String body = safe(driverInstance.findElement(By.tagName("body")).getText())
                                .toLowerCase(Locale.ROOT);

                        boolean leftLoginPage = !currentUrl.equalsIgnoreCase(initialUrl)
                                && !currentUrl.toLowerCase(Locale.ROOT).contains("login");
                        boolean loggedInText = body.contains("logout")
                                || body.contains("welcome")
                                || body.contains("dashboard");

                        return leftLoginPage || loggedInText;
                    } catch (Exception ignored) {
                        return false;
                    }
                });

                System.out.println("SPFO LOGIN SUCCESS DETECTED | Case " + caseId);

                if (interestMode) {
                    CustomerDetails resultCustomer = fetchCustomerDetails(caseRow.customer);

                    // SPFO Interest Problem is grievance-only. Do NOT send the ordinary
                    // SPFO balance while the grievance is open. Only check grievance status;
                    // when Resolved, forward the resolved PDF/result exactly once.
                    System.out.println(
                            "SPFO INTEREST PROBLEM - GRIEVANCE CHECK ONLY - NO OPEN/PENDING MESSAGE"
                                    + " | Case: " + caseRow.id
                    );

                    System.out.println(
                            "SPFO GRIEVANCE FOLLOW-UP START"
                                    + " | Case: " + caseRow.id
                                    + " | Pending -> Resolved -> View Details -> Resolution History -> PDF"
                    );

                    SpfoInterestResult interestResult = checkSpfoInterestGrievance(
                            driver,
                            caseRow
                    );

                    if (interestResult.pending()) {
                        System.out.println(
                                "SPFO GRIEVANCE STILL PENDING - KEEP MM CASE OPEN"
                                        + " | Case: " + caseRow.id
                        );
                    } else if (!interestResult.resolved()) {
                        System.out.println(
                                "SPFO RESOLVED GRIEVANCE NOT FOUND - KEEP MM CASE OPEN"
                                        + " | Case: " + caseRow.id
                        );
                    } else if (interestResult.attachments().isEmpty()) {
                        System.out.println(
                                "SPFO GRIEVANCE RESOLVED BUT PDF ATTACHMENT NOT FOUND - KEEP MM CASE OPEN"
                                        + " | Case: " + caseRow.id
                        );
                    } else if (isBlank(interestResult.balanceAmount())) {
                        System.out.println(
                                "SPFO RESOLVED PDF FOUND BUT BALANCE AMOUNT NOT READ - KEEP MM CASE OPEN"
                                        + " | Case: " + caseRow.id
                        );
                    } else {
                        boolean sendResult = productionSend || Boolean.parseBoolean(
                                config.getProperty("spfo.test.send.result", "false").trim()
                        );

                        if (!sendResult) {
                            System.out.println(
                                    "SPFO SAFE PREVIEW ONLY - RESOLVED PDF/BALANCE FOUND, NOTHING SENT"
                                            + " | Case: " + caseRow.id
                                            + " | Balance: " + interestResult.balanceAmount()
                            );
                        } else {
                            sendSpfoInterestResultAtMostOnce(
                                    caseRow,
                                    resultCustomer,
                                    interestResult
                            );

                            if (isSpfoFinalInterestStageComplete(
                                    caseRow,
                                    resultCustomer,
                                    loadSentHistory()
                            )) {
                                System.out.println(
                                        "SPFO FINAL PDF + WHATSAPP COMPLETED - CLOSING MM CASE"
                                                + " | Case: " + caseRow.id
                                );
                                closeMarinersMentorCaseAtMostOnce(caseRow);
                            }
                        }
                    }

                    logoutSpfoViaProfile(driver);
                    return;
                }

                // From here the SPFO Balance Check is fully automatic:
                // close the update popup -> open View PF / Passbook -> select FROM YEAR
                // -> read BALANCE rows -> read Available PF Balance.
                SpfoBalanceResult spfoResult = openSpfoPassbookAndReadBalance(
                        driver,
                        caseRow
                );

                if (spfoResult == null || spfoResult.yearlyBalances().isEmpty()) {
                    System.out.println("NO SPFO YEARLY BALANCE ROWS DETECTED - NOTHING SENT.");
                    dumpSpfoDebugArtifacts(driver, caseId, "balance-page-not-detected");
                } else {
                    System.out.println("SPFO BALANCE DETECTED:");
                    for (SpfoYearBalance row : spfoResult.yearlyBalances()) {
                        System.out.println("  " + row.year() + " = " + row.total());
                    }
                    System.out.println("  AVAILABLE PF BALANCE = " + spfoResult.totalAmount());
                    if (!spfoResult.availablePfYear().isBlank()) {
                        System.out.println("  AS PER YEAR = " + spfoResult.availablePfYear());
                    }

                    boolean sendResult = productionSend || Boolean.parseBoolean(
                            config.getProperty("spfo.test.send.result", "false").trim()
                    );

                    if (!sendResult) {
                        System.out.println("SAFE PREVIEW ONLY - spfo.test.send.result=false - NOTHING SENT.");
                    } else {
                        CustomerDetails resultCustomer = fetchCustomerDetails(caseRow.customer);
                        sendSpfoBalanceResultAtMostOnce(caseRow, resultCustomer, spfoResult);
                        System.out.println(
                                productionSend
                                        ? "SPFO PRODUCTION RESULT SEND FINISHED WITH PERMANENT NO-RESEND LOCK."
                                        : "SPFO TEST RESULT SEND FINISHED WITH PERMANENT NO-RESEND LOCK."
                        );
                    }

                    // Normal SPFO BALANCE CHECK: logout after reading/sending.
                    // Interest / Balance Update actions are deliberately NOT logged out here,
                    // because another action must be added before logout later.
                    if (isSpfoBalanceCase(caseRow) || isSpfoBalanceQuery(caseRow.serviceName)) {
                        logoutSpfoViaProfile(driver);
                    } else {
                        System.out.println("SPFO SESSION LEFT OPEN - NON-BALANCE ACTION MAY NEED ANOTHER STEP BEFORE LOGOUT.");
                    }
                }
            } catch (org.openqa.selenium.TimeoutException timeout) {
                System.out.println(
                        (productionSend ? "SPFO PRODUCTION" : "SPFO TEST")
                                + " ENDED AFTER 10 MINUTES - login success was not detected."
                );
                dumpSpfoDebugArtifacts(driver, caseId, "login-not-detected");
            }
        } finally {
            driver.quit();
        }
    }

    private static void dumpSpfoDebugArtifacts(
            WebDriver driver,
            long caseId,
            String stage
    ) {
        // Debug HTML/TXT dumps are OFF in normal production so the project does
        // not keep filling itself with spfo-debug files. Enable only when needed.
        if (config == null || !Boolean.parseBoolean(
                config.getProperty("spfo.debug.enabled", "false").trim())) {
            return;
        }
        try {
            Path debugDir = Path.of("spfo-debug").toAbsolutePath().normalize();
            Files.createDirectories(debugDir);
            String safeStage = safe(stage).replaceAll("[^A-Za-z0-9._-]+", "-");
            Path htmlFile = debugDir.resolve("spfo-case-" + caseId + "-" + safeStage + ".html");
            Path textFile = debugDir.resolve("spfo-case-" + caseId + "-" + safeStage + ".txt");

            Files.writeString(htmlFile, safe(driver.getPageSource()), StandardCharsets.UTF_8);

            String bodyText = "";
            try {
                bodyText = safe(driver.findElement(By.tagName("body")).getText());
            } catch (Exception ignored) {
            }
            Files.writeString(textFile, bodyText, StandardCharsets.UTF_8);

            System.out.println("SPFO DEBUG HTML SAVED | " + htmlFile);
            System.out.println("SPFO DEBUG TEXT SAVED | " + textFile);
        } catch (Exception debugError) {
            System.err.println("SPFO DEBUG SAVE FAILED | " + debugError.getMessage());
        }
    }

    private record SpfoYearBalance(
            String year,
            String seafarerContribution,
            String employerContribution,
            String voluntaryContribution,
            String exgratiaContribution,
            String pensionAnnuityContribution,
            String total
    ) {}

    private record SpfoLedgerRow(
            String financialYear,
            String typeOrFinancialYear,
            String yearInwardNo,
            String signOnDate,
            String signOffDate,
            String seafarerContribution,
            String employerContribution,
            String voluntaryContribution,
            String exgratiaContribution,
            String pensionAnnuityContribution,
            String total,
            String remarks
    ) {}

    private record SpfoBalanceResult(
            java.util.List<SpfoYearBalance> yearlyBalances,
            java.util.List<SpfoLedgerRow> ledgerRows,
            String totalAmount,
            String availablePfYear,
            byte[] officialLedgerPdf,
            String officialLedgerPdfFileName
    ) {}


    private record SpfoPdfAttachment(
            String fileName,
            byte[] bytes
    ) {}

    private record SpfoInterestResult(
            boolean pending,
            boolean resolved,
            String grievanceId,
            String balanceAmount,
            SpfoPdfAttachment attachment
    ) {
        java.util.List<SpfoPdfAttachment> attachments() {
            return attachment == null ? java.util.List.of() : java.util.List.of(attachment);
        }
    }

    /**
     * After a successful SPFO login:
     * 1) Grievance Management -> Show All Grievances.
     * 2) Pending is checked first; if an Interest grievance is still Pending, keep the MM case open.
     * 3) Then open the SPFO Resolved tab (the current SPFO UI keeps completed grievances here).
     * 4) Open the resolved grievance Details/Response page, confirm Status = Resolved and a
     *    Resolution History row with status Resolved.
     * 5) Download the PDF shown under ATTACHMENTS, extract BALANCE from it, and return it for
     *    the existing email/WhatsApp sender. The email attaches the same resolved PDF.
     */
    private static SpfoInterestResult checkSpfoInterestGrievance(
            WebDriver driver,
            CaseRow caseRow
    ) throws Exception {
        WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(30));
        closeSpfoUpdatePopup(driver);

        // Check Pending first so an unresolved Interest grievance is not treated as complete.
        driver.get("https://spfo.gov.in/spfo/seamangrievances.html?activeTab=Pending");
        wait.until(ExpectedConditions.presenceOfElementLocated(By.tagName("body")));
        dumpSpfoDebugArtifacts(driver, caseRow.id, "grievance-pending");

        WebElement pendingScope = driver.findElements(By.id("pendingTab")).stream()
                .filter(element -> {
                    try {
                        return element.isDisplayed();
                    } catch (Exception ignored) {
                        return false;
                    }
                })
                .findFirst()
                .orElse(driver.findElement(By.tagName("body")));

        boolean pendingInterestFound = false;
        for (WebElement row : pendingScope.findElements(By.cssSelector("tbody tr"))) {
            List<WebElement> cells = row.findElements(By.tagName("td"));
            if (cells.size() < 2) {
                continue;
            }
            String rowText = safe(row.getText()).trim().toLowerCase(Locale.ROOT);
            if (rowText.isBlank() || rowText.contains("no records found")) {
                continue;
            }
            // Interest is sometimes misspelled as "Intrest" on SPFO, so accept both.
            if (rowText.contains("interest") || rowText.contains("intrest")) {
                pendingInterestFound = true;
                break;
            }
        }

        if (pendingInterestFound) {
            System.out.println(
                    "SPFO PENDING TAB HAS INTEREST GRIEVANCE - KEEP CASE OPEN"
                            + " | Case: " + caseRow.id
            );
            return new SpfoInterestResult(true, false, "", "", null);
        }

        System.out.println("SPFO PENDING INTEREST CLEAR -> CHECKING RESOLVED TAB");

        // CURRENT SPFO FLOW (confirmed from the live screen): completed grievances are in Resolved.
        driver.get("https://spfo.gov.in/spfo/seamangrievances.html?activeTab=Resolved");
        wait.until(ExpectedConditions.presenceOfElementLocated(By.tagName("body")));
        dumpSpfoDebugArtifacts(driver, caseRow.id, "grievance-resolved");

        WebElement resolvedScope = driver.findElements(By.id("resolvedTab")).stream()
                .filter(element -> {
                    try {
                        return element.isDisplayed();
                    } catch (Exception ignored) {
                        return false;
                    }
                })
                .findFirst()
                .orElse(driver.findElement(By.tagName("body")));

        String resolvedText = safe(resolvedScope.getText()).toLowerCase(Locale.ROOT);
        if (resolvedText.contains("no records found")) {
            System.out.println("SPFO RESOLVED TAB: NO RECORDS FOUND | Case " + caseRow.id);
            return new SpfoInterestResult(false, false, "", "", null);
        }

        // Collect Interest-related resolved grievances first. If SPFO changes the title wording,
        // keep all numeric IDs as a fallback and inspect each Details page safely.
        java.util.ArrayList<String> interestIds = new java.util.ArrayList<>();
        java.util.ArrayList<String> fallbackIds = new java.util.ArrayList<>();

        for (WebElement row : resolvedScope.findElements(By.cssSelector("tbody tr"))) {
            List<WebElement> cells = row.findElements(By.tagName("td"));
            if (cells.size() < 2) {
                continue;
            }

            String id = safe(cells.get(0).getText()).trim();
            String rowText = safe(row.getText()).trim().toLowerCase(Locale.ROOT);
            if (!id.matches("\\d+") || rowText.contains("no records found")) {
                continue;
            }

            if (!fallbackIds.contains(id)) {
                fallbackIds.add(id);
            }
            if ((rowText.contains("interest") || rowText.contains("intrest"))
                    && !interestIds.contains(id)) {
                interestIds.add(id);
            }
        }

        java.util.ArrayList<String> grievanceIds =
                interestIds.isEmpty() ? fallbackIds : interestIds;

        if (grievanceIds.isEmpty()) {
            System.out.println("SPFO RESOLVED TAB: GRIEVANCE ROW NOT FOUND | Case " + caseRow.id);
            return new SpfoInterestResult(false, false, "", "", null);
        }

        // SPFO's current eye/View action opens:
        // /spfo/grievances/response/{grievanceId}.html?activeTab=Resolved
        for (String grievanceId : grievanceIds) {
            String responseUrl =
                    "https://spfo.gov.in/spfo/grievances/response/"
                            + grievanceId
                            + ".html?activeTab=Resolved";

            driver.get(responseUrl);
            wait.until(ExpectedConditions.presenceOfElementLocated(By.tagName("body")));
            dumpSpfoDebugArtifacts(
                    driver,
                    caseRow.id,
                    "grievance-resolved-response-" + grievanceId
            );

            String bodyText = safe(driver.findElement(By.tagName("body")).getText());
            String bodyLower = bodyText.toLowerCase(Locale.ROOT);

            // Details table itself must say Status Resolved.
            boolean detailsResolved = false;
            for (WebElement row : driver.findElements(By.cssSelector("table tr"))) {
                List<WebElement> cells = row.findElements(By.tagName("td"));
                for (int i = 0; i + 1 < cells.size(); i++) {
                    String label = safe(cells.get(i).getText()).trim();
                    String value = safe(cells.get(i + 1).getText()).trim();
                    if (label.equalsIgnoreCase("STATUS") && value.equalsIgnoreCase("Resolved")) {
                        detailsResolved = true;
                        break;
                    }
                }
                if (detailsResolved) {
                    break;
                }
            }

            // Resolution History must also contain a real resolved row/date.
            boolean historyResolved = false;
            String resolvedDate = "";
            for (WebElement row : driver.findElements(By.cssSelector("table tbody tr"))) {
                List<WebElement> cells = row.findElements(By.tagName("td"));
                if (cells.size() < 2) {
                    continue;
                }

                String date = safe(cells.get(0).getText()).trim();
                String status = safe(cells.get(1).getText()).trim();
                if (!date.isBlank() && status.equalsIgnoreCase("Resolved")) {
                    historyResolved = true;
                    resolvedDate = date;
                    break;
                }
            }

            if (!detailsResolved && !bodyLower.contains("status resolved")) {
                System.out.println(
                        "SPFO RESOLVED LIST ITEM DETAILS NOT CONFIRMED"
                                + " | Case: " + caseRow.id
                                + " | Grievance ID: " + grievanceId
                );
                continue;
            }

            if (!historyResolved) {
                System.out.println(
                        "SPFO RESOLUTION HISTORY NOT YET RESOLVED"
                                + " | Case: " + caseRow.id
                                + " | Grievance ID: " + grievanceId
                );
                continue;
            }

            System.out.println(
                    "SPFO RESOLVED CONFIRMED"
                            + " | Case: " + caseRow.id
                            + " | Grievance ID: " + grievanceId
                            + " | Resolved Date: " + resolvedDate
            );

            // The current page shows the PDF under ATTACHMENTS. Use broad but safe selectors so
            // this survives small SPFO HTML changes.
            List<WebElement> attachmentLinks = driver.findElements(
                    By.cssSelector(
                            "a[href$='.pdf'], a[href*='.pdf?'], "
                                    + "a[href*='/grievance/attachment/'], "
                                    + "a[href*='attachment/download'], a[href*='download']"
                    )
            );

            java.util.ArrayList<String> hrefs = new java.util.ArrayList<>();
            java.util.ArrayList<String> names = new java.util.ArrayList<>();

            for (WebElement link : attachmentLinks) {
                String href = safe(link.getAttribute("href")).trim();
                String name = safe(link.getText()).trim();
                String combined = (href + " " + name).toLowerCase(Locale.ROOT);

                if (href.isBlank()
                        || href.startsWith("javascript:")
                        || (!combined.contains(".pdf") && !combined.contains("attachment"))) {
                    continue;
                }
                if (hrefs.contains(href)) {
                    continue;
                }

                if (name.isBlank()) {
                    name = "SPFO_Interest_Update.pdf";
                }
                if (!name.toLowerCase(Locale.ROOT).endsWith(".pdf")) {
                    name = name + ".pdf";
                }

                hrefs.add(href);
                names.add(name);
            }

            if (hrefs.isEmpty()) {
                System.out.println(
                        "SPFO RESOLVED PAGE HAS NO PDF ATTACHMENT"
                                + " | Case: " + caseRow.id
                                + " | Grievance ID: " + grievanceId
                );
                continue;
            }

            SpfoPdfAttachment selectedAttachment = null;
            String balance = "";

            // Latest attachment first. The exact downloaded PDF is passed to the mail sender.
            for (int i = hrefs.size() - 1; i >= 0; i--) {
                try {
                    byte[] pdf = downloadSpfoAuthenticatedPdf(driver, hrefs.get(i));
                    Path savedFile = saveSpfoInterestAttachment(
                            caseRow.id,
                            names.get(i),
                            pdf
                    );

                    String foundBalance = extractSpfoInterestBalance(pdf);

                    System.out.println(
                            "SPFO RESOLVED PDF DOWNLOADED"
                                    + " | File: " + savedFile
                                    + " | Bytes: " + pdf.length
                                    + " | PDF Balance: "
                                    + (foundBalance.isBlank() ? "NOT FOUND" : foundBalance)
                    );

                    if (!foundBalance.isBlank()) {
                        selectedAttachment = new SpfoPdfAttachment(
                                savedFile.getFileName().toString(),
                                pdf
                        );
                        balance = foundBalance;
                        break;
                    }
                } catch (Exception e) {
                    System.err.println(
                            "SPFO RESOLVED PDF READ FAILED"
                                    + " | " + names.get(i)
                                    + " | " + safe(e.getMessage())
                    );
                }
            }

            if (selectedAttachment != null) {
                return new SpfoInterestResult(
                        false,
                        true,
                        grievanceId,
                        balance,
                        selectedAttachment
                );
            }
        }

        return new SpfoInterestResult(false, false, "", "", null);
    }

    private static byte[] downloadSpfoAuthenticatedPdf(
            WebDriver driver,
            String href
    ) throws Exception {
        String cookieHeader = driver.manage().getCookies().stream()
                .map(cookie -> cookie.getName() + "=" + cookie.getValue())
                .reduce((a, b) -> a + "; " + b)
                .orElse("");

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(href))
                .timeout(Duration.ofSeconds(60))
                .header("Accept", "application/pdf,*/*")
                .header("Referer", safe(driver.getCurrentUrl()))
                .GET();

        if (!cookieHeader.isBlank()) {
            builder.header("Cookie", cookieHeader);
        }

        HttpResponse<byte[]> response = HTTP_CLIENT.send(
                builder.build(),
                HttpResponse.BodyHandlers.ofByteArray()
        );

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("SPFO attachment HTTP " + response.statusCode());
        }

        byte[] bytes = response.body();
        if (bytes == null || bytes.length < 5
                || bytes[0] != '%' || bytes[1] != 'P' || bytes[2] != 'D' || bytes[3] != 'F') {
            throw new IOException("SPFO attachment is not a valid PDF.");
        }
        return bytes;
    }

    private static Path saveSpfoInterestAttachment(
            long caseId,
            String originalFileName,
            byte[] pdfBytes
    ) throws IOException {
        if (pdfBytes == null || pdfBytes.length < 5) {
            throw new IOException("SPFO Interest attachment is empty.");
        }

        String cleanName = safe(originalFileName).trim();
        if (cleanName.isBlank()) {
            cleanName = "SPFO_Interest_Update.pdf";
        }
        cleanName = cleanName.replaceAll("[\\\\/:*?\"<>|]+", "_");
        if (!cleanName.toLowerCase(Locale.ROOT).endsWith(".pdf")) {
            cleanName = cleanName + ".pdf";
        }

        Path dir = Path.of(
                "spfo-interest-downloads",
                "case-" + caseId
        ).toAbsolutePath().normalize();
        Files.createDirectories(dir);

        Path target = dir.resolve(cleanName);
        Files.write(target, pdfBytes);
        return target;
    }

    /**
     * Reads the FINAL total from the latest BALANCE row.
     * Example row from the SPFO ledger:
     * BALANCE 280,526.50 280,526.50 359,218.00 0.00 0.00 920,271.00
     * -> 920,271.00
     *
     * Important: the first number after BALANCE is NOT the total. The total is
     * normally the last/largest monetary value in that BALANCE row.
     */
    private static String extractSpfoInterestBalance(byte[] pdfBytes) throws Exception {
        if (pdfBytes == null || pdfBytes.length < 5) {
            return "";
        }

        try (org.apache.pdfbox.pdmodel.PDDocument document =
                     org.apache.pdfbox.Loader.loadPDF(pdfBytes)) {
            String text = new org.apache.pdfbox.text.PDFTextStripper().getText(document);
            if (text == null || text.isBlank()) {
                return "";
            }

            String normalized = text
                    .replace('\u00A0', ' ')
                    .replaceAll("[\\t ]+", " ");

            Pattern moneyPattern = Pattern.compile(
                    "(?<!\\d)([0-9]{1,3}(?:,[0-9]{3})+(?:\\.[0-9]{1,2})?|[0-9]+(?:\\.[0-9]{1,2})?)(?!\\d)"
            );

            // Best case: PDFBox keeps each BALANCE row on one text line.
            String[] lines = normalized.split("\\R");
            for (int i = lines.length - 1; i >= 0; i--) {
                String line = lines[i];
                if (!line.toUpperCase(Locale.ROOT).contains("BALANCE")) {
                    continue;
                }

                String after = line.substring(
                        line.toUpperCase(Locale.ROOT).lastIndexOf("BALANCE") + "BALANCE".length()
                );

                String best = largestMoneyToken(after, moneyPattern);
                if (!best.isBlank()) {
                    return best;
                }
            }

            // Fallback for PDFs where a table row is broken into multiple text lines.
            // Use the LAST BALANCE occurrence and inspect a short text window after it.
            String upper = normalized.toUpperCase(Locale.ROOT);
            int lastBalance = upper.lastIndexOf("BALANCE");
            if (lastBalance >= 0) {
                int end = Math.min(normalized.length(), lastBalance + 420);
                String window = normalized.substring(lastBalance + "BALANCE".length(), end);
                String best = largestMoneyToken(window, moneyPattern);
                if (!best.isBlank()) {
                    return best;
                }
            }

            return "";
        }
    }

    private static String largestMoneyToken(
            String text,
            Pattern moneyPattern
    ) {
        if (text == null || text.isBlank()) {
            return "";
        }

        Matcher matcher = moneyPattern.matcher(text);
        java.math.BigDecimal largest = null;
        String largestRaw = "";

        while (matcher.find()) {
            String raw = matcher.group(1).trim();
            try {
                java.math.BigDecimal value = new java.math.BigDecimal(raw.replace(",", ""));

                // Ignore obvious financial-year fragments such as 2026 / 2027
                // unless there is no larger monetary value in the BALANCE row.
                if (largest == null || value.compareTo(largest) > 0) {
                    largest = value;
                    largestRaw = raw;
                }
            } catch (Exception ignored) {
            }
        }

        return largestRaw;
    }

    private static void sendSpfoInterestResultAtMostOnce(
            CaseRow caseRow,
            CustomerDetails customer,
            SpfoInterestResult result
    ) throws Exception {
        if (caseRow == null || customer == null || result == null || !result.resolved()) {
            throw new IllegalArgumentException("SPFO Interest result/case/customer is missing.");
        }
        if (result.attachment() == null || result.attachment().bytes() == null
                || result.attachment().bytes().length < 5) {
            throw new IllegalStateException("SPFO Interest resolved PDF not found. NOTHING SENT.");
        }
        if (isBlank(result.balanceAmount())) {
            throw new IllegalStateException("BALANCE amount not found in resolved PDF. NOTHING SENT.");
        }
        if (isClosedJsuOrSpfoNow(caseRow)) {
            System.out.println("SPFO INTEREST SEND SKIPPED - MM CASE CLOSED | Case: " + caseRow.id);
            return;
        }

        Properties sent = loadSentHistory();
        String emailSentKey = caseRow.id + ".spfo.interest.mail";
        String whatsappSentKey = caseRow.id + ".spfo.interest.whatsapp";

        // TEST-ONLY override: when explicitly enabled, resend the Interest result
        // even if Case 7410 was already marked sent in persistent history.
        // Production mode is never affected because isSpfoTestOnlyMode() must be true.
        boolean forceTestResend = isSpfoTestOnlyMode()
                && Boolean.parseBoolean(
                config.getProperty("spfo.test.force.resend", "false").trim()
        );

        if (forceTestResend) {
            System.out.println(
                    "SPFO TEST FORCE RESEND ENABLED - IGNORING OLD INTEREST MAIL/WHATSAPP SENT FLAGS"
                            + " | Case: " + caseRow.id
            );
        }

        // Create the watermarked PDF once and physically save it. The exact same
        // bytes are attached to the candidate email.
        byte[] watermarked = PdfWatermarkService.addMarinersMentorInterestWatermark(
                result.attachment().bytes()
        );

        Path watermarkedFile = saveSpfoInterestAttachment(
                caseRow.id,
                "Mariners_Mentor_SPFO_Interest_Update.pdf",
                watermarked
        );

        System.out.println(
                "SPFO INTEREST WATERMARKED PDF SAVED"
                        + " | Case: " + caseRow.id
                        + " | File: " + watermarkedFile
        );

        Exception firstFailure = null;

        // EMAIL: no resend after a successful send. A failed SMTP attempt is NOT
        // permanently locked, so it can be retried safely on the next bot run.
        if (isBlank(customer.email)) {
            System.out.println("SPFO INTEREST EMAIL SKIPPED - EMAIL MISSING | Case " + caseRow.id);
        } else if (!forceTestResend && sent.containsKey(emailSentKey)) {
            System.out.println("SPFO INTEREST EMAIL SKIPPED - ALREADY SENT | Case " + caseRow.id);
        } else {
            try {
                ZohoMailService.sendSpfoInterestUpdateMail(
                        config,
                        getSurnameFollowedByFirstName(caseRow, customer),
                        customer.email,
                        result.balanceAmount(),
                        watermarked,
                        watermarkedFile.getFileName().toString()
                );

                rememberSent(sent, emailSentKey);
                System.out.println(
                        "SPFO INTEREST PDF MAIL SENT"
                                + " | Case: " + caseRow.id
                                + " | Balance: " + result.balanceAmount()
                );
            } catch (Exception e) {
                firstFailure = e;
                System.err.println(
                        "SPFO INTEREST EMAIL FAILED - WILL BE RETRYABLE"
                                + " | Case: " + caseRow.id
                                + " | " + safe(e.getMessage())
                );
            }
        }

        // WHATSAPP: send only the approved template with Candidate Name + Balance.
        // As with email, successful sends are remembered; failed sends remain retryable.
        String phone = normalizePhone(customer.phone);
        if (isBlank(phone)) {
            System.out.println("SPFO INTEREST WHATSAPP SKIPPED - PHONE MISSING | Case " + caseRow.id);
        } else if (!forceTestResend && sent.containsKey(whatsappSentKey)) {
            System.out.println("SPFO INTEREST WHATSAPP SKIPPED - ALREADY SENT | Case " + caseRow.id);
        } else {
            try {
                WhatsAppService.sendSpfoInterestUpdateMessage(
                        config,
                        phone,
                        getSurnameFollowedByFirstName(caseRow, customer),
                        result.balanceAmount()
                );

                rememberSent(sent, whatsappSentKey);
                System.out.println(
                        "SPFO INTEREST WHATSAPP ACCEPTED BY META"
                                + " | Case: " + caseRow.id
                                + " | Balance: " + result.balanceAmount()
                );
            } catch (Exception e) {
                if (firstFailure == null) {
                    firstFailure = e;
                }
                System.err.println(
                        "SPFO INTEREST WHATSAPP FAILED - WILL BE RETRYABLE"
                                + " | Case: " + caseRow.id
                                + " | " + safe(e.getMessage())
                );
            }
        }

        if (firstFailure != null) {
            throw firstFailure;
        }
    }

    private static void closeMarinersMentorCaseAtMostOnce(
            CaseRow caseRow
    ) throws Exception {
        if (caseRow == null || caseRow.id <= 0) {
            return;
        }

        Properties sent = loadSentHistory();
        String closedKey = caseRow.id + ".spfo.mm.case.closed";

        if (sent.containsKey(closedKey)) {
            System.out.println("MM CASE CLOSE SKIPPED - ALREADY RECORDED | Case " + caseRow.id);
            return;
        }

        if (!isCaseStillOpen(caseRow)) {
            rememberSent(sent, closedKey);
            System.out.println("MM CASE ALREADY CLOSED | Case " + caseRow.id);
            return;
        }

        if (!closeMarinersMentorCaseInDashboard(caseRow.id)) {
            throw new IllegalStateException(
                    "Could not confirm Mariners Mentor case closure for case " + caseRow.id
            );
        }

        rememberSent(sent, closedKey);
        System.out.println("MARINERS MENTOR CASE CLOSED | Case " + caseRow.id);
    }

    /**
     * Headless dashboard close flow. The normal dashboard credentials from
     * config.properties are used. This is deliberately performed only AFTER the
     * resolved SPFO PDF email and PDF-balance WhatsApp have both completed.
     */
    private static boolean closeMarinersMentorCaseInDashboard(long caseId) {
        WebDriver driver = null;
        try {
            driver = createAutomationChromeDriver();
            WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(35));

            driver.get("https://dashboard.marinersmentor.com/auth/sign-in");
            wait.until(ExpectedConditions.visibilityOfElementLocated(By.name("email")))
                    .sendKeys(requiredConfig("dashboard.email"));
            driver.findElement(By.name("password"))
                    .sendKeys(requiredConfig("dashboard.password"));
            driver.findElement(By.cssSelector("button[type='submit']")).click();

            wait.until(webDriver -> {
                String url = safe(webDriver.getCurrentUrl());
                return !url.contains("/auth/sign-in");
            });

            driver.get(
                    "https://dashboard.marinersmentor.com/dashboard/case/view?caseId="
                            + caseId
            );
            wait.until(ExpectedConditions.presenceOfElementLocated(By.tagName("body")));

            // If the API/UI already says closed, do not click anything.
            String currentStatus = fetchMarinersMentorCaseStatus(caseId);
            if (isClosedCaseStatus(currentStatus)) {
                return true;
            }

            List<WebElement> closeButtons = driver.findElements(
                    By.xpath("//button[normalize-space(.)='Close Case' or .//*[normalize-space(.)='Close Case']]")
            );

            WebElement closeButton = closeButtons.stream()
                    .filter(element -> {
                        try {
                            return element.isDisplayed() && element.isEnabled();
                        } catch (Exception ignored) {
                            return false;
                        }
                    })
                    .findFirst()
                    .orElse(null);

            if (closeButton == null) {
                String body = safe(driver.findElement(By.tagName("body")).getText()).toLowerCase(Locale.ROOT);
                return body.contains("closed");
            }

            try {
                closeButton.click();
            } catch (Exception e) {
                ((JavascriptExecutor) driver).executeScript("arguments[0].click();", closeButton);
            }

            try {
                Thread.sleep(800);
            } catch (InterruptedException interruptedException) {
                Thread.currentThread().interrupt();
            }

            // Some dashboard builds close immediately; others show a MUI dialog.
            WebElement dialog = driver.findElements(By.cssSelector("[role='dialog'], .MuiDialog-root"))
                    .stream()
                    .filter(element -> {
                        try {
                            return element.isDisplayed();
                        } catch (Exception ignored) {
                            return false;
                        }
                    })
                    .findFirst()
                    .orElse(null);

            if (dialog != null) {
                // If a reason/remark textarea is present, fill a clear audit note.
                for (WebElement textarea : dialog.findElements(By.tagName("textarea"))) {
                    try {
                        if (textarea.isDisplayed() && textarea.isEnabled()
                                && safe(textarea.getAttribute("value")).isBlank()) {
                            textarea.sendKeys("SPFO resolved PDF and balance update forwarded to candidate.");
                        }
                    } catch (Exception ignored) {
                    }
                }

                WebElement confirm = null;
                String[] confirmTexts = {"Close Case", "Confirm", "Yes", "Close", "Submit"};
                for (String text : confirmTexts) {
                    List<WebElement> buttons = dialog.findElements(
                            By.xpath(".//button[normalize-space(.)='" + text + "']")
                    );
                    confirm = buttons.stream()
                            .filter(element -> {
                                try {
                                    return element.isDisplayed() && element.isEnabled();
                                } catch (Exception ignored) {
                                    return false;
                                }
                            })
                            .findFirst()
                            .orElse(null);
                    if (confirm != null) {
                        break;
                    }
                }

                if (confirm == null) {
                    System.err.println("MM CASE CLOSE DIALOG FOUND BUT CONFIRM BUTTON NOT IDENTIFIED | Case " + caseId);
                    return false;
                }

                try {
                    confirm.click();
                } catch (Exception e) {
                    ((JavascriptExecutor) driver).executeScript("arguments[0].click();", confirm);
                }
            }

            // Give the dashboard/API a moment to persist the status.
            for (int attempt = 0; attempt < 10; attempt++) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException interruptedException) {
                    Thread.currentThread().interrupt();
                    break;
                }

                String status = fetchMarinersMentorCaseStatus(caseId);
                if (isClosedCaseStatus(status)) {
                    return true;
                }
            }

            // Final UI fallback if the API status field is absent in this build.
            driver.navigate().refresh();
            wait.until(ExpectedConditions.presenceOfElementLocated(By.tagName("body")));
            String body = safe(driver.findElement(By.tagName("body")).getText()).toLowerCase(Locale.ROOT);
            boolean stillHasCloseButton = driver.findElements(
                    By.xpath("//button[normalize-space(.)='Close Case' or .//*[normalize-space(.)='Close Case']]")
            ).stream().anyMatch(element -> {
                try {
                    return element.isDisplayed();
                } catch (Exception ignored) {
                    return false;
                }
            });

            return body.contains("closed") && !stillHasCloseButton;

        } catch (Exception e) {
            System.err.println(
                    "MARINERS MENTOR CASE CLOSE FAILED"
                            + " | Case: " + caseId
                            + " | " + safe(e.getMessage())
            );
            return false;
        } finally {
            if (driver != null) {
                try {
                    driver.quit();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private static String fetchMarinersMentorCaseStatus(long caseId) {
        try {
            HttpResponse<String> response = sendAuthorizedGetWithRefresh(
                    CASE_DETAIL_API_BASE + caseId + "/",
                    "Case details API for close verification " + caseId
            );
            JsonNode node = chooseCaseNode(MAPPER.readTree(response.body()));
            return findFirstText(
                    node,
                    "status",
                    "case_status",
                    "caseStatus",
                    "status_name",
                    "statusName"
            );
        } catch (Exception ignored) {
            return "";
        }
    }

    private static boolean isClosedCaseStatus(String status) {
        String normalized = normalizeServiceName(status);
        return normalized.contains("closed")
                || normalized.contains("complete")
                || normalized.contains("abandon")
                || normalized.contains("cancel");
    }

    /**
     * Complete SPFO balance path after login.
     *
     * 1. Close the "Please update your email Id..." popup.
     * 2. Open View PF / Passbook.
     * 3. Select the FROM YEAR dropdown.
     *    - If case remarks contain a financial year, use it.
     *    - Otherwise select the oldest available year so the complete history is loaded.
     * 4. Read only BALANCE rows (not O.B rows).
     * 5. Read Available PF Balance shown at the bottom.
     */
    private static SpfoBalanceResult openSpfoPassbookAndReadBalance(
            WebDriver driver,
            CaseRow caseRow
    ) throws Exception {

        WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(30));

        closeSpfoUpdatePopup(driver);

        boolean opened = false;

        // First try the visible View PF tile/link exactly as the dashboard shows it.
        for (By locator : List.of(
                By.xpath("//a[normalize-space()='View PF']"),
                By.xpath("//*[normalize-space()='View PF']"),
                By.cssSelector("a[href*='viewPassbook.html']")
        )) {
            try {
                for (WebElement el : driver.findElements(locator)) {
                    if (!el.isDisplayed()) {
                        continue;
                    }
                    try {
                        el.click();
                    } catch (Exception e) {
                        ((JavascriptExecutor) driver).executeScript("arguments[0].click();", el);
                    }
                    opened = true;
                    break;
                }
            } catch (Exception ignored) {
            }
            if (opened) {
                break;
            }
        }

        // Reliable fallback: use the confirmed passbook page URL.
        if (!opened) {
            driver.get("https://spfo.gov.in/spfo/view/viewPassbook.html");
        }

        wait.until(d -> safe(d.getCurrentUrl()).toLowerCase(Locale.ROOT).contains("viewpassbook"));

        String selectedYear = selectConfiguredOrLatestSpfoYear(driver);

        System.out.println("SPFO YEAR SELECTED = " + selectedYear);

        // Selecting FROM YEAR refreshes/redraws the passbook table.
        // Do not require a BALANCE row for the exact requested start year because a
        // seafarer may have joined later. Example: FROM YEAR 2009-10 may correctly
        // return the first actual ledger year as 2020-21. Wait until any real ledger
        // year + BALANCE row has loaded, then capture every available year from the
        // requested FROM YEAR onward.
        WebDriverWait tableWait = new WebDriverWait(driver, Duration.ofSeconds(45));
        tableWait.until(d -> {
            try {
                Object result = ((JavascriptExecutor) d).executeScript(
                        "var t=document.getElementById('viewPassBookTable');" +
                                "if(!t) return false;" +
                                "var rows=Array.from(t.querySelectorAll('tr'));" +
                                "var sawYear=false, sawBalance=false;" +
                                "for (var i=0;i<rows.length;i++) {" +
                                "  var cells=Array.from(rows[i].querySelectorAll('th,td')).map(function(c){return (c.innerText||'').trim();});" +
                                "  if(!cells.length) continue;" +
                                "  if(/^\\d{4}[-–/]\\d{2,4}$/.test(cells[0])) sawYear=true;" +
                                "  if((cells[0]||'').toUpperCase()==='BALANCE') sawBalance=true;" +
                                "}" +
                                "return sawYear && sawBalance;"
                );
                return Boolean.TRUE.equals(result);
            } catch (Exception ignored) {
                return false;
            }
        });

        // SPFO/DataTables may paginate the passbook. Expand it before taking the
        // snapshot so all financial-year BALANCE rows are available to Java.
        showAllSpfoPassbookRows(driver);

        // Allow the DataTable redraw to settle, then take a DOM snapshot.
        // captureSpfoBalanceFromPassbook() below uses JavaScript snapshots instead
        // of holding WebElement row references, so DataTables cannot cause a
        // StaleElementReferenceException while we read the table.
        Thread.sleep(900L);

        dumpSpfoDebugArtifacts(
                driver,
                caseRow == null ? 0 : caseRow.id,
                "passbook-loaded"
        );

        SpfoBalanceResult captured = captureSpfoBalanceFromPassbook(driver, selectedYear);

        // Download the exact official SPFO ledger PDF shown by the site's
        // PRINT DOCUMENT button. This is downloaded through the logged-in
        // browser session, so it is the same official ledger document the
        // operator sees in Chrome (not a reconstructed HTML/PDF).
        byte[] officialPdf = downloadOfficialSpfoLedgerPdf(driver, selectedYear);
        String officialPdfFileName = "Seafarer_Ledger_Document.pdf";

        System.out.println(
                "SPFO OFFICIAL LEDGER PDF CAPTURED"
                        + " | File: " + officialPdfFileName
                        + " | Bytes: " + officialPdf.length
        );

        return new SpfoBalanceResult(
                captured.yearlyBalances(),
                captured.ledgerRows(),
                captured.totalAmount(),
                captured.availablePfYear(),
                officialPdf,
                officialPdfFileName
        );
    }

    private static byte[] downloadOfficialSpfoLedgerPdf(
            WebDriver driver,
            String financialYear
    ) throws Exception {

        String normalizedYear = normalizeSpfoYear(financialYear);
        if (!normalizedYear.matches("(?:19|20)\\d{2}-\\d{2}")) {
            throw new IllegalStateException(
                    "SPFO official PDF cannot be downloaded because financial year is invalid: "
                            + financialYear
            );
        }

        // Confirmed by SPFO's PRINT DOCUMENT flow:
        // /spfo/report/viewPassbook/2025-26.html renders Seafarer_Ledger_Document.pdf
        String pdfUrl =
                "https://spfo.gov.in/spfo/report/viewPassbook/"
                        + normalizedYear
                        + ".html";

        Object rawResult = ((JavascriptExecutor) driver).executeAsyncScript(
                "var url=arguments[0];" +
                        "var done=arguments[arguments.length-1];" +
                        "fetch(url,{credentials:'include',cache:'no-store'})" +
                        ".then(function(r){" +
                        "  return r.arrayBuffer().then(function(buf){" +
                        "    var bytes=new Uint8Array(buf);" +
                        "    var binary='';" +
                        "    var chunk=0x8000;" +
                        "    for(var i=0;i<bytes.length;i+=chunk){" +
                        "      binary += String.fromCharCode.apply(null,bytes.subarray(i,Math.min(i+chunk,bytes.length)));" +
                        "    }" +
                        "    done(JSON.stringify({status:r.status,type:(r.headers.get('content-type')||''),data:btoa(binary)}));" +
                        "  });" +
                        "})" +
                        ".catch(function(e){done(JSON.stringify({status:0,type:'',error:String(e)}));});",
                pdfUrl
        );

        JsonNode resultNode = MAPPER.readTree(safe(String.valueOf(rawResult)));
        int status = resultNode.path("status").asInt(0);
        String contentType = resultNode.path("type").asText("");
        String error = resultNode.path("error").asText("");
        String base64 = resultNode.path("data").asText("");

        if (status < 200 || status >= 300 || base64.isBlank()) {
            throw new IllegalStateException(
                    "SPFO official ledger PDF download failed"
                            + " | HTTP " + status
                            + (contentType.isBlank() ? "" : " | Content-Type: " + contentType)
                            + (error.isBlank() ? "" : " | " + error)
            );
        }

        byte[] pdfBytes = Base64.getDecoder().decode(base64);

        if (pdfBytes.length < 5
                || pdfBytes[0] != '%'
                || pdfBytes[1] != 'P'
                || pdfBytes[2] != 'D'
                || pdfBytes[3] != 'F') {
            throw new IllegalStateException(
                    "SPFO PRINT DOCUMENT did not return a valid PDF"
                            + " | HTTP " + status
                            + " | Content-Type: " + contentType
                            + " | Bytes: " + pdfBytes.length
            );
        }

        return pdfBytes;
    }

    /**
     * Wait for SPFO to populate the From Year dropdown, then select the year.
     *
     * Priority:
     * 1. spfo.financial.from.year from config.properties (for example 2009-10)
     * 2. If blank, use the oldest real year in the dropdown to request full history.
     *
     * The SPFO page creates #financialYear before its option list is populated,
     * therefore reading Select#getOptions() immediately can incorrectly return only
     * the default "Select" option. This method repeatedly reacquires the element
     * until real financial-year options are present.
     */
    private static String selectConfiguredOrLatestSpfoYear(WebDriver driver) {
        // This SPFO control is a FROM YEAR dropdown, not a single-year filter.
        // Preferred property: spfo.financial.from.year.
        // Backward compatibility: the older spfo.financial.year property is still accepted.
        String configuredYear = normalizeSpfoYear(
                safe(config.getProperty(
                        "spfo.financial.from.year",
                        config.getProperty("spfo.financial.year", "")
                ))
        );

        if (!configuredYear.isBlank()) {
            System.out.println("SPFO CONFIGURED FROM YEAR = " + configuredYear);
        } else {
            System.out.println("SPFO CONFIGURED FROM YEAR = (blank - oldest available year will be used)");
        }

        WebDriverWait yearWait = new WebDriverWait(driver, Duration.ofSeconds(60));

        // SPFO inserts the real options through AJAX after #financialYear exists.
        yearWait.until(d -> {
            try {
                Select select = new Select(d.findElement(By.id("financialYear")));
                return select.getOptions().stream().anyMatch(option -> {
                    String text = normalizeSpfoYear(option.getText());
                    String value = normalizeSpfoYear(option.getAttribute("value"));
                    return text.matches("(?:19|20)\\d{2}-\\d{2}")
                            || value.matches("(?:19|20)\\d{2}-\\d{2}");
                });
            } catch (Exception ignored) {
                return false;
            }
        });

        Select yearSelect = new Select(yearWait.until(
                ExpectedConditions.elementToBeClickable(By.id("financialYear"))
        ));

        java.util.ArrayList<String> availableYears = new java.util.ArrayList<>();
        for (WebElement option : yearSelect.getOptions()) {
            String visible = safe(option.getText()).trim();
            String value = safe(option.getAttribute("value")).trim();
            String normalizedText = normalizeSpfoYear(visible);
            String normalizedValue = normalizeSpfoYear(value);
            String normalized = normalizedText.matches("(?:19|20)\\d{2}-\\d{2}")
                    ? normalizedText
                    : normalizedValue;
            if (normalized.matches("(?:19|20)\\d{2}-\\d{2}")) {
                availableYears.add(normalized);
            }
        }

        if (availableYears.isEmpty()) {
            throw new IllegalStateException(
                    "No financial year option was found in SPFO Passbook after waiting 60 seconds."
            );
        }

        System.out.println(
                "SPFO FROM YEAR OPTIONS = "
                        + availableYears.get(availableYears.size() - 1)
                        + " TO "
                        + availableYears.get(0)
                        + " | COUNT=" + availableYears.size()
        );

        String selectedYear = "";

        // Exact requested FROM YEAR, e.g. 2009-10.
        if (!configuredYear.isBlank()) {
            for (WebElement option : yearSelect.getOptions()) {
                String visible = safe(option.getText()).trim();
                String value = safe(option.getAttribute("value")).trim();
                String normalizedText = normalizeSpfoYear(visible);
                String normalizedValue = normalizeSpfoYear(value);

                if (configuredYear.equalsIgnoreCase(normalizedText)
                        || configuredYear.equalsIgnoreCase(normalizedValue)) {
                    if (!value.isBlank() && !"0".equals(value)) {
                        yearSelect.selectByValue(value);
                    } else {
                        yearSelect.selectByVisibleText(visible);
                    }
                    selectedYear = configuredYear;
                    break;
                }
            }

            if (selectedYear.isBlank()) {
                throw new IllegalStateException(
                        "Configured SPFO FROM YEAR is not available in the dropdown: "
                                + configuredYear
                                + " | Available: " + String.join(", ", availableYears)
                );
            }
        }

        // Blank config = oldest available year, so the full SPFO history is requested.
        if (selectedYear.isBlank()) {
            selectedYear = availableYears.get(availableYears.size() - 1);
            final String oldest = selectedYear;
            for (WebElement option : yearSelect.getOptions()) {
                String visible = safe(option.getText()).trim();
                String value = safe(option.getAttribute("value")).trim();
                if (oldest.equalsIgnoreCase(normalizeSpfoYear(visible))
                        || oldest.equalsIgnoreCase(normalizeSpfoYear(value))) {
                    if (!value.isBlank() && !"0".equals(value)) {
                        yearSelect.selectByValue(value);
                    } else {
                        yearSelect.selectByVisibleText(visible);
                    }
                    break;
                }
            }
        }

        // Explicitly fire change for SPFO/jQuery builds that listen to this event.
        try {
            ((JavascriptExecutor) driver).executeScript(
                    "var s=document.getElementById('financialYear');" +
                            "if(s){" +
                            " s.dispatchEvent(new Event('change',{bubbles:true}));" +
                            " if(window.jQuery){jQuery(s).trigger('change');}" +
                            "}"
            );
        } catch (Exception ignored) {
        }

        final String expectedYear = selectedYear;
        String confirmedYear = yearWait.until(d -> {
            try {
                Select current = new Select(d.findElement(By.id("financialYear")));
                WebElement selected = current.getFirstSelectedOption();
                String selectedText = normalizeSpfoYear(selected.getText());
                String selectedValue = normalizeSpfoYear(selected.getAttribute("value"));
                if (expectedYear.equalsIgnoreCase(selectedText)
                        || expectedYear.equalsIgnoreCase(selectedValue)) {
                    return expectedYear;
                }
            } catch (Exception ignored) {
            }
            return null;
        });

        System.out.println("SPFO FROM YEAR CONFIRMED = " + confirmedYear);
        System.out.println("SPFO WILL INCLUDE ALL AVAILABLE YEARS FROM " + confirmedYear + " ONWARD");
        return confirmedYear;
    }

    private static void showAllSpfoPassbookRows(WebDriver driver) {
        try {
            Object result = ((JavascriptExecutor) driver).executeScript(
                    "try {" +
                            " if (window.jQuery && jQuery.fn && jQuery.fn.DataTable" +
                            "     && jQuery.fn.DataTable.isDataTable('#viewPassBookTable')) {" +
                            "   var t = jQuery('#viewPassBookTable').DataTable();" +
                            "   t.page.len(-1).draw(false);" +
                            "   return 'DATATABLE_ALL_ROWS=' + t.rows().count();" +
                            " }" +
                            " return 'NO_DATATABLE';" +
                            "} catch(e) { return 'DATATABLE_ERROR:' + e.message; }"
            );
            System.out.println("SPFO TABLE EXPAND | " + safe(String.valueOf(result)));
        } catch (Exception e) {
            System.out.println("SPFO TABLE EXPAND WARNING | " + e.getMessage());
        }
    }

    private static void closeSpfoUpdatePopup(WebDriver driver) {
        try {
            WebElement modal = null;
            List<WebElement> modals = driver.findElements(By.id("updateAlert"));
            if (!modals.isEmpty()) {
                modal = modals.get(0);
            }

            if (modal == null) {
                return;
            }

            boolean visible;
            try {
                visible = modal.isDisplayed();
            } catch (Exception e) {
                visible = false;
            }

            if (!visible) {
                return;
            }

            boolean closed = false;
            for (By closeBy : List.of(
                    By.cssSelector("#updateAlert button.close"),
                    By.cssSelector("#updateAlert .modal-header .close"),
                    By.cssSelector("#updateAlert [data-dismiss='modal']"),
                    By.xpath("//*[@id='updateAlert']//*[normalize-space()='×']")
            )) {
                try {
                    for (WebElement close : driver.findElements(closeBy)) {
                        if (close.isDisplayed()) {
                            try {
                                close.click();
                            } catch (Exception e) {
                                ((JavascriptExecutor) driver).executeScript("arguments[0].click();", close);
                            }
                            closed = true;
                            break;
                        }
                    }
                } catch (Exception ignored) {
                }
                if (closed) {
                    break;
                }
            }

            if (!closed) {
                // Bootstrap-safe fallback if the X button cannot be clicked.
                ((JavascriptExecutor) driver).executeScript(
                        "var m=document.getElementById('updateAlert');" +
                                "if(m){m.classList.remove('in','show');m.style.display='none';m.setAttribute('aria-hidden','true');}" +
                                "document.body.classList.remove('modal-open');" +
                                "document.querySelectorAll('.modal-backdrop').forEach(function(x){x.remove();});"
                );
            }

            Thread.sleep(400L);
            System.out.println("SPFO UPDATE EMAIL/PHONE POPUP CLOSED");
        } catch (Exception e) {
            System.out.println("SPFO POPUP CLOSE WARNING | " + e.getMessage());
        }
    }

    private static String extractSpfoFromYear(String remarks) {
        Matcher matcher = Pattern.compile(
                "\\b((?:19|20)\\d{2})[-/](\\d{2}|(?:19|20)\\d{2})\\b"
        ).matcher(safe(remarks));

        if (!matcher.find()) {
            return "";
        }

        String first = matcher.group(1);
        String second = matcher.group(2);
        if (second.length() == 4) {
            second = second.substring(2);
        }
        return first + "-" + second;
    }

    private static String selectSpfoFromYear(Select select, String requestedYear) {
        List<WebElement> options = select.getOptions();

        if (!safe(requestedYear).isBlank()) {
            for (WebElement option : options) {
                String text = normalizeSpfoYear(option.getText());
                String value = normalizeSpfoYear(option.getAttribute("value"));
                if (requestedYear.equalsIgnoreCase(text) || requestedYear.equalsIgnoreCase(value)) {
                    select.selectByVisibleText(option.getText().trim());
                    return option.getText().trim();
                }
            }
            System.out.println("Requested SPFO FROM YEAR not found in dropdown: " + requestedYear);
        }

        // Dropdown is newest -> oldest in the current SPFO page.
        // Pick the oldest real year, not "Select".
        for (int i = options.size() - 1; i >= 0; i--) {
            WebElement option = options.get(i);
            String year = normalizeSpfoYear(option.getText());
            if (year.matches("(?:19|20)\\d{2}-\\d{2}")) {
                select.selectByVisibleText(option.getText().trim());
                return option.getText().trim();
            }
        }

        throw new IllegalStateException("No financial year option was found in SPFO Passbook.");
    }

    private static int spfoYearStartNumber(String financialYear) {
        String normalized = normalizeSpfoYear(financialYear);
        Matcher matcher = Pattern.compile("^((?:19|20)\\d{2})-\\d{2}$").matcher(normalized);
        if (!matcher.find()) {
            return -1;
        }
        try {
            return Integer.parseInt(matcher.group(1));
        } catch (Exception ignored) {
            return -1;
        }
    }

    private static String normalizeSpfoYear(String raw) {
        String value = safe(raw).trim().replace('/', '-').replace('–', '-');
        Matcher matcher = Pattern.compile("((?:19|20)\\d{2})-(\\d{2}|(?:19|20)\\d{2})").matcher(value);
        if (!matcher.find()) {
            return value;
        }
        String second = matcher.group(2);
        if (second.length() == 4) {
            second = second.substring(2);
        }
        return matcher.group(1) + "-" + second;
    }

    private static SpfoBalanceResult captureSpfoBalanceFromPassbook(WebDriver driver, String requestedFromYear) {
        // Take one stable DOM snapshot and keep BOTH:
        // 1) the exact BALANCE row for each financial year; and
        // 2) every visible ledger row for the full Zoho email table / vessel summary.
        java.util.LinkedHashMap<String, SpfoYearBalance> balanceByYear =
                new java.util.LinkedHashMap<>();
        java.util.ArrayList<SpfoLedgerRow> ledgerRows = new java.util.ArrayList<>();

        int requestedStartYear = spfoYearStartNumber(requestedFromYear);

        List<?> rows;
        try {
            Object raw = ((JavascriptExecutor) driver).executeScript(
                    "var t=document.getElementById('viewPassBookTable');" +
                            "if(!t) return [];" +
                            "return Array.from(t.querySelectorAll('tr')).map(function(r){" +
                            "  return Array.from(r.querySelectorAll('th,td')).map(function(c){" +
                            "    return (c.innerText||'').replace(/\\u00a0/g,' ').trim();" +
                            "  });" +
                            "});"
            );

            if (!(raw instanceof List<?>)) {
                return new SpfoBalanceResult(List.of(), List.of(), "", "", new byte[0], "");
            }
            rows = (List<?>) raw;
        } catch (Exception e) {
            System.out.println("SPFO TABLE SNAPSHOT FAILED | " + e.getMessage());
            return new SpfoBalanceResult(List.of(), List.of(), "", "", new byte[0], "");
        }

        String currentYear = "";
        Pattern exactYear = Pattern.compile(
                "^((?:19|20)\\d{2})[-–/](\\d{2}|(?:19|20)\\d{2})$"
        );

        for (Object rowObj : rows) {
            if (!(rowObj instanceof List<?> rawCells) || rawCells.isEmpty()) {
                continue;
            }

            java.util.ArrayList<String> cells = new java.util.ArrayList<>();
            for (Object c : rawCells) {
                cells.add(safe(String.valueOf(c)).replaceAll("\\s+", " ").trim());
            }

            String first = cells.get(0);
            Matcher yearMatcher = exactYear.matcher(first);

            if (yearMatcher.find()) {
                currentYear = normalizeSpfoYear(first);

                // Defensive filter: even if SPFO returns older rows, only keep the
                // configured FROM YEAR and later years.
                if (requestedStartYear >= 0
                        && spfoYearStartNumber(currentYear) >= 0
                        && spfoYearStartNumber(currentYear) < requestedStartYear) {
                    currentYear = "";
                    continue;
                }

                ledgerRows.add(new SpfoLedgerRow(
                        currentYear,
                        currentYear,
                        "", "", "", "", "", "", "", "", "", ""
                ));
                continue;
            }

            if (currentYear.isBlank()) {
                // Header or unrelated DataTables rows before the first year.
                continue;
            }

            // Preserve the complete official SPFO ledger row exactly as displayed.
            ledgerRows.add(new SpfoLedgerRow(
                    currentYear,
                    cellValue(cells, 0),
                    cellValue(cells, 1),
                    cellValue(cells, 2),
                    cellValue(cells, 3),
                    spfoMoney(cellValue(cells, 4)),
                    spfoMoney(cellValue(cells, 5)),
                    spfoMoney(cellValue(cells, 6)),
                    spfoMoney(cellValue(cells, 7)),
                    spfoMoney(cellValue(cells, 8)),
                    spfoMoney(cellValue(cells, 9)),
                    cellValue(cells, 10)
            ));

            if (!first.equalsIgnoreCase("BALANCE")) {
                continue;
            }

            // One authoritative cumulative BALANCE row per year.
            SpfoYearBalance balance = new SpfoYearBalance(
                    currentYear,
                    spfoMoney(cellValue(cells, 4)),
                    spfoMoney(cellValue(cells, 5)),
                    spfoMoney(cellValue(cells, 6)),
                    spfoMoney(cellValue(cells, 7)),
                    spfoMoney(cellValue(cells, 8)),
                    spfoMoney(cellValue(cells, 9))
            );

            balanceByYear.put(currentYear, balance);
        }

        java.util.ArrayList<SpfoYearBalance> yearly =
                new java.util.ArrayList<>(balanceByYear.values());

        String availableBalance = "";
        String availableYear = "";

        try {
            Object bodyObj = ((JavascriptExecutor) driver).executeScript(
                    "return (document.body && document.body.innerText) ? document.body.innerText : '';"
            );

            String body = safe(String.valueOf(bodyObj)).replace('\u00A0', ' ');

            Matcher availableMatcher = Pattern.compile(
                    "(?is)Available\\s+PF\\s+Balance.*?(?:Rs\\.?|₹)?\\s*" +
                            "([0-9][0-9,]*(?:\\.[0-9]{1,2})?)\\s*/?-?\\s*" +
                            "As\\s+Per\\s+((?:19|20)\\d{2}[-–/]\\d{2,4})\\s+Year"
            ).matcher(body);

            if (availableMatcher.find()) {
                availableBalance = formatSpfoAmount(availableMatcher.group(1));
                availableYear = normalizeSpfoYear(availableMatcher.group(2));
            }
        } catch (Exception ignored) {
        }

        // Fallback only when the footer could not be read.
        if (availableBalance.isBlank() && !yearly.isEmpty()) {
            SpfoYearBalance last = yearly.get(yearly.size() - 1);
            availableBalance = last.total();
            availableYear = last.year();
        }

        System.out.println("SPFO EXACT BALANCE ROWS = " + yearly.size());
        for (SpfoYearBalance row : yearly) {
            System.out.println(
                    "  " + row.year()
                            + " | SEAFARER=" + row.seafarerContribution()
                            + " | EMPLOYER=" + row.employerContribution()
                            + " | VOLUNTARY=" + row.voluntaryContribution()
                            + " | EXGRATIA=" + row.exgratiaContribution()
                            + " | PENSION=" + row.pensionAnnuityContribution()
                            + " | TOTAL=" + row.total()
            );
        }

        System.out.println("SPFO FULL LEDGER ROWS = " + ledgerRows.size());

        if (!availableBalance.isBlank()) {
            System.out.println(
                    "AVAILABLE PF BALANCE = " + availableBalance
                            + (availableYear.isBlank() ? "" : " | AS PER " + availableYear)
            );
        }

        return new SpfoBalanceResult(yearly, ledgerRows, availableBalance, availableYear, new byte[0], "");
    }

    private static String cellValue(java.util.List<String> cells, int index) {
        if (cells == null || index < 0 || index >= cells.size()) {
            return "";
        }
        return safe(cells.get(index)).trim();
    }


    private static java.math.BigDecimal[] zeroSpfoAmounts() {
        return new java.math.BigDecimal[]{
                java.math.BigDecimal.ZERO,
                java.math.BigDecimal.ZERO,
                java.math.BigDecimal.ZERO,
                java.math.BigDecimal.ZERO,
                java.math.BigDecimal.ZERO,
                java.math.BigDecimal.ZERO
        };
    }

    private static java.math.BigDecimal[] copySpfoAmounts(java.math.BigDecimal[] source) {
        java.math.BigDecimal[] out = zeroSpfoAmounts();
        if (source == null) {
            return out;
        }
        for (int i = 0; i < out.length && i < source.length; i++) {
            out[i] = source[i] == null ? java.math.BigDecimal.ZERO : source[i];
        }
        return out;
    }

    private static java.math.BigDecimal parseSpfoDecimal(String raw) {
        String cleaned = safe(raw)
                .replace(",", "")
                .replaceAll("[^0-9.\\-]", "")
                .trim();
        if (cleaned.isBlank() || cleaned.equals("-") || cleaned.equals(".")) {
            return java.math.BigDecimal.ZERO;
        }
        try {
            return new java.math.BigDecimal(cleaned);
        } catch (Exception ignored) {
            return java.math.BigDecimal.ZERO;
        }
    }

    private static boolean hasPositiveSpfoDecimal(java.math.BigDecimal[] values) {
        if (values == null) {
            return false;
        }
        for (java.math.BigDecimal value : values) {
            if (value != null && value.compareTo(java.math.BigDecimal.ZERO) > 0) {
                return true;
            }
        }
        return false;
    }

    private static String spfoMoney(java.math.BigDecimal value) {
        if (value == null) {
            value = java.math.BigDecimal.ZERO;
        }
        return "₹" + formatIndianNumber(value);
    }

    private static String cellText(List<WebElement> cells, int index) {
        if (index < 0 || index >= cells.size()) {
            return "";
        }
        try {
            return safe(cells.get(index).getText()).trim();
        } catch (Exception e) {
            return "";
        }
    }

    private static boolean hasAnyPositiveSpfoAmount(String... values) {
        for (String value : values) {
            String cleaned = safe(value).replace(",", "").replaceAll("[^0-9.-]", "");
            if (cleaned.isBlank() || cleaned.equals("-") || cleaned.equals(".")) {
                continue;
            }
            try {
                if (new java.math.BigDecimal(cleaned).compareTo(java.math.BigDecimal.ZERO) > 0) {
                    return true;
                }
            } catch (Exception ignored) {
            }
        }
        return false;
    }

    private static String spfoMoney(String raw) {
        String value = safe(raw).trim();
        if (value.isBlank() || value.equals("-")) {
            return "₹0";
        }
        return formatSpfoAmount(value);
    }

    private static void logoutSpfoViaProfile(WebDriver driver) {
        try {
            // Return to dashboard where Profile -> Logout is available.
            if (!safe(driver.getCurrentUrl()).toLowerCase(Locale.ROOT).contains("welcome.html")) {
                driver.get("https://spfo.gov.in/spfo/view/welcome.html");
            }

            closeSpfoUpdatePopup(driver);
            WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(15));

            WebElement profile = wait.until(d -> {
                for (By locator : List.of(
                        By.xpath("//a[normalize-space()='Profile']"),
                        By.cssSelector("a.dropdown-toggle.userIcon")
                )) {
                    for (WebElement el : d.findElements(locator)) {
                        try {
                            if (el.isDisplayed()) {
                                return el;
                            }
                        } catch (Exception ignored) {
                        }
                    }
                }
                return null;
            });

            try {
                profile.click();
            } catch (Exception e) {
                ((JavascriptExecutor) driver).executeScript("arguments[0].click();", profile);
            }

            WebElement logout = wait.until(d -> {
                for (WebElement el : d.findElements(By.linkText("Logout"))) {
                    try {
                        if (el.isDisplayed()) {
                            return el;
                        }
                    } catch (Exception ignored) {
                    }
                }
                return null;
            });

            try {
                logout.click();
            } catch (Exception e) {
                ((JavascriptExecutor) driver).executeScript("arguments[0].click();", logout);
            }

            System.out.println("SPFO PROFILE -> LOGOUT CLICKED");
        } catch (Exception e) {
            System.out.println("SPFO LOGOUT WARNING | " + e.getMessage());
        }
    }

    private static String formatSpfoAmount(String raw) {
        String cleaned = safe(raw).replace(",", "").trim();
        try {
            return "₹" + formatIndianNumber(new java.math.BigDecimal(cleaned));
        } catch (Exception e) {
            return safe(raw);
        }
    }

    private static String formatIndianNumber(java.math.BigDecimal value) {
        java.text.DecimalFormat formatter = new java.text.DecimalFormat("##,##,##0.##");
        return formatter.format(value);
    }

    private static java.util.List<String> buildSpfoWhatsAppYearLines(SpfoBalanceResult result) {
        if (result == null || result.yearlyBalances() == null || result.yearlyBalances().isEmpty()) {
            return java.util.List.of("Financial year balance not available");
        }

        java.util.LinkedHashMap<String, java.util.LinkedHashSet<String>> vesselEntriesByYear =
                new java.util.LinkedHashMap<>();

        if (result.ledgerRows() != null) {
            for (SpfoLedgerRow row : result.ledgerRows()) {
                if (row == null || safe(row.financialYear()).isBlank()) {
                    continue;
                }

                String type = safe(row.typeOrFinancialYear()).trim();
                if (type.equalsIgnoreCase("BALANCE")
                        || type.equalsIgnoreCase("O.B")
                        || type.equalsIgnoreCase("OB")
                        || normalizeSpfoYear(type).equalsIgnoreCase(row.financialYear())) {
                    continue;
                }

                String vesselAndDate = extractSpfoVesselAndDate(row.remarks());
                if (!vesselAndDate.isBlank()) {
                    String pfTotal = safe(row.total()).trim();
                    String vesselEntry = vesselAndDate;
                    if (!pfTotal.isBlank() && !pfTotal.equals("₹0")) {
                        vesselEntry += " " + pfTotal;
                    }

                    vesselEntriesByYear
                            .computeIfAbsent(row.financialYear(), k -> new java.util.LinkedHashSet<>())
                            .add(vesselEntry);
                }
            }
        }

        java.util.ArrayList<String> lines = new java.util.ArrayList<>();

        for (SpfoYearBalance yearRow : result.yearlyBalances()) {
            if (yearRow == null || safe(yearRow.year()).isBlank()) {
                continue;
            }

            java.util.LinkedHashSet<String> vesselEntries =
                    vesselEntriesByYear.get(yearRow.year());

            String vesselText;
            if (vesselEntries == null || vesselEntries.isEmpty()) {
                vesselText = "No Vessel Name given";
            } else {
                vesselText = String.join(" + ", vesselEntries);
            }

            lines.add(
                    yearRow.year()
                            + " -> " + vesselText
                            + " | Balance -> " + safe(yearRow.total())
            );
        }

        return lines.isEmpty()
                ? java.util.List.of("Financial year balance not available")
                : lines;
    }

    private static String extractSpfoVesselAndDate(String remarks) {
        String text = safe(remarks).replaceAll("\\s+", " ").trim();
        if (text.isBlank()) {
            return "";
        }

        Matcher matcher = Pattern.compile(
                "(?i)\\bCONTRIBUTED\\s+BY\\s+THE\\s+VESSEL\\s+(.+?)\\s+ON\\s+"
                        + "(\\d{2}[-/]\\d{2}[-/]\\d{4})\\b"
        ).matcher(text);

        if (!matcher.find()) {
            return "";
        }

        String vessel = safe(matcher.group(1)).replaceAll("\\s+", " ").trim();
        String date = safe(matcher.group(2)).trim();

        if (vessel.isBlank()) {
            return "";
        }

        return "Vessel " + vessel + " on " + date;
    }

    // Old 3-variable template fallback / console preview.
    private static String buildSpfoWhatsAppYearText(SpfoBalanceResult result) {
        return String.join(" | ", buildSpfoWhatsAppYearLines(result));
    }


    private static void sendSpfoBalanceResultAtMostOnce(
            CaseRow caseRow,
            CustomerDetails customer,
            SpfoBalanceResult result
    ) throws Exception {
        if (caseRow == null || customer == null || result == null) {
            throw new IllegalArgumentException("SPFO result/case/customer is missing.");
        }
        if (result.yearlyBalances() == null || result.yearlyBalances().isEmpty()) {
            throw new IllegalStateException("No SPFO yearly balances were detected. NOTHING SENT.");
        }
        if (result.officialLedgerPdf() == null
                || result.officialLedgerPdf().length < 5) {
            throw new IllegalStateException(
                    "Official SPFO ledger PDF was not captured. NOTHING SENT."
            );
        }
        if (isClosedJsuOrSpfoNow(caseRow)) {
            System.out.println("SPFO BALANCE SEND SKIPPED - MM CASE CLOSED | Case: " + caseRow.id);
            return;
        }

        Properties attempts = loadAttemptHistory();
        Properties sent = loadSentHistory();

        String emailAttemptKey = getAttemptHistoryKey(caseRow, "spfo.balance.mail");
        String whatsappAttemptKey = getAttemptHistoryKey(caseRow, "spfo.balance.whatsapp");
        String emailSentKey = caseRow.id + ".spfo.balance.mail";
        String whatsappSentKey = caseRow.id + ".spfo.balance.whatsapp";

        Exception firstFailure = null;

        // ---------------- SPFO CANDIDATE ZOHO MAIL ----------------
        // IMPORTANT: only a SUCCESSFUL send blocks future sends.
        // Old attempt-only markers are ignored so a failed/blocked historical
        // attempt cannot prevent the candidate from receiving the result.
        if (isBlank(customer.email)) {
            System.out.println("SPFO EMAIL SKIPPED - CANDIDATE EMAIL MISSING | Case " + caseRow.id);
        } else if (sent.containsKey(emailSentKey)) {
            System.out.println("SPFO EMAIL SKIPPED - ALREADY SUCCESSFULLY SENT | Case " + caseRow.id);
        } else if (!SPFO_BALANCE_EMAIL_IN_FLIGHT.add(caseRow.id)) {
            System.out.println("SPFO EMAIL DUPLICATE BLOCKED - SEND ALREADY IN PROGRESS | Case " + caseRow.id);
        } else {
            try {
                // Zoho mail gets the COMPLETE official SPFO ledger table:
                // year heading, O.B, every P.F transaction, BALANCE and remarks.
                java.util.List<String[]> rows = new java.util.ArrayList<>();
                for (SpfoLedgerRow ledgerRow : result.ledgerRows()) {
                    rows.add(new String[]{
                            ledgerRow.typeOrFinancialYear(),
                            ledgerRow.yearInwardNo(),
                            ledgerRow.signOnDate(),
                            ledgerRow.signOffDate(),
                            ledgerRow.seafarerContribution(),
                            ledgerRow.employerContribution(),
                            ledgerRow.voluntaryContribution(),
                            ledgerRow.exgratiaContribution(),
                            ledgerRow.pensionAnnuityContribution(),
                            ledgerRow.total(),
                            ledgerRow.remarks()
                    });
                }

                // Add Mariners Mentor watermark ONLY to the copy that is emailed.
                // The original official PDF bytes remain unchanged in SpfoBalanceResult.
                byte[] watermarkedLedgerPdf = PdfWatermarkService.addMarinersMentorWatermark(
                        result.officialLedgerPdf()
                );

                String watermarkedLedgerFileName =
                        "Mariners_Mentor_SPFO_Seafarer_Ledger.pdf";

                System.out.println(
                        "SPFO PDF WATERMARK ADDED"
                                + " | Logo: mariners-mentor-logo.png"
                                + " | Text: MARINERS MENTOR"
                                + " | Orientation: VERTICAL"
                                + " | Color: DARK GREEN"
                                + " | Pages: ALL"
                                + " | Bytes: " + watermarkedLedgerPdf.length
                );

                ZohoMailService.sendSpfoBalanceMail(
                        config,
                        safe(caseRow.customerFullName),
                        customer.email,
                        result.totalAmount(),
                        result.availablePfYear(),
                        rows,
                        watermarkedLedgerPdf,
                        watermarkedLedgerFileName
                );

                rememberSent(sent, emailSentKey);
                System.out.println(
                        "SPFO BALANCE ZOHO MAIL SENT"
                                + " | Case: " + caseRow.id
                                + " | To: " + customer.email
                );
            } catch (Exception e) {
                firstFailure = e;
                System.err.println(
                        "SPFO CANDIDATE EMAIL FAILED - WILL RETRY UNTIL SUCCESS"
                                + " | Case " + caseRow.id
                                + " | To: " + customer.email
                                + " | " + safe(e.getMessage())
                );
            } finally {
                SPFO_BALANCE_EMAIL_IN_FLIGHT.remove(caseRow.id);
            }
        }

        // ---------------- SPFO WHATSAPP ----------------
        String phone = normalizePhone(customer.phone);

        if (isBlank(phone)) {
            System.out.println("SPFO WHATSAPP SKIPPED - CANDIDATE PHONE MISSING | Case " + caseRow.id);
        } else if (sent.containsKey(whatsappSentKey)
                || wasEverAttempted(attempts, caseRow, "spfo.balance.whatsapp")) {
            System.out.println("SPFO WHATSAPP BLOCKED - ALREADY SENT/ATTEMPTED | Case " + caseRow.id);
        } else if (!claimAttemptOnce(caseRow, "spfo.balance.whatsapp")) {
            System.out.println("SPFO WHATSAPP DUPLICATE BLOCKED BEFORE SEND | Case " + caseRow.id);
        } else {
            try {
                // WhatsApp gets TEXT ONLY. The official SPFO ledger PDF is
                // attached to Zoho email only (no Meta media upload/document header).
                WhatsAppService.sendSpfoBalanceMessage(
                        config,
                        phone,
                        getSurnameFollowedByFirstName(caseRow, customer),
                        buildSpfoWhatsAppYearLines(result),
                        result.totalAmount()
                );

                rememberSent(sent, whatsappSentKey);
                System.out.println(
                        "SPFO BALANCE WHATSAPP ACCEPTED BY META"
                                + " | Case: " + caseRow.id
                                + " | To: " + phone
                );
            } catch (Exception e) {
                String errorText = safe(e.getMessage());
                boolean metaRejectedBeforeSend =
                        errorText.contains("132001")
                                || errorText.contains("132000")
                                || errorText.toLowerCase(java.util.Locale.ROOT)
                                .contains("template name does not exist")
                                || errorText.toLowerCase(java.util.Locale.ROOT)
                                .contains("number of parameters does not match");

                if (metaRejectedBeforeSend) {
                    // Meta 132001/132000 means the template request was rejected
                    // before delivery. Safe to unlock ONLY the WhatsApp channel so
                    // the same case can retry after the template/code is corrected.
                    try {
                        forgetAttempt(loadAttemptHistory(), whatsappAttemptKey);
                    } catch (Exception unlockError) {
                        System.err.println(
                                "SPFO WHATSAPP META REJECTED - COULD NOT UNLOCK RETRY"
                                        + " | Case " + caseRow.id
                                        + " | " + safe(unlockError.getMessage())
                        );
                    }
                    System.err.println(
                            "SPFO WHATSAPP META REJECTED - NO MESSAGE SENT; RETRY UNLOCKED"
                                    + " | Case " + caseRow.id
                    );
                } else {
                    System.err.println(
                            "SPFO WHATSAPP FAILED BUT REMAINS LOCKED - NO AUTO RESEND"
                                    + " | Case " + caseRow.id
                                    + " | " + errorText
                    );
                }

                if (firstFailure == null) {
                    firstFailure = e;
                }
            }
        }

        // Both channels were attempted independently. Report failure only after giving
        // the other channel a chance to send.
        if (firstFailure != null) {
            throw firstFailure;
        }
    }


    private static WebDriver createSpfoTestChromeDriver() {
        String chromedriverPath = firstNonBlank(
                System.getenv("CHROMEDRIVER_PATH"),
                System.getenv("CHROMEDRIVER_BIN")
        );

        if (!chromedriverPath.isBlank()) {
            System.setProperty("webdriver.chrome.driver", chromedriverPath);
        }

        ChromeOptions options = new ChromeOptions();
        String chromeBinary = firstNonBlank(
                System.getenv("CHROME_BIN"),
                System.getenv("CHROME_PATH")
        );

        if (!chromeBinary.isBlank()) {
            options.setBinary(chromeBinary);
        }

        // Visible browser is required because SPFO CAPTCHA must be entered manually.
        options.addArguments(
                "--start-maximized",
                "--disable-notifications",
                "--remote-allow-origins=*"
        );

        return new ChromeDriver(options);
    }

    private static String maskCdcForLog(String cdcNo) {
        String value = safe(cdcNo).trim();
        if (value.length() <= 4) {
            return "****";
        }
        return value.substring(0, 2)
                + "***"
                + value.substring(value.length() - 2);
    }

    private static WebDriver createAutomationChromeDriver() {
        String chromedriverPath = firstNonBlank(
                System.getenv("CHROMEDRIVER_PATH"),
                System.getenv("CHROMEDRIVER_BIN")
        );

        if (!chromedriverPath.isBlank()) {
            System.setProperty("webdriver.chrome.driver", chromedriverPath);
        }

        ChromeOptions options = new ChromeOptions();

        String chromeBinary = firstNonBlank(
                System.getenv("CHROME_BIN"),
                System.getenv("CHROME_PATH")
        );

        if (!chromeBinary.isBlank()) {
            options.setBinary(chromeBinary);
        }

        options.addArguments(
                "--headless=new",
                "--disable-gpu",
                "--no-sandbox",
                "--disable-dev-shm-usage",
                "--disable-extensions",
                "--disable-background-networking",
                "--disable-sync",
                "--disable-default-apps",
                "--no-first-run",
                "--window-size=1280,900",
                "--remote-allow-origins=*"
        );

        return new ChromeDriver(options);
    }

    private static String loginAndGetDashboardToken()
            throws Exception {

        String email = requiredConfig("dashboard.email");
        String password = requiredConfig("dashboard.password");

        WebDriver driver = createAutomationChromeDriver();

        try {
            driver.get(
                    "https://dashboard.marinersmentor.com/auth/sign-in"
            );

            WebDriverWait wait =
                    new WebDriverWait(
                            driver,
                            Duration.ofSeconds(30)
                    );

            wait.until(
                    ExpectedConditions.visibilityOfElementLocated(
                            By.name("email")
                    )
            ).sendKeys(email);

            driver.findElement(
                    By.name("password")
            ).sendKeys(password);

            driver.findElement(
                    By.cssSelector("button[type='submit']")
            ).click();

            try {
                wait.until(webDriver -> {
                    String currentUrl = webDriver.getCurrentUrl();
                    return currentUrl != null
                            && !currentUrl.contains("/auth/sign-in");
                });
            } catch (org.openqa.selenium.TimeoutException loginTimeout) {
                // The submit completed, but the site did not accept the login.
                // Report visible feedback without logging credentials or page HTML.
                String pageText = safe(driver.findElement(By.tagName("body")).getText());
                String feedback = pageText.lines()
                        .map(String::trim)
                        .filter(line -> line.matches("(?i).*(invalid|incorrect|wrong|failed|error|captcha|verify|verification|blocked|too many|try again).*"))
                        .findFirst().orElse("No visible login error found");
                if (feedback.length() > 180) feedback = feedback.substring(0, 180);
                throw new IOException("Dashboard login remained on the sign-in page after 30 seconds. "
                        + "Page feedback: " + feedback + ". Check dashboard.email/dashboard.password "
                        + "and whether the site requests CAPTCHA or verification.", loginTimeout);
            }

            String token = waitForTokenInBrowser(driver);

            if (isBlank(token)) {
                throw new IOException(
                        "Dashboard login succeeded, but Bearer token "
                                + "was not found in browser storage."
                );
            }

            return stripBearerPrefix(token);

        } finally {
            driver.quit();
        }
    }

    private static String waitForTokenInBrowser(
            WebDriver driver
    ) throws InterruptedException {

        for (int attempt = 1; attempt <= 30; attempt++) {

            String token = findTokenInBrowserStorage(driver);

            if (!isBlank(token)) {
                return token;
            }

            Thread.sleep(1000);
        }

        return "";
    }

    private static String findTokenInBrowserStorage(
            WebDriver driver
    ) {

        JavascriptExecutor javascript =
                (JavascriptExecutor) driver;

        Object result = javascript.executeScript(
                """
                const stores = [window.localStorage, window.sessionStorage];

                function looksLikeToken(value) {
                    if (!value || typeof value !== 'string') return false;

                    const cleaned = value
                        .replace(/^Bearer\\s+/i, '')
                        .replace(/^["']|["']$/g, '')
                        .trim();

                    if (cleaned.split('.').length === 3
                            && cleaned.length > 50) {
                        return true;
                    }

                    return cleaned.length > 100
                            && /^[A-Za-z0-9._~-]+$/.test(cleaned);
                }

                function inspect(value) {
                    if (!value) return null;

                    if (looksLikeToken(value)) {
                        return value;
                    }

                    try {
                        const parsed = JSON.parse(value);

                        const stack = [parsed];

                        while (stack.length > 0) {
                            const current = stack.pop();

                            if (!current || typeof current !== 'object') {
                                continue;
                            }

                            for (const [key, item] of Object.entries(current)) {
                                if (typeof item === 'string') {
                                    const lowerKey = key.toLowerCase();

                                    if ((lowerKey.includes('token')
                                            || lowerKey.includes('access')
                                            || lowerKey.includes('jwt'))
                                            && looksLikeToken(item)) {
                                        return item;
                                    }
                                } else if (item
                                        && typeof item === 'object') {
                                    stack.push(item);
                                }
                            }
                        }
                    } catch (ignored) {
                    }

                    return null;
                }

                for (const store of stores) {
                    for (let i = 0; i < store.length; i++) {
                        const key = store.key(i);
                        const value = store.getItem(key);
                        const token = inspect(value);

                        if (token) {
                            return token;
                        }
                    }
                }

                return null;
                """
        );

        return result == null
                ? ""
                : String.valueOf(result).trim();
    }

    private static String stripBearerPrefix(String token) {

        if (isBlank(token)) {
            return "";
        }

        String cleaned = token.trim();

        if (cleaned.toLowerCase(Locale.ROOT)
                .startsWith("bearer ")) {

            cleaned = cleaned.substring(7).trim();
        }

        if ((cleaned.startsWith("\"")
                && cleaned.endsWith("\""))
                || (cleaned.startsWith("'")
                && cleaned.endsWith("'"))) {

            cleaned = cleaned.substring(
                    1,
                    cleaned.length() - 1
            ).trim();
        }

        return cleaned;
    }

    private static String requiredConfig(String key) {

        String value = config.getProperty(key);

        if (isBlank(value)) {
            throw new IllegalStateException(
                    key + " is missing in config.properties"
            );
        }

        return value.trim();
    }

    private static Properties loadConfigProperties() throws IOException {
        Properties properties = new Properties();

        try (InputStream input =
                     Main.class.getClassLoader()
                             .getResourceAsStream("config.properties")) {

            if (input == null) {
                throw new IOException(
                        "config.properties not found inside src/main/resources"
                );
            }

            properties.load(input);
        }

        return properties;
    }

    private static Sheets createSheetsService() throws Exception {
        try (FileInputStream inputStream =
                     new FileInputStream(SERVICE_ACCOUNT_FILE)) {

            GoogleCredentials credentials =
                    GoogleCredentials.fromStream(inputStream)
                            .createScoped(
                                    Collections.singleton(
                                            SheetsScopes.SPREADSHEETS
                                    )
                            );

            return new Sheets.Builder(
                    GoogleNetHttpTransport.newTrustedTransport(),
                    GsonFactory.getDefaultInstance(),
                    new HttpCredentialsAdapter(credentials)
            )
                    .setApplicationName("Mariners Mentor Welcome Bot")
                    .build();
        }
    }

    private static List<CaseRow> fetchAllCasesForBirthday()
            throws Exception {

        List<CaseRow> cases =
                fetchSharedAllCasesSnapshot("BIRTHDAY", false);

        System.out.println(
                "BIRTHDAY SOURCE = SHARED ALL CASES SNAPSHOT"
                        + " | Cases received: "
                        + cases.size()
        );

        return cases;
    }

    private static List<CaseRow> fetchAllCasesForCustomerProfile()
            throws Exception {

        List<CaseRow> cases =
                fetchSharedAllCasesSnapshot("CUSTOMER PROFILE", false);

        System.out.println(
                "CUSTOMER PROFILE SOURCE = SHARED ALL CASES SNAPSHOT"
                        + " | Cases received: "
                        + cases.size()
        );

        return cases;
    }

    /**
     * Fetch the very large period=all cases payload only once and share it between
     * Birthday + Customer Profile. This avoids simultaneous 7,000+ case requests
     * that were causing HTTP 502 from the Mariners Mentor backend.
     *
     * Successful snapshots are also saved to disk. If the backend is temporarily
     * unavailable later, the last complete snapshot is reused instead of stopping
     * Birthday / Customer Profile processing.
     */
    private static List<CaseRow> fetchSharedAllCasesSnapshot(
            String purpose,
            boolean forceRefresh
    ) throws Exception {

        synchronized (ALL_CASES_SNAPSHOT_LOCK) {

            long now = System.currentTimeMillis();

            if (!forceRefresh
                    && ALL_CASES_MEMORY_CACHE != null
                    && !ALL_CASES_MEMORY_CACHE.isEmpty()
                    && (now - ALL_CASES_MEMORY_CACHE_AT) < ALL_CASES_MEMORY_TTL_MILLIS) {

                System.out.println(
                        "ALL CASES SNAPSHOT REUSED FROM MEMORY"
                                + " | Purpose: " + purpose
                                + " | Cases: " + ALL_CASES_MEMORY_CACHE.size()
                );
                return new ArrayList<>(ALL_CASES_MEMORY_CACHE);
            }

            Exception last = null;

            for (int attempt = 1; attempt <= 6; attempt++) {
                try {
                    System.out.println(
                            "ALL CASES SNAPSHOT FETCH"
                                    + " | Purpose: " + purpose
                                    + " | Attempt: " + attempt + "/6"
                                    + " | Only one period=all request is allowed at a time"
                    );

                    HttpResponse<String> response =
                            sendAuthorizedGetWithRefresh(
                                    BIRTHDAY_CASES_API,
                                    "Shared Cases API - ALL"
                            );

                    List<CaseRow> cases =
                            MAPPER.readValue(
                                    response.body(),
                                    new TypeReference<List<CaseRow>>() {}
                            );

                    // Protect against saving a broken/partial response as the full snapshot.
                    if (cases == null || cases.size() < 100) {
                        throw new IOException(
                                "Shared Cases API - ALL returned an unexpectedly small list: "
                                        + (cases == null ? 0 : cases.size())
                        );
                    }

                    ALL_CASES_MEMORY_CACHE =
                            Collections.unmodifiableList(new ArrayList<>(cases));
                    ALL_CASES_MEMORY_CACHE_AT = System.currentTimeMillis();

                    saveAllCasesDiskCache(cases);

                    System.out.println(
                            "ALL CASES SNAPSHOT REFRESHED SUCCESSFULLY"
                                    + " | Cases: " + cases.size()
                                    + " | Shared by Birthday + Customer Profile"
                    );

                    return new ArrayList<>(cases);

                } catch (Exception e) {
                    last = e;

                    System.err.println(
                            "ALL CASES SNAPSHOT FETCH FAILED"
                                    + " | Purpose: " + purpose
                                    + " | Attempt: " + attempt + "/6"
                                    + " | " + safe(e.getMessage())
                    );

                    if (attempt < 6) {
                        long sleepMillis = Math.min(30000L, 5000L * attempt);
                        System.out.println(
                                "ALL CASES RETRY WAIT | "
                                        + (sleepMillis / 1000L) + " seconds"
                        );
                        try {
                            Thread.sleep(sleepMillis);
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw interrupted;
                        }
                    }
                }
            }

            List<CaseRow> diskCases = loadAllCasesDiskCache();

            if (!diskCases.isEmpty()) {
                ALL_CASES_MEMORY_CACHE =
                        Collections.unmodifiableList(new ArrayList<>(diskCases));
                ALL_CASES_MEMORY_CACHE_AT = System.currentTimeMillis();

                System.out.println(
                        "ALL CASES API TEMPORARILY UNAVAILABLE - USING LAST SUCCESSFUL DISK SNAPSHOT"
                                + " | Purpose: " + purpose
                                + " | Cases: " + diskCases.size()
                                + " | File: " + ALL_CASES_DISK_CACHE
                );

                return new ArrayList<>(diskCases);
            }

            throw last == null
                    ? new IOException("ALL cases could not be fetched and no local snapshot exists")
                    : last;
        }
    }

    private static void saveAllCasesDiskCache(List<CaseRow> cases) {
        if (cases == null || cases.isEmpty()) {
            return;
        }

        try {
            Files.createDirectories(ALL_CASES_DISK_CACHE.getParent());

            Path temp =
                    ALL_CASES_DISK_CACHE.resolveSibling(
                            ALL_CASES_DISK_CACHE.getFileName() + ".tmp"
                    );

            MAPPER.writerWithDefaultPrettyPrinter()
                    .writeValue(temp.toFile(), cases);

            try {
                Files.move(
                        temp,
                        ALL_CASES_DISK_CACHE,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE
                );
            } catch (Exception atomicMoveError) {
                Files.move(
                        temp,
                        ALL_CASES_DISK_CACHE,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING
                );
            }

            System.out.println(
                    "ALL CASES DISK SNAPSHOT SAVED"
                            + " | Cases: " + cases.size()
                            + " | " + ALL_CASES_DISK_CACHE
            );

        } catch (Exception e) {
            System.err.println(
                    "ALL CASES DISK SNAPSHOT SAVE WARNING | "
                            + safe(e.getMessage())
            );
        }
    }

    private static List<CaseRow> loadAllCasesDiskCache() {
        try {
            if (!Files.exists(ALL_CASES_DISK_CACHE)
                    || Files.size(ALL_CASES_DISK_CACHE) <= 0L) {
                return Collections.emptyList();
            }

            List<CaseRow> cases =
                    MAPPER.readValue(
                            ALL_CASES_DISK_CACHE.toFile(),
                            new TypeReference<List<CaseRow>>() {}
                    );

            if (cases == null || cases.size() < 100) {
                return Collections.emptyList();
            }

            return cases;

        } catch (Exception e) {
            System.err.println(
                    "ALL CASES DISK SNAPSHOT READ WARNING | "
                            + safe(e.getMessage())
            );
            return Collections.emptyList();
        }
    }

    private static Path resolveMmLocalStateDir() {
        String localAppData = System.getenv("LOCALAPPDATA");

        if (localAppData != null && !localAppData.isBlank()) {
            return Path.of(
                    localAppData,
                    "MarinersMentor",
                    "MMcasesBot"
            );
        }

        return Path.of(
                System.getProperty("user.home", "."),
                "AppData",
                "Local",
                "MarinersMentor",
                "MMcasesBot"
        );
    }

    private static List<CaseRow> fetchCases()
            throws Exception {

        HttpResponse<String> response =
                sendAuthorizedGetWithRefresh(
                        CASES_API,
                        "Cases API"
                );

        return MAPPER.readValue(
                response.body(),
                new TypeReference<List<CaseRow>>() {}
        );
    }

    private static CaseRow ensureCaseRemarksLoaded(
            CaseRow caseRow
    ) {

        if (caseRow == null
                || caseRow.id <= 0
                || !isBlank(caseRow.remarks)) {

            return caseRow;
        }

        try {

            HttpResponse<String> response =
                    sendAuthorizedGetWithRefresh(
                            CASE_DETAIL_API_BASE
                                    + caseRow.id
                                    + "/",
                            "Case details API for ID "
                                    + caseRow.id
                    );

            JsonNode root =
                    MAPPER.readTree(
                            response.body()
                    );

            JsonNode detailNode =
                    chooseCaseNode(
                            root
                    );

            String remarks =
                    findFirstText(
                            detailNode,
                            "remarks",
                            "remark",
                            "case_remarks",
                            "caseRemarks"
                    );

            if (!isBlank(remarks)) {
                caseRow.remarks = remarks;
            }

        } catch (Exception e) {

            System.err.println(
                    "Unable to read remarks for case "
                            + caseRow.id
                            + ": "
                            + e.getMessage()
            );
        }

        return caseRow;
    }

    private static CustomerDetails fetchCustomerDetails(
            long customerId
    ) {

        if (customerId <= 0) {
            return new CustomerDetails();
        }

        try {
            HttpResponse<String> response =
                    sendAuthorizedGetWithRefresh(
                            CUSTOMER_API_BASE + customerId + "/",
                            "Customer API for ID " + customerId
                    );

            JsonNode root = MAPPER.readTree(response.body());
            JsonNode customerNode = chooseCustomerNode(root);

            CustomerDetails details =
                    MAPPER.treeToValue(customerNode, CustomerDetails.class);

            if (isBlank(details.email)) {
                details.email = findFirstText(
                        customerNode,
                        "email",
                        "email_id",
                        "emailId",
                        "customer_email"
                );
            }

            if (isBlank(details.phone)) {
                details.phone = findFirstText(
                        customerNode,
                        "phone",
                        "phone_no",
                        "phone_number",
                        "mobile",
                        "mobile_no",
                        "contact",
                        "contact_no",
                        "customer_phone"
                );
            }

            if (isBlank(details.dateOfBirth)) {
                details.dateOfBirth = findFirstText(
                        customerNode,
                        "date_of_birth",
                        "dateOfBirth",
                        "dob",
                        "birth_date",
                        "birthDate",
                        "birthday"
                );
            }

            if (isBlank(details.cdcNo)) {
                details.cdcNo = findCdcNumber(customerNode);
            }

            if (isBlank(details.firstName)) {
                details.firstName = findFirstText(
                        customerNode,
                        "first_name",
                        "firstName",
                        "given_name",
                        "givenName",
                        "firstname"
                );
            }

            if (isBlank(details.surname)) {
                details.surname = findFirstText(
                        customerNode,
                        "surname",
                        "last_name",
                        "lastName",
                        "family_name",
                        "familyName",
                        "lastname"
                );
            }

            return details;

        } catch (Exception e) {
            System.err.println(
                    "Unable to fetch customer " + customerId + ": " + e.getMessage()
            );
            return new CustomerDetails();
        }
    }

    private static HttpResponse<String> sendAuthorizedGetWithRefresh(
            String url,
            String apiName
    ) throws Exception {

        String token = getDashboardAccessToken();

        HttpResponse<String> response =
                HTTP_CLIENT.send(
                        createAuthorizedGetRequest(url, token),
                        HttpResponse.BodyHandlers.ofString(
                                StandardCharsets.UTF_8
                        )
                );

        if (response.statusCode() == 401
                || response.statusCode() == 403) {

            token = refreshDashboardAccessToken();

            response =
                    HTTP_CLIENT.send(
                            createAuthorizedGetRequest(url, token),
                            HttpResponse.BodyHandlers.ofString(
                                    StandardCharsets.UTF_8
                            )
                    );
        }

        verifyApiResponse(response, apiName);
        return response;
    }

    private static HttpRequest createAuthorizedGetRequest(
            String url,
            String accessToken
    ) {
        return HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(60))
                .header("Accept", "application/json, text/plain, */*")
                .header("Authorization", "Bearer " + accessToken)
                .header("Origin", "https://dashboard.marinersmentor.com")
                .header("Referer", "https://dashboard.marinersmentor.com/")
                .GET()
                .build();
    }

    private static void verifyApiResponse(
            HttpResponse<String> response,
            String apiName
    ) throws IOException {

        int status = response.statusCode();

        if (status == 401 || status == 403) {
            throw new IOException(
                    apiName + " authentication failed. Access token may have expired."
            );
        }

        if (status < 200 || status >= 300) {
            throw new IOException(
                    apiName + " returned HTTP " + status + ": " + response.body()
            );
        }
    }

    /**
     * Normal welcome work is limited to today's cases. SPFO is different: once an
     * SPFO case is created, it remains under follow-up on every bot run until the
     * Mariners Mentor case itself is closed.
     */
    private static List<CaseRow> selectCasesForProcessing(
            List<CaseRow> allCases
    ) {
        List<CaseRow> selected = new ArrayList<>();
        LocalDate today = LocalDate.now(INDIA_ZONE);

        if (allCases == null) {
            return selected;
        }

        for (CaseRow caseRow : allCases) {
            if (caseRow == null) continue;

            // Membership service is completely outside this bot now.
            if (isMmMembershipCase(caseRow)) {
                continue;
            }

            // Do not carry old JSU/SPFO (or any other old cases) into today's run.
            LocalDate createdDate = parseCaseDate(caseRow.createdAt);
            if (createdDate == null || !createdDate.equals(today)) {
                continue;
            }

            // Only OPEN/live cases are processed. Closed/completed/cancelled/rejected cases are ignored.
            boolean caseOpen = isCaseStillOpen(caseRow);
            if (safe(caseRow.status).isBlank()) {
                String liveStatus = fetchMarinersMentorCaseStatus(caseRow.id);
                if (!liveStatus.isBlank()) {
                    caseRow.status = liveStatus;
                    caseOpen = isCaseStillOpen(caseRow);
                }
            }
            if (!caseOpen) {
                System.out.println("SKIP CLOSED CASE | Case: " + caseRow.id
                        + " | Status: " + safe(caseRow.status));
                continue;
            }

            selected.add(caseRow);
        }
        return selected;
    }

    private static boolean isCaseStillOpen(CaseRow caseRow) {
        if (caseRow == null) {
            return false;
        }

        String status = normalizeServiceName(caseRow.status);
        if (status.isBlank()) {
            // Some list API payloads omit status. Do not drop an SPFO follow-up
            // merely because that optional field is absent.
            return true;
        }

        return !(status.contains("closed")
                || status.contains("complete")
                || status.contains("abandon")
                || status.contains("cancel")
                || status.contains("reject"));
    }

    private static LocalDate parseCaseDate(String createdAt) {
        if (isBlank(createdAt)) {
            return null;
        }

        try {
            return OffsetDateTime.parse(createdAt)
                    .atZoneSameInstant(INDIA_ZONE)
                    .toLocalDate();
        } catch (Exception e) {
            System.err.println("Unable to parse date: " + createdAt);
            return null;
        }
    }

    private static void syncBirthdayCasesFromCases(
            List<CaseRow> cases
    ) throws Exception {

        if (sheetsService == null || cases == null) {
            return;
        }

        ensureBirthdayCaseSheetExists();

        String range =
                quoteSheetName(BIRTHDAY_CASE_SHEET)
                        + "!A2:F";

        ValueRange existingResponse =
                sheetsService.spreadsheets()
                        .values()
                        .get(
                                SPREADSHEET_ID,
                                range
                        )
                        .execute();

        LinkedHashMap<String, List<Object>> byCustomer =
                new LinkedHashMap<>();

        if (existingResponse.getValues() != null) {

            for (List<Object> row : existingResponse.getValues()) {

                if (row == null || row.isEmpty()) {
                    continue;
                }

                String customerId =
                        safe(row.get(0))
                                .trim();

                if (customerId.isBlank()) {
                    continue;
                }

                List<Object> normalized =
                        new ArrayList<>();

                for (int i = 0; i < 6; i++) {
                    normalized.add(
                            row.size() > i
                                    ? safe(row.get(i))
                                    : ""
                    );
                }

                byCustomer.put(
                        customerId,
                        normalized
                );
            }
        }

        /*
         * IMPORTANT PERFORMANCE GUARD
         * ---------------------------
         * Older builds fetched /api/customers/<id>/ for almost every case in the
         * all-time case list. With thousands of cases this produced thousands of
         * HTTP calls during startup and again from birthday schedulers, which could
         * make Windows/IntelliJ/Chrome appear hung even on a 16 GB machine.
         *
         * Reuse the existing Birthday Cases registry first. Only customers whose
         * registry row is missing DOB/phone/name need a Customer API lookup, and a
         * bounded number are repaired per run. Subsequent runs continue from the
         * remaining incomplete rows automatically.
         */
        int maxCustomerLookups = 100;
        try {
            maxCustomerLookups = Math.max(
                    0,
                    Integer.parseInt(
                            config == null
                                    ? "100"
                                    : config.getProperty(
                                            "birthday.customer.lookup.max.per.run",
                                            "100"
                                    ).trim()
                    )
            );
        } catch (Exception ignored) {
            maxCustomerLookups = 100;
        }

        Map<Long, CustomerDetails> customerCache =
                new HashMap<>();

        int scannedCases = 0;
        int usableDobRows = 0;
        int customerLookups = 0;
        int deferredLookups = 0;
        int changedRows = 0;

        for (CaseRow caseRow : cases) {

            if (caseRow == null
                    || caseRow.customer <= 0) {
                continue;
            }

            scannedCases++;

            String customerId =
                    String.valueOf(caseRow.customer);

            List<Object> existingRow =
                    byCustomer.get(customerId);

            String existingName = existingRow != null && existingRow.size() > 1
                    ? safe(existingRow.get(1)).trim() : "";
            String existingDob = existingRow != null && existingRow.size() > 2
                    ? safe(existingRow.get(2)).trim() : "";
            String existingPhone = existingRow != null && existingRow.size() > 3
                    ? normalizePhone(safe(existingRow.get(3))) : "";

            String caseDob = safe(caseRow.dateOfBirth).trim();
            String caseName =
                    (safe(caseRow.firstName) + " " + safe(caseRow.surname))
                            .replaceAll("\\s+", " ")
                            .trim();

            if (caseName.isBlank()) {
                caseName = safe(caseRow.customerFullName)
                        .replaceAll("\\s+", " ")
                        .trim();
            }

            boolean needsCustomerLookup =
                    existingPhone.isBlank()
                            || (caseDob.isBlank() && existingDob.isBlank())
                            || (caseName.isBlank() && existingName.isBlank());

            CustomerDetails customer = null;

            if (needsCustomerLookup) {
                if (customerLookups < maxCustomerLookups) {
                    customer = customerCache.get(caseRow.customer);
                    if (customer == null) {
                        customer = fetchCustomerDetails(caseRow.customer);
                        customerCache.put(caseRow.customer, customer);
                        customerLookups++;
                    }
                } else {
                    deferredLookups++;
                }
            }

            String customerName = "";
            if (customer != null) {
                customerName =
                        (safe(customer.firstName) + " " + safe(customer.surname))
                                .replaceAll("\\s+", " ")
                                .trim();
            }

            String fullName =
                    firstNonBlank(
                            caseName,
                            customerName,
                            existingName
                    );

            String dob =
                    firstNonBlank(
                            caseDob,
                            customer == null ? "" : customer.dateOfBirth,
                            existingDob
                    );

            if (!dob.isBlank()) {
                usableDobRows++;
            }

            String phone =
                    firstNonBlank(
                            customer == null ? "" : normalizePhone(customer.phone),
                            existingPhone
                    );

            String lastCaseId = String.valueOf(caseRow.id);

            List<Object> candidateWithoutSeen = new ArrayList<>(List.of(
                    customerId,
                    fullName,
                    dob,
                    phone,
                    lastCaseId,
                    existingRow != null && existingRow.size() > 5
                            ? safe(existingRow.get(5))
                            : ""
            ));

            boolean rowChanged = existingRow == null
                    || !safe(existingRow.get(1)).equals(safe(candidateWithoutSeen.get(1)))
                    || !safe(existingRow.get(2)).equals(safe(candidateWithoutSeen.get(2)))
                    || !normalizePhone(safe(existingRow.get(3))).equals(normalizePhone(safe(candidateWithoutSeen.get(3))))
                    || !safe(existingRow.get(4)).equals(safe(candidateWithoutSeen.get(4)));

            if (rowChanged) {
                String lastSeen =
                        LocalDateTime.now(INDIA_ZONE)
                                .format(
                                        DateTimeFormatter.ofPattern(
                                                "dd/MM/yyyy HH:mm"
                                        )
                                );
                candidateWithoutSeen.set(5, lastSeen);
                byCustomer.put(customerId, candidateWithoutSeen);
                changedRows++;
            }
        }

        if (changedRows == 0) {
            System.out.println(
                    "BIRTHDAY CASE SYNC - NO SHEET CHANGES"
                            + " | Cases scanned: " + scannedCases
                            + " | Registry customers: " + byCustomer.size()
                            + " | Customer API lookups: " + customerLookups
                            + " | Deferred lookups: " + deferredLookups
            );
            return;
        }

        List<List<Object>> output =
                new ArrayList<>();

        output.add(
                List.of(
                        "Customer ID",
                        "Customer Name",
                        "DOB",
                        "Phone",
                        "Last Case ID",
                        "Last Seen"
                )
        );

        output.addAll(
                byCustomer.values()
        );

        String outputRange =
                quoteSheetName(BIRTHDAY_CASE_SHEET)
                        + "!A1:F"
                        + Math.max(
                                output.size(),
                                1
                        );

        ValueRange body =
                new ValueRange()
                        .setValues(output);

        sheetsService.spreadsheets()
                .values()
                .update(
                        SPREADSHEET_ID,
                        outputRange,
                        body
                )
                .setValueInputOption("RAW")
                .execute();

        System.out.println(
                "BIRTHDAY CASE SYNC COMPLETED"
                        + " | Cases scanned: "
                        + scannedCases
                        + " | Registry customers: "
                        + byCustomer.size()
                        + " | Cases with DOB: "
                        + usableDobRows
                        + " | Customer API lookups: "
                        + customerLookups
                        + " | Deferred lookups: "
                        + deferredLookups
                        + " | Changed rows: "
                        + changedRows
        );
    }


    private static void ensureBirthdayCaseSheetExists()
            throws IOException {

        Spreadsheet spreadsheet =
                sheetsService.spreadsheets()
                        .get(SPREADSHEET_ID)
                        .execute();

        if (spreadsheet.getSheets() != null) {

            for (com.google.api.services.sheets.v4.model.Sheet sheet
                    : spreadsheet.getSheets()) {

                if (sheet.getProperties() != null
                        && BIRTHDAY_CASE_SHEET.equals(
                                sheet.getProperties().getTitle()
                        )) {
                    return;
                }
            }
        }

        Request addSheet =
                new Request()
                        .setAddSheet(
                                new AddSheetRequest()
                                        .setProperties(
                                                new SheetProperties()
                                                        .setTitle(
                                                                BIRTHDAY_CASE_SHEET
                                                        )
                                        )
                        );

        sheetsService.spreadsheets()
                .batchUpdate(
                        SPREADSHEET_ID,
                        new BatchUpdateSpreadsheetRequest()
                                .setRequests(
                                        List.of(addSheet)
                                )
                )
                .execute();

        System.out.println(
                "Created Google Sheet tab: "
                        + BIRTHDAY_CASE_SHEET
        );
    }



    private static void ensureWelcomeHeaderExists() throws IOException {
        String range = quoteSheetName(WELCOME_SHEET) + "!A1:O1";

        ValueRange body =
                new ValueRange().setValues(List.of(WELCOME_HEADERS));

        sheetsService.spreadsheets()
                .values()
                .update(SPREADSHEET_ID, range, body)
                .setValueInputOption("RAW")
                .execute();
    }

    private static List<String> readReminderStatuses(
            int rowNumber
    ) throws IOException {

        String range =
                quoteSheetName(WELCOME_SHEET)
                        + "!M"
                        + rowNumber
                        + ":N"
                        + rowNumber;

        ValueRange result =
                sheetsService.spreadsheets()
                        .values()
                        .get(
                                SPREADSHEET_ID,
                                range
                        )
                        .execute();

        String whatsappStatus = "";
        String mailStatus = "";

        if (result.getValues() != null
                && !result.getValues().isEmpty()) {

            List<Object> row =
                    result.getValues().get(0);

            if (!row.isEmpty()) {
                whatsappStatus = safe(row.get(0));
            }

            if (row.size() > 1) {
                mailStatus = safe(row.get(1));
            }
        }

        return List.of(
                whatsappStatus,
                mailStatus
        );
    }

    private static void saveReminderDetails(
            int rowNumber,
            String remarks,
            ReminderInfo reminder
    ) throws IOException {

        String range =
                quoteSheetName(WELCOME_SHEET)
                        + "!J"
                        + rowNumber
                        + ":L"
                        + rowNumber;

        ValueRange body =
                new ValueRange().setValues(
                        List.of(
                                List.of(
                                        safe(remarks),
                                        "ON HOLD - "
                                                + reminder.eventDate()
                                                .minusDays(2)
                                                .format(
                                                        REMINDER_DATE_FORMAT
                                                )
                                                + " 10:00 AM",
                                        reminder.locationCode()
                                )
                        )
                );

        sheetsService.spreadsheets()
                .values()
                .update(
                        SPREADSHEET_ID,
                        range,
                        body
                )
                .setValueInputOption("RAW")
                .execute();
    }

    private static int findWelcomeCaseRowNumber(long caseId)
            throws IOException {

        String range = quoteSheetName(WELCOME_SHEET) + "!A2:A";

        ValueRange result =
                sheetsService.spreadsheets()
                        .values()
                        .get(SPREADSHEET_ID, range)
                        .execute();

        List<List<Object>> values = result.getValues();

        if (values == null || values.isEmpty()) {
            return -1;
        }

        String wanted = String.valueOf(caseId);

        for (int index = 0; index < values.size(); index++) {
            List<Object> row = values.get(index);

            if (row != null
                    && !row.isEmpty()
                    && wanted.equals(safe(row.get(0)))) {
                return index + 2;
            }
        }

        return -1;
    }

    private static int findWelcomePhoneRowNumber(
            String phone,
            long currentCaseId
    ) throws IOException {

        String wantedPhone =
                normalizePhone(phone);

        if (isBlank(wantedPhone)) {
            return -1;
        }

        String range =
                quoteSheetName(WELCOME_SHEET)
                        + "!A2:H";

        ValueRange result =
                sheetsService.spreadsheets()
                        .values()
                        .get(
                                SPREADSHEET_ID,
                                range
                        )
                        .execute();

        List<List<Object>> values =
                result.getValues();

        if (values == null
                || values.isEmpty()) {
            return -1;
        }

        String currentCase =
                String.valueOf(currentCaseId);

        for (int index = 0;
             index < values.size();
             index++) {

            List<Object> row =
                    values.get(index);

            if (row == null
                    || row.isEmpty()) {
                continue;
            }

            String existingCaseId =
                    row.size() > 0
                            ? safe(row.get(0)).trim()
                            : "";

            String existingPhone =
                    row.size() > 7
                            ? normalizePhone(
                            safe(row.get(7))
                    )
                            : "";

            /*
             * Same case is not considered a duplicate.
             * Only another case with the same phone blocks sending.
             */
            if (!existingCaseId.equals(currentCase)
                    && wantedPhone.equals(existingPhone)) {

                return index + 2;
            }
        }

        return -1;
    }

    private static List<String> readWelcomeStatuses(int rowNumber)
            throws IOException {

        String range =
                quoteSheetName(WELCOME_SHEET)
                        + "!E" + rowNumber + ":I" + rowNumber;

        ValueRange result =
                sheetsService.spreadsheets()
                        .values()
                        .get(SPREADSHEET_ID, range)
                        .execute();

        String mailStatus = "";
        String whatsAppStatus = "";

        List<List<Object>> values = result.getValues();

        if (values != null && !values.isEmpty()) {
            List<Object> row = values.get(0);

            if (!row.isEmpty()) {
                mailStatus = safe(row.get(0));
            }

            if (row.size() > 4) {
                whatsAppStatus = safe(row.get(4));
            }
        }

        return List.of(mailStatus, whatsAppStatus);
    }

    private static List<Object> createWelcomeRow(
            CaseRow caseRow,
            CustomerDetails customer,
            String mailStatus,
            String whatsAppStatus
    ) {

        String date = "";

        if (!isBlank(caseRow.createdAt)) {
            try {
                date = OffsetDateTime.parse(caseRow.createdAt)
                        .atZoneSameInstant(INDIA_ZONE)
                        .format(DateTimeFormatter.ofPattern("dd/MM/yyyy"));
            } catch (Exception ignored) {
            }
        }

        return new ArrayList<>(
                List.of(
                        caseRow.id,
                        safe(caseRow.customerFullName),
                        safe(customer.email),
                        getDisplayServiceName(caseRow.serviceName),
                        safe(mailStatus),
                        date,
                        safe(caseRow.assignedToFullName),
                        normalizePhone(customer.phone),
                        safe(whatsAppStatus)
                )
        );
    }

    private static void appendWelcomeRow(List<Object> row)
            throws IOException {

        ValueRange body =
                new ValueRange().setValues(List.of(row));

        sheetsService.spreadsheets()
                .values()
                .append(
                        SPREADSHEET_ID,
                        quoteSheetName(WELCOME_SHEET) + "!A:I",
                        body
                )
                .setValueInputOption("USER_ENTERED")
                .setInsertDataOption("INSERT_ROWS")
                .execute();
    }

    private static void updateWelcomeRow(
            int rowNumber,
            List<Object> row
    ) throws IOException {

        String range =
                quoteSheetName(WELCOME_SHEET)
                        + "!A" + rowNumber + ":I" + rowNumber;

        ValueRange body =
                new ValueRange().setValues(List.of(row));

        sheetsService.spreadsheets()
                .values()
                .update(SPREADSHEET_ID, range, body)
                .setValueInputOption("USER_ENTERED")
                .execute();
    }

    private static void updateWelcomeStatus(
            int rowNumber,
            String column,
            String status
    ) throws IOException {

        String range =
                quoteSheetName(WELCOME_SHEET)
                        + "!" + column + rowNumber;

        ValueRange body =
                new ValueRange()
                        .setValues(List.of(List.of(status)));

        sheetsService.spreadsheets()
                .values()
                .update(SPREADSHEET_ID, range, body)
                .setValueInputOption("RAW")
                .execute();
    }

    private static JsonNode chooseCustomerNode(JsonNode root) {
        if (root == null) {
            return MAPPER.createObjectNode();
        }

        if (root.has("customer") && root.get("customer").isObject()) {
            return root.get("customer");
        }

        if (root.has("data") && root.get("data").isObject()) {
            return root.get("data");
        }

        if (root.has("result") && root.get("result").isObject()) {
            return root.get("result");
        }

        return root;
    }

    private static JsonNode chooseCaseNode(
            JsonNode root
    ) {

        if (root == null) {
            return MAPPER.createObjectNode();
        }

        if (root.has("case")
                && root.get("case").isObject()) {

            return root.get("case");
        }

        if (root.has("data")
                && root.get("data").isObject()) {

            return root.get("data");
        }

        if (root.has("result")
                && root.get("result").isObject()) {

            return root.get("result");
        }

        return root;
    }

    /**
     * Reads CDC robustly from a Mariners Mentor customer/case JSON payload.
     * Besides the known API field names, this also tolerates keys such as
     * "CDC No", "CDC-No" or "cdc no." by normalising the JSON key name.
     */
    private static String findCdcNumber(JsonNode node) {
        if (node == null || node.isNull()) {
            return "";
        }

        String direct = findFirstText(
                node,
                "cdc_no",
                "cdcNo",
                "cdc_number",
                "cdcNumber",
                "cdc",
                "cdc_num",
                "cdcNum",
                "cdcnumber",
                "cdc_no_number",
                "cdcNoNumber"
        );

        if (!direct.isBlank()) {
            return direct.trim();
        }

        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();

            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                String normalizedKey = safe(field.getKey())
                        .toLowerCase(Locale.ROOT)
                        .replaceAll("[^a-z0-9]", "");

                if (normalizedKey.equals("cdc")
                        || normalizedKey.equals("cdcno")
                        || normalizedKey.equals("cdcnum")
                        || normalizedKey.equals("cdcnumber")
                        || normalizedKey.equals("cdcnonumber")) {
                    JsonNode value = field.getValue();
                    if (value != null && value.isValueNode()) {
                        String text = safe(value.asText()).trim();
                        if (!text.isBlank()) {
                            return text;
                        }
                    }
                }

                String nested = findCdcNumber(field.getValue());
                if (!nested.isBlank()) {
                    return nested;
                }
            }
        }

        if (node.isArray()) {
            for (JsonNode child : node) {
                String nested = findCdcNumber(child);
                if (!nested.isBlank()) {
                    return nested;
                }
            }
        }

        return "";
    }

    private static String findFirstText(
            JsonNode node,
            String... fieldNames
    ) {
        if (node == null) {
            return "";
        }

        for (String fieldName : fieldNames) {
            JsonNode value = node.get(fieldName);

            if (value != null
                    && !value.isNull()
                    && !value.asText().isBlank()) {
                return value.asText().trim();
            }
        }

        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();

            while (fields.hasNext()) {
                String found =
                        findFirstText(fields.next().getValue(), fieldNames);

                if (!found.isBlank()) {
                    return found;
                }
            }
        }

        if (node.isArray()) {
            for (JsonNode child : node) {
                String found = findFirstText(child, fieldNames);

                if (!found.isBlank()) {
                    return found;
                }
            }
        }

        return "";
    }

    private static boolean isSent(String status) {

        String value = safe(status)
                .trim()
                .toUpperCase(Locale.ROOT);

        return value.equals("SENT")
                || value.startsWith("SENT -")
                || value.equals("ACCEPTED BY META")
                || value.equals("DELIVERED")
                || value.equals("READ");
    }

    private static String quoteSheetName(String sheetName) {
        return "'" + sheetName.replace("'", "''") + "'";
    }

    private static String normalizePhone(String phone) {
        if (isBlank(phone)) {
            return "";
        }

        String digits = phone.replaceAll("\\D", "");

        if (digits.startsWith("0") && digits.length() == 11) {
            digits = digits.substring(1);
        }

        if (digits.length() == 10) {
            digits = "91" + digits;
        }

        return digits;
    }

    private static boolean isRailwayRuntime() {
        return !isBlank(System.getenv("RAILWAY_ENVIRONMENT"))
                || !isBlank(System.getenv("RAILWAY_PROJECT_ID"))
                || !isBlank(System.getenv("RAILWAY_SERVICE_ID"));
    }

    private static boolean isRailwayCaseProcessingEnabled() {
        if (config == null) return false;
        return Boolean.parseBoolean(
                config.getProperty("railway.case.processing.enabled", "false").trim()
        );
    }

    private static void printCaseSummary(
            CaseRow caseRow,
            String mailStatus,
            String whatsappStatus
    ) {
        if (caseRow == null || caseRow.id <= 0) return;

        String assigned = getCoordinatorDisplayName(caseRow.assignedToFullName);
        if (assigned.isBlank()) assigned = "-";

        String summary = "CASE | " + caseRow.id
                + " | Service: " + getCaseDisplayServiceName(caseRow)
                + " | Assigned: " + assigned
                + " | WhatsApp: " + compactDeliveryStatus(whatsappStatus)
                + " | Mail: " + compactDeliveryStatus(mailStatus);

        String previous = LAST_CASE_CONSOLE_SUMMARY.put(caseRow.id, summary);
        if (!summary.equals(previous)) {
            System.out.println(summary);
        }
    }

    private static String compactDeliveryStatus(String status) {
        String value = safe(status).toUpperCase(Locale.ROOT);
        if (value.isBlank() || value.equals("-") || value.contains("NOT REQUIRED")) return "N/A";
        if (value.contains("FAILED") || value.contains("MISSING")) return "FAILED";
        if (value.contains("WAIT") || value.contains("PENDING")) return "WAITING";
        if (value.contains("SENT")
                || value.contains("ACCEPTED")
                || value.contains("ALREADY")
                || value.contains("BLOCKED - ALREADY")) return "SENT";
        if (value.contains("SKIPPED")) return "SENT";
        return value.length() > 18 ? value.substring(0, 18) : value;
    }

    private static String shortStatus(String value) {
        String cleaned = safe(value).replaceAll("\\s+", " ");

        if (cleaned.length() > 180) {
            return cleaned.substring(0, 180);
        }

        return cleaned;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String safe(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    @RestController
    public static class JsuRetirementMailController {

        @GetMapping(value = {"/jsu-retirement-mail/{token}", "/jsu-retirement-mail/{token}/"})
        public ResponseEntity<?> openPreparedMail(
                @PathVariable("token") String token
        ) {
            long caseId = -1L;

            // New links use the numeric Case ID as the dynamic URL suffix.
            // Older signed links remain supported for compatibility.
            String cleanedToken = safe(token);
            if (cleanedToken.matches("\\d+")) {
                try {
                    caseId = Long.parseLong(cleanedToken);
                } catch (NumberFormatException ignored) {
                    caseId = -1L;
                }
            } else {
                caseId = validateAndReadJsuRetirementCaseId(cleanedToken);
            }

            if (caseId <= 0) {
                return jsuMailError(
                        HttpStatus.BAD_REQUEST,
                        "Invalid Link",
                        "This JSU Open Mail link is invalid. Please contact Mariners Mentor."
                );
            }

            try {
                HttpResponse<String> response =
                        sendAuthorizedGetWithRefresh(
                                CASE_DETAIL_API_BASE + caseId + "/",
                                "JSU retirement case details API for ID " + caseId
                        );

                JsonNode root = MAPPER.readTree(response.body());
                JsonNode caseNode = chooseCaseNode(root);
                CaseRow caseRow = MAPPER.treeToValue(caseNode, CaseRow.class);

                if (caseRow.id <= 0) caseRow.id = caseId;
                caseRow = ensureCaseRemarksLoaded(caseRow);

                if (!isJsuRetirementCase(caseRow)) {
                    return jsuMailError(
                            HttpStatus.BAD_REQUEST,
                            "Wrong Service",
                            "This link is not for a JSU Retirement case."
                    );
                }

                CustomerDetails customer = fetchCustomerDetails(caseRow.customer);
                String name = getSurnameFollowedByFirstName(caseRow, customer);
                String rppNumber = extractRppNumber(caseRow.remarks);

                if (name.isBlank() || rppNumber.isBlank()) {
                    return jsuMailError(
                            HttpStatus.BAD_REQUEST,
                            "Details Missing",
                            "Candidate name or RPP No is missing in the case. Required remarks syntax: RPP NO : <number>"
                    );
                }

                String recipient = "retirement_pay_plan@jsu.jp";
                String subject = "Request for Withdrawal of Japan PF Amount - RPP No. " + rppNumber;
                String body = "Dear Sir,\n\n"
                        + "Good day.\n\n"
                        + "I am " + name + ", holder of RPP No. " + rppNumber + ".\n\n"
                        + "I have permanently retired from sea service. During my service, I sailed on a Japanese vessel.\n\n"
                        + "I would like to request the withdrawal of my Japan PF amount. Please advise me on the procedure for this withdrawal.\n\n"
                        + "Thank you for your assistance.\n\n"
                        + "Regards,\n"
                        + name;

                String mailtoUrl = "mailto:" + recipient
                        + "?subject=" + URLEncoder.encode(subject, StandardCharsets.UTF_8).replace("+", "%20")
                        + "&body=" + URLEncoder.encode(body, StandardCharsets.UTF_8).replace("+", "%20");

                String mailtoForHtml = escapeHtml(mailtoUrl);
                String mailtoForJs = mailtoUrl
                        .replace("\\", "\\\\")
                        .replace("'", "\\'");

                String html = """
                        <!doctype html>
                        <html>
                        <head>
                            <meta charset="UTF-8">
                            <meta name="viewport" content="width=device-width, initial-scale=1">
                            <title>Open Mail</title>
                            <style>
                                body { font-family: Arial, sans-serif; background:#f5f7f8; margin:0; padding:30px 16px; text-align:center; }
                                .box { max-width:520px; margin:40px auto; background:white; padding:28px; border-radius:14px; box-shadow:0 3px 14px rgba(0,0,0,.10); }
                                h2 { color:#087333; margin-top:0; }
                                a { display:inline-block; margin-top:14px; padding:14px 24px; background:#087333; color:white; text-decoration:none; border-radius:8px; font-weight:bold; }
                                p { line-height:1.5; color:#333; }
                            </style>
                        </head>
                        <body>
                        <div class="box">
                            <h2>Opening your mail app...</h2>
                            <p>If your phone does not open the mail app automatically, tap the button below.</p>
                            <a href="%s">Open Mail App</a>
                        </div>
                        <script>
                            window.location.href = '%s';
                        </script>
                        </body>
                        </html>
                        """.formatted(mailtoForHtml, mailtoForJs);

                System.out.println(
                        "JSU RETIREMENT MAIL APP"
                                + " | Case: " + caseId
                                + " | Name: " + name
                                + " | RPP No: " + rppNumber
                );

                // Open the user's default mail app (Gmail/Outlook/etc.) instead of Gmail Web.
                // WhatsApp's in-app browser may block automatic external-app launching; the
                // fallback button above provides a one-tap mailto: link in that case.
                return ResponseEntity
                        .ok()
                        .contentType(MediaType.TEXT_HTML)
                        .body(html);

            } catch (Exception e) {
                System.err.println(
                        "JSU RETIREMENT OPEN MAIL FAILED | Case: "
                                + caseId + " | " + e.getMessage()
                );
                return jsuMailError(
                        HttpStatus.INTERNAL_SERVER_ERROR,
                        "Unable to Open Mail",
                        "Please try again or contact Mariners Mentor."
                );
            }
        }

        private static ResponseEntity<String> jsuMailError(
                HttpStatus status,
                String title,
                String message
        ) {
            return ResponseEntity
                    .status(status)
                    .contentType(MediaType.TEXT_HTML)
                    .body(simpleJsuMailPage(title, message));
        }

        private static String escapeHtml(String value) {
            if (value == null) {
                return "";
            }
            return value
                    .replace("&", "&amp;")
                    .replace("<", "&lt;")
                    .replace(">", "&gt;")
                    .replace("\"", "&quot;")
                    .replace("'", "&#39;");
        }

        private static String simpleJsuMailPage(String title, String message) {
            return """
                    <!doctype html><html><head><meta charset="UTF-8"><meta name="viewport" content="width=device-width,initial-scale=1">
                    <title>%s</title><style>body{font-family:Arial,sans-serif;background:#f4f7f9;padding:30px}.card{max-width:520px;margin:auto;background:white;padding:28px;border-radius:12px;text-align:center}</style>
                    </head><body><div class="card"><h2>%s</h2><p>%s</p></div></body></html>
                    """.formatted(
                    JsuBalanceController.html(title),
                    JsuBalanceController.html(title),
                    JsuBalanceController.html(message)
            );
        }
    }

    @RestController
    public static class SpfoInitialMailController {

        @GetMapping(value = {"/spfo-initial-mail/{caseId}", "/spfo-initial-mail/{caseId}/"}, produces = "text/html;charset=UTF-8")
        public ResponseEntity<String> showForm(@PathVariable("caseId") long caseId) {
            if (caseId <= 0) return error("Invalid Link", "Please contact Mariners Mentor.");
            try {
                CaseRow caseRow = loadCase(caseId);
                if (!isSpfoInitialAccountProblemCase(caseRow)) {
                    return error("Wrong Service", "This link is not for SPFO Initial A/C Problem.");
                }
                CustomerDetails customer = fetchCustomerDetails(caseRow.customer);
                String name = getSurnameFollowedByFirstName(caseRow, customer);
                if (name.isBlank()) name = safe(caseRow.customerFullName);
                return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(formPage(caseId, name, safe(customer.cdcNo)));
            } catch (Exception e) {
                System.err.println("SPFO INITIAL MAIL FORM FAILED | Case: " + caseId + " | " + e.getMessage());
                return error("Unable to Open Form", "Please try again or contact Mariners Mentor.");
            }
        }

        @PostMapping(value = "/spfo-initial-mail/{caseId}/open", produces = "text/html;charset=UTF-8")
        public ResponseEntity<String> openMail(
                @PathVariable("caseId") long caseId,
                @RequestParam(value="vesselName", required=false) List<String> vesselNames,
                @RequestParam(value="fromDate", required=false) List<String> fromDates,
                @RequestParam(value="toDate", required=false) List<String> toDates) {
            try {
                CaseRow caseRow = loadCase(caseId);
                if (!isSpfoInitialAccountProblemCase(caseRow)) return error("Wrong Service", "This link is not for SPFO Initial A/C Problem.");
                CustomerDetails customer = fetchCustomerDetails(caseRow.customer);
                String name = getSurnameFollowedByFirstName(caseRow, customer);
                if (name.isBlank()) name = safe(caseRow.customerFullName);
                String cdc = safe(customer.cdcNo);
                String phone = safe(customer.phone);

                StringBuilder vessels = new StringBuilder();
                int n = Math.max(vesselNames == null ? 0 : vesselNames.size(), Math.max(fromDates == null ? 0 : fromDates.size(), toDates == null ? 0 : toDates.size()));
                for (int i=0; i<n; i++) {
                    String v = item(vesselNames,i), f=item(fromDates,i), t=item(toDates,i);
                    if (v.isBlank() && f.isBlank() && t.isBlank()) continue;
                    vessels.append("Vessel Name : ").append(v).append("\nFrom : ").append(f).append("   To : ").append(t).append("\n\n");
                }
                if (vessels.length()==0) vessels.append("Vessel Name : \nFrom :    To : \n\n");

                String recipient = "spfo-commr@spfo.gov.in";
                String subject = "Request to Solve Login Problem";
                String body = "Dear Team,\n\nMy details are as follows:\n"
                        + "Name : " + name + "\n"
                        + "CDC No : " + cdc + "\n\n"
                        + vessels
                        + "I am trying to check my balance, but I am facing a login issue.\n"
                        + "It shows invalid username and password.\n\n"
                        + "Kindly solve this issue at your earliest convenience.\n"
                        + "Looking forward to your support.\n\n"
                        + "Thank you.\n\n"
                        + name + "\n"
                        + "Ph. No : " + phone;
                String mailto = "mailto:" + recipient
                        + "?subject=" + enc(subject) + "&body=" + enc(body);
                return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(openPage(mailto));
            } catch (Exception e) {
                System.err.println("SPFO INITIAL OPEN MAIL FAILED | Case: " + caseId + " | " + e.getMessage());
                return error("Unable to Open Mail", "Please try again or contact Mariners Mentor.");
            }
        }

        private static CaseRow loadCase(long caseId) throws Exception {
            HttpResponse<String> response = sendAuthorizedGetWithRefresh(CASE_DETAIL_API_BASE + caseId + "/", "SPFO initial case details API for ID " + caseId);
            JsonNode root = MAPPER.readTree(response.body());
            JsonNode caseNode = chooseCaseNode(root);
            CaseRow row = MAPPER.treeToValue(caseNode, CaseRow.class);
            if (row.id <= 0) row.id = caseId;
            return ensureCaseRemarksLoaded(row);
        }
        private static String item(List<String> x,int i){ return x!=null && i<x.size()?safe(x.get(i)):""; }
        private static String enc(String x){ return URLEncoder.encode(x, StandardCharsets.UTF_8).replace("+","%20"); }
        private static String h(String x){ return safe(x).replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&#39;"); }

        private static String formPage(long caseId, String name, String cdc) {
            return """
<!doctype html><html><head><meta charset="UTF-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>SPFO Login Problem</title>
<style>body{margin:0;background:#f3f7fa;font-family:Arial,sans-serif;color:#17324d}.top{background:#0875bd;display:flex;justify-content:center;align-items:center;gap:14px;padding:14px}.logo{max-width:70px;max-height:70px}.brand{color:#fff;font-size:25px;font-weight:800}.card{max-width:650px;margin:28px auto;background:#fff;padding:28px;border-radius:12px;box-shadow:0 8px 28px #183b5b1c}h2{color:#087333}label{font-weight:700;display:block;margin:12px 0 6px}input{box-sizing:border-box;width:100%%;padding:12px;border:1px solid #c9d3dc;border-radius:7px;font-size:16px}.row{display:grid;grid-template-columns:2fr 1fr 1fr;gap:8px;margin-bottom:10px}.add{background:#e8f3ff;color:#075f9b;border:1px solid #9fc9e6;padding:10px 14px;border-radius:7px;font-weight:700}.send{width:100%%;margin-top:18px;background:#087333;color:#fff;border:0;padding:14px;border-radius:8px;font-size:17px;font-weight:700}@media(max-width:600px){.card{margin:16px 10px;padding:20px}.row{grid-template-columns:1fr}.brand{font-size:20px}}</style></head>
<body><div class="top"><img class="logo" src="/jsu-logo" alt="Mariners Mentor"><span class="brand">Mariners Mentor</span></div><div class="card"><h2>SPFO Login Problem</h2>
<label>Name</label><input value="%s" readonly><label>CDC No</label><input value="%s" readonly>
<form method="post" action="/spfo-initial-mail/%d/open"><label>Vessel Details</label><div id="vessels"><div class="row"><input name="vesselName" placeholder="Vessel Name" required><input name="fromDate" placeholder="From"><input name="toDate" placeholder="To"></div></div><button class="add" type="button" onclick="addVessel()">+ Add Vessel</button><button class="send" type="submit">Open Mail</button></form></div>
<script>function addVessel(){const d=document.createElement('div');d.className='row';d.innerHTML='<input name="vesselName" placeholder="Vessel Name" required><input name="fromDate" placeholder="From"><input name="toDate" placeholder="To">';document.getElementById('vessels').appendChild(d)}</script></body></html>
""".formatted(h(name),h(cdc),caseId);
        }
        private static String openPage(String mailto) {
            String e=h(mailto), js=mailto.replace("\\","\\\\").replace("'","\\'");
            return """<!doctype html><html><head><meta charset="UTF-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Open Mail</title><style>body{font-family:Arial;background:#f3f7fa;text-align:center;margin:0}.top{background:#0875bd;color:white;padding:18px;font-size:25px;font-weight:800}.box{max-width:520px;margin:40px auto;background:white;padding:28px;border-radius:12px}.btn{display:inline-block;background:#087333;color:white;padding:14px 24px;border-radius:8px;text-decoration:none;font-weight:bold}</style></head><body><div class="top">Mariners Mentor</div><div class="box"><h2>Opening your mail app...</h2><p>If it does not open automatically, tap below.</p><a class="btn" href="%s">Open Mail App</a></div><script>window.location.href='%s';</script></body></html>""".formatted(e,js);
        }
        private static ResponseEntity<String> error(String title,String msg){ return ResponseEntity.badRequest().contentType(MediaType.TEXT_HTML).body("<html><body style='font-family:Arial;text-align:center;padding:40px'><h2>"+h(title)+"</h2><p>"+h(msg)+"</p></body></html>"); }
    }

    @RestController
    public static class JsuBalanceController {

        private static final DateTimeFormatter JSU_DOB_FORMAT =
                DateTimeFormatter.ofPattern("dd/MM/uuuu")
                        .withResolverStyle(
                                java.time.format.ResolverStyle.STRICT
                        );

        @GetMapping(
                value = "/jsu-balance",
                produces = "text/html;charset=UTF-8"
        )
        public String showBalanceForm(
                @RequestParam("token") String token
        ) {

            if (!isValidJsuBalanceToken(token)) {
                return expiredPage();
            }

            return page(
                    "",
                    "",
                    "",
                    false,
                    token
            );
        }

        @GetMapping(value = "/jsu-logo", produces = "image/png")
        public byte[] showLogo() throws IOException {
            try (InputStream input = Main.class.getClassLoader()
                    .getResourceAsStream("mariners-mentor-logo.png")) {
                if (input == null) {
                    throw new IOException(
                            "mariners-mentor-logo.png was not found in resources"
                    );
                }
                return input.readAllBytes();
            }
        }

        @PostMapping(
                value = "/jsu-balance/check",
                produces = "text/html;charset=UTF-8"
        )
        public String checkBalance(
                @RequestParam("name") String name,
                @RequestParam("dob") String dob,
                @RequestParam("token") String token
        ) {

            if (!isValidJsuBalanceToken(token)) {
                return expiredPage();
            }

            String cleanName = safe(name)
                    .replaceAll("\\s+", " ")
                    .toUpperCase(Locale.ROOT);

            if (cleanName.isBlank()
                    || cleanName.length() > 100
                    || !cleanName.matches("[A-Z .'-]+")) {

                return page(
                        cleanName,
                        safe(dob),
                        "Please enter the name exactly as shown in the passport.",
                        true,
                        token
                );
            }

            final LocalDate birthDate;

            try {
                birthDate = LocalDate.parse(
                        safe(dob),
                        JSU_DOB_FORMAT
                );
            } catch (Exception e) {
                return page(
                        cleanName,
                        safe(dob),
                        "Invalid date. Please use DD/MM/YYYY.",
                        true,
                        token
                );
            }

            try {
                JsuBalanceResult result = fetchJsuBalance(
                        cleanName,
                        birthDate,
                        ""
                );

                return resultPage(result);

            } catch (Exception e) {
                System.err.println(
                        "JSU BALANCE CHECK FAILED | "
                                + cleanName + " | " + e.getMessage()
                );

                return page(
                        cleanName,
                        safe(dob),
                        "Unable to retrieve the JSU balance now. Please verify the details and try again.",
                        true,
                        token
                );
            }
        }

        private static JsuBalanceResult fetchJsuBalance(
                String passportName,
                LocalDate birthDate,
                String rppNumber
        ) {

            WebDriver driver = createAutomationChromeDriver();

            try {
                WebDriverWait wait = new WebDriverWait(
                        driver,
                        Duration.ofSeconds(40)
                );

                driver.get(JSU_LOGIN_URL);

                wait.until(
                        ExpectedConditions.visibilityOfElementLocated(
                                By.id("name")
                        )
                ).sendKeys(passportName);

                driver.findElement(By.id("birthday_DD"))
                        .sendKeys(String.format("%02d", birthDate.getDayOfMonth()));

                driver.findElement(By.id("birthday_MM"))
                        .sendKeys(String.format("%02d", birthDate.getMonthValue()));

                driver.findElement(By.id("birthday_YYYY"))
                        .sendKeys(String.valueOf(birthDate.getYear()));

                // JSU Balance requires the RPP number as well as passport name + DOB.
                // Current JSU page field: <input id="rppno" name="rppno" ...>
                WebElement rppField = wait.until(
                        ExpectedConditions.visibilityOfElementLocated(By.id("rppno"))
                );
                rppField.clear();
                if (!safe(rppNumber).isBlank()) {
                    rppField.sendKeys(safe(rppNumber).trim());
                }

                driver.findElement(
                        By.cssSelector("button[type='submit']")
                ).click();

                // The JSU result page layout can change. Do not depend on an old
                // CSS class such as div.amount-cell-left. Read the value from the
                // visible "Cumulative Contribution Amount" row/text instead.
                String amount = wait.until(driverInstance -> {
                    try {
                        String bodyText = driverInstance.findElement(By.tagName("body"))
                                .getText();

                        Matcher amountMatcher = Pattern.compile(
                                "(?is)Cumulative\\s+Contribution\\s+Amount"
                                        + "\\s*(?:[:\\-]|\\r?\\n|\\s)*"
                                        + "([0-9][0-9,]*(?:\\.[0-9]+)?"
                                        + "(?:\\s*(?:US\\s*\\$|US\\$|USD))?)"
                        ).matcher(bodyText);

                        if (amountMatcher.find()) {
                            String found = amountMatcher.group(1)
                                    .replaceAll("\\s+", " ")
                                    .trim();
                            if (!found.isBlank()) {
                                return found;
                            }
                        }

                        // DOM fallback: locate the row containing the label and read
                        // the last cell. This survives class-name changes.
                        List<WebElement> labelRows = driverInstance.findElements(
                                By.xpath(
                                        "//tr[.//*[contains("
                                                + "translate(normalize-space(.),"
                                                + "'abcdefghijklmnopqrstuvwxyz',"
                                                + "'ABCDEFGHIJKLMNOPQRSTUVWXYZ'),"
                                                + "'CUMULATIVE CONTRIBUTION AMOUNT')]]"
                                )
                        );

                        for (WebElement row : labelRows) {
                            List<WebElement> cells = row.findElements(
                                    By.xpath("./th|./td")
                            );
                            if (cells.size() >= 2) {
                                String found = cells.get(cells.size() - 1)
                                        .getText()
                                        .replaceAll("\\s+", " ")
                                        .trim();
                                if (!found.isBlank()
                                        && !found.toUpperCase(Locale.ROOT)
                                        .contains("CUMULATIVE CONTRIBUTION AMOUNT")) {
                                    return found;
                                }
                            }
                        }
                    } catch (Exception ignored) {
                        // Keep waiting until the result page finishes rendering.
                    }
                    return null;
                });

                if (amount == null || amount.isBlank()) {
                    throw new IllegalStateException(
                            "JSU returned an empty cumulative contribution amount"
                    );
                }

                List<JsuContribution> contributions = new ArrayList<>();

                // No Tampermonkey/userscript is needed. If the JSU site hides
                // the vessel table behind a More/Show Details control, open it
                // directly with Selenium before reading the rows.
                try {
                    List<WebElement> detailButtons = driver.findElements(By.xpath(
                            "//button[contains(translate(normalize-space(.),'abcdefghijklmnopqrstuvwxyz','ABCDEFGHIJKLMNOPQRSTUVWXYZ'),'DETAIL')]"
                                    + " | //a[contains(translate(normalize-space(.),'abcdefghijklmnopqrstuvwxyz','ABCDEFGHIJKLMNOPQRSTUVWXYZ'),'DETAIL')]"
                                    + " | //*[@role='button' and contains(translate(normalize-space(.),'abcdefghijklmnopqrstuvwxyz','ABCDEFGHIJKLMNOPQRSTUVWXYZ'),'DETAIL')]"
                    ));
                    for (WebElement detailButton : detailButtons) {
                        try {
                            if (detailButton.isDisplayed() && detailButton.isEnabled()) {
                                ((JavascriptExecutor) driver).executeScript(
                                        "arguments[0].click();",
                                        detailButton
                                );
                                Thread.sleep(500);
                                break;
                            }
                        } catch (Exception ignored) {
                            // Try the next possible details control.
                        }
                    }
                } catch (Exception ignored) {
                    // Some JSU result pages show the table immediately.
                }

                List<WebElement> rows = new ArrayList<>();
                try {
                    WebDriverWait detailsWait = new WebDriverWait(driver, Duration.ofSeconds(12));
                    rows = detailsWait.until(driverInstance -> {
                        List<WebElement> foundRows = driverInstance.findElements(By.xpath(
                                "//table[.//*[contains(translate(normalize-space(.),'abcdefghijklmnopqrstuvwxyz','ABCDEFGHIJKLMNOPQRSTUVWXYZ'),'VESSEL NAME')]]//tbody/tr"
                        ));
                        return foundRows.isEmpty() ? null : foundRows;
                    });
                } catch (Exception detailError) {
                    System.err.println(
                            "JSU VESSEL DETAILS NOT FOUND - SENDING CUMULATIVE AMOUNT ONLY | "
                                    + detailError.getMessage()
                    );
                }

                String vesselName = "";

                for (WebElement row : rows) {
                    List<WebElement> cells = row.findElements(By.tagName("td"));

                    if (cells.size() == 1) {
                        vesselName = cells.get(0).getText().trim();
                    } else if (cells.size() >= 3 && !vesselName.isBlank()) {
                        contributions.add(new JsuContribution(
                                vesselName,
                                cells.get(0).getText().trim(),
                                cells.get(1).getText().trim(),
                                cells.get(2).getText().trim()
                        ));
                        vesselName = "";
                    }
                }

                String normalizedAmount = amount
                        .replaceAll("\\s+", " ")
                        .trim();

                if (!normalizedAmount.toUpperCase(Locale.ROOT).contains("US $")
                        && !normalizedAmount.toUpperCase(Locale.ROOT).contains("USD")
                        && !normalizedAmount.toUpperCase(Locale.ROOT).contains("US$")) {
                    normalizedAmount = normalizedAmount + " US $";
                }

                return new JsuBalanceResult(
                        normalizedAmount,
                        contributions
                );

            } finally {
                driver.quit();
            }
        }

        private static String page(
                String name,
                String dob,
                String message,
                boolean error,
                String token
        ) {

            String alert = message.isBlank()
                    ? ""
                    : "<div class='alert "
                      + (error ? "error" : "success")
                      + "'>" + html(message) + "</div>";

            String formHtml = """
                    <!doctype html>
                    <html lang="en">
                    <head>
                      <meta charset="UTF-8">
                      <meta name="viewport" content="width=device-width,initial-scale=1">
                      <meta name="robots" content="noindex,nofollow">
                      <meta http-equiv="Cache-Control" content="no-store, no-cache, must-revalidate">
                      <title>JSU Balance Query</title>
                      <style>
                        *{box-sizing:border-box}body{margin:0;background:#f3f7fa;font-family:Arial,sans-serif;color:#17324d}
                        .top{background:#0875bd;display:flex;justify-content:center;align-items:center;gap:14px;padding:14px 12px}.logo{display:block;max-width:76px;max-height:76px;width:auto;height:auto}.brand{color:#fff;font-size:27px;font-weight:800}
                        .card{max-width:540px;margin:34px auto;background:#fff;padding:30px;border-radius:12px;box-shadow:0 8px 28px #183b5b1c}
                        h1{margin:0 0 8px;text-align:center;font-size:28px}.note{text-align:center;color:#657b8f;margin:0 0 26px}
                        label{display:block;font-weight:700;margin:18px 0 7px}input{width:100%;padding:14px;border:1px solid #b9c9d7;border-radius:7px;font-size:17px;text-transform:uppercase}
                        button{width:100%;margin-top:24px;padding:14px;border:0;border-radius:8px;background:#0875bd;color:#fff;font-size:19px;font-weight:700;cursor:pointer}
                        button:hover{background:#045f9b}.alert{padding:13px;border-radius:7px;margin-bottom:18px}.error{background:#ffe8e8;color:#a42020}.success{background:#e6f8eb;color:#176b31}
                        .privacy{font-size:12px;color:#718497;text-align:center;margin-top:18px}
                        @media(max-width:600px){body{padding:0 12px}.top{margin:0 -12px;padding:12px 10px}.logo{max-width:62px;max-height:62px}.brand{font-size:21px}.card{width:100%;margin:18px auto;padding:22px 18px;border-radius:9px}h1{font-size:24px}input{font-size:16px}button{font-size:17px}}
                      </style>
                    </head>
                    <body>
                      <div class="top"><img class="logo" src="/jsu-logo" alt="Mariners Mentor"><span class="brand">Mariners Mentor</span></div>
                      <main class="card">
                        <h1>JSU Balance Query</h1>
                        <p class="note">Enter the details exactly as Per in the passport.</p>
                        {{ALERT}}
                        <form method="post" action="/jsu-balance/check">
                          <input type="hidden" name="token" value="{{TOKEN}}">
                          <label for="name">Name as per Passport</label>
                          <input id="name" name="name" maxlength="100" required value="{{NAME}}" placeholder="Surname Followed By Given Name">
                          <label for="dob">Date of Birth (DD/MM/YYYY)</label>
                          <input id="dob" name="dob" maxlength="10" inputmode="numeric" required value="{{DOB}}" placeholder="DD/MM/YYYY" pattern="\\d{2}/\\d{2}/\\d{4}">
                          <button type="submit">Check Balance</button>
                        </form>
                        <div class="privacy">Your details are used only to check the JSU balance.</div>
                      </main>
                    </body>
                    </html>
                    """;

            return formHtml
                    .replace("{{ALERT}}", alert)
                    .replace("{{NAME}}", html(name))
                    .replace("{{DOB}}", html(dob))
                    .replace("{{TOKEN}}", html(token));
        }

        private static String expiredPage() {
            return """
                    <!doctype html>
                    <html lang="en">
                    <head>
                      <meta charset="UTF-8">
                      <meta name="viewport" content="width=device-width,initial-scale=1">
                      <meta name="robots" content="noindex,nofollow">
                      <title>JSU Balance Link Expired</title>
                      <style>
                        body{margin:0;background:#f3f7fa;font-family:Arial,sans-serif;color:#17324d}.top{background:#0875bd;display:flex;justify-content:center;align-items:center;gap:14px;padding:14px}.logo{max-width:70px;max-height:70px}.brand{color:#fff;font-size:25px;font-weight:800}.card{max-width:520px;margin:40px auto;background:#fff;padding:32px;border-radius:12px;text-align:center;box-shadow:0 8px 28px #183b5b1c}h1{color:#b42318;font-size:25px}p{line-height:1.6}@media(max-width:600px){body{padding:0 12px}.top{margin:0 -12px}.brand{font-size:20px}.card{margin:22px auto;padding:25px 18px}}
                      </style>
                    </head>
                    <body>
                      <div class="top"><img class="logo" src="/jsu-logo" alt="Mariners Mentor"><span class="brand">Mariners Mentor</span></div>
                      <main class="card"><h1>Link Expired</h1><p>This secure JSU balance link is valid for two hours only. Please contact Mariners Mentor for a new link.</p></main>
                    </body>
                    </html>
                    """;
        }

        private static String resultPage(JsuBalanceResult result) {
            StringBuilder detailRows = new StringBuilder();

            for (JsuContribution contribution : result.contributions()) {
                detailRows.append("<tr class='vessel'><td colspan='3'>")
                        .append(html(contribution.vesselName()))
                        .append("</td></tr><tr><td>")
                        .append(html(contribution.doo()))
                        .append("</td><td>")
                        .append(html(contribution.dod()))
                        .append("</td><td class='number'>")
                        .append(html(contribution.amount()))
                        .append("</td></tr>");
            }

            String moreDetails = detailRows.isEmpty()
                    ? ""
                    : """
                      <button class="details-button" type="button" onclick="toggleDetails()" aria-expanded="false" id="detailsButton">More Details</button>
                      <section id="details" class="details" hidden>
                        <div class="table-wrap">
                          <table>
                            <thead>
                              <tr><th colspan="3">Vessel Name</th></tr>
                              <tr><th>DOO (D/M/Y)</th><th>DOD (D/M/Y)</th><th>Contribution Amount (US $)</th></tr>
                            </thead>
                            <tbody>{{DETAIL_ROWS}}</tbody>
                          </table>
                        </div>
                      </section>
                      <script>
                        function toggleDetails() {
                          const details = document.getElementById('details');
                          const button = document.getElementById('detailsButton');
                          const willOpen = details.hidden;
                          details.hidden = !willOpen;
                          button.textContent = willOpen ? 'Hide Details' : 'More Details';
                          button.setAttribute('aria-expanded', String(willOpen));
                        }
                      </script>
                      """.replace("{{DETAIL_ROWS}}", detailRows.toString());

            String resultHtml = """
                    <!doctype html>
                    <html lang="en">
                    <head>
                      <meta charset="UTF-8">
                      <meta name="viewport" content="width=device-width,initial-scale=1">
                      <meta name="robots" content="noindex,nofollow">
                      <meta http-equiv="Cache-Control" content="no-store, no-cache, must-revalidate">
                      <title>JSU Balance Result</title>
                      <style>
                        body{margin:0;background:#f3f7fa;font-family:Arial,sans-serif;color:#17324d}.top{background:#0875bd;display:flex;justify-content:center;align-items:center;gap:14px;padding:14px 12px}.logo{display:block;max-width:76px;max-height:76px;width:auto;height:auto}.brand{color:#fff;font-size:27px;font-weight:800}
                        .card{max-width:560px;margin:34px auto;background:#fff;padding:34px 30px;border-radius:12px;box-shadow:0 8px 28px #183b5b1c;text-align:center}h1{margin:0 0 22px;font-size:25px}
                        .amount{background:#eaf7ef;border:1px solid #9fd5b1;color:#087333;border-radius:10px;padding:24px 16px;font-size:38px;font-weight:800}
                        .details-button{margin-top:20px;padding:12px 22px;border:0;border-radius:8px;background:#0875bd;color:#fff;font-size:17px;font-weight:700;cursor:pointer}.details{margin-top:18px;text-align:left}.table-wrap{overflow-x:auto;border:1px solid #c8d6e2;border-radius:7px}table{width:100%;border-collapse:collapse;min-width:500px}th{background:#0875bd;color:#fff;text-align:center;padding:9px;border:1px solid #d4e1eb;font-size:14px}td{padding:8px;border:1px solid #d4e1eb;color:#111}.vessel td{background:#f0f2f4;font-weight:700}.number{text-align:right}
                        @media(max-width:600px){body{padding:0 12px}.top{margin:0 -12px;padding:12px 10px}.logo{max-width:62px;max-height:62px}.brand{font-size:21px}.card{width:100%;margin:18px auto;padding:25px 18px;border-radius:9px}h1{font-size:22px}.amount{font-size:32px;padding:20px 12px}.details-button{width:100%}}
                      </style>
                    </head>
                    <body>
                      <div class="top"><img class="logo" src="/jsu-logo" alt="Mariners Mentor"><span class="brand">Mariners Mentor</span></div>
                      <main class="card">
                        <h1>Cumulative Contribution Amount</h1>
                        <div class="amount">{{AMOUNT}}</div>
                        {{MORE_DETAILS}}
                      </main>
                    </body>
                    </html>
                    """;

            return resultHtml
                    .replace("{{AMOUNT}}", html(result.amount()))
                    .replace("{{MORE_DETAILS}}", moreDetails);
        }

        private record JsuBalanceResult(
                String amount,
                List<JsuContribution> contributions
        ) {}

        private record JsuContribution(
                String vesselName,
                String doo,
                String dod,
                String amount
        ) {}

        private static String html(String value) {
            return safe(value)
                    .replace("&", "&amp;")
                    .replace("<", "&lt;")
                    .replace(">", "&gt;")
                    .replace("\"", "&quot;")
                    .replace("'", "&#39;");
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CaseRow {
        public long id;

        @JsonAlias({"customer_full_name", "customerFullName"})
        public String customerFullName;

        @JsonAlias({"first_name", "firstName", "given_name", "givenName", "firstname"})
        public String firstName;

        @JsonAlias({"surname", "last_name", "lastName", "family_name", "familyName", "lastname"})
        public String surname;

        @JsonAlias({"assigned_to_full_name", "assignedToFullName"})
        public String assignedToFullName;

        @JsonAlias({"service_name", "serviceName"})
        public String serviceName;

        // Some case-list API/dashboard builds show the selected service under Title.
        // Membership detection uses this only as a fallback; comments are never required.
        @JsonAlias({"title", "case_title", "caseTitle"})
        public String title;

        @JsonAlias({"created_at", "createdAt"})
        public String createdAt;

        @JsonAlias({
                "created_by_full_name", "createdByFullName",
                "created_by_name", "createdByName",
                "case_created_by", "caseCreatedBy"
        })
        public String createdBy;

        // Case status from dashboard/API.
        // Supports common API field names.
        @JsonAlias({
                "status",
                "case_status",
                "caseStatus",
                "status_name",
                "statusName"
        })
        public String status;

        @JsonAlias({
                "date_of_birth",
                "dateOfBirth",
                "dob",
                "birth_date",
                "birthDate",
                "birthday"
        })
        public String dateOfBirth;

        @JsonAlias({
                "remarks",
                "remark",
                "case_remarks",
                "caseRemarks"
        })
        public String remarks;

        public long customer;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CustomerDetails {

        @JsonAlias({"first_name", "firstName", "given_name", "givenName", "firstname"})
        public String firstName;

        @JsonAlias({"surname", "last_name", "lastName", "family_name", "familyName", "lastname"})
        public String surname;

        @JsonAlias({
                "email",
                "email_id",
                "emailId",
                "customer_email"
        })
        public String email;

        @JsonAlias({
                "phone",
                "phone_no",
                "phone_number",
                "mobile",
                "mobile_no",
                "contact",
                "contact_no",
                "customer_phone"
        })
        public String phone;

        @JsonAlias({
                "date_of_birth",
                "dateOfBirth",
                "dob",
                "birth_date",
                "birthDate",
                "birthday"
        })
        public String dateOfBirth;

        @JsonAlias({
                "cdc_no",
                "cdcNo",
                "cdc_number",
                "cdcNumber",
                "cdc",
                "cdc_num",
                "cdcNum",
                "cdcnumber",
                "cdc_no_number",
                "cdcNoNumber"
        })
        public String cdcNo;
    }

}
