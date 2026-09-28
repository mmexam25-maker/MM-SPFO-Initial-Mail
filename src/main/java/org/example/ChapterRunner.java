package org.example;

import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.NoSuchElementException;
import org.openqa.selenium.StaleElementReferenceException;
import org.openqa.selenium.TimeoutException;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.PageLoadStrategy;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.time.Duration;
import java.lang.management.ManagementFactory;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ChapterRunner {

    /*
     * Four-user PC1 mode. Up to four hidden Chrome sessions can be active.
     * Startup itself is serialized and RAM-guarded so four Chrome processes
     * never stampede the JVM at the same instant.
     */
    private static final int MAX_ACTIVE_BROWSER_SESSIONS = 4;
    private static final Semaphore BROWSER_SESSION_GATE =
            new Semaphore(MAX_ACTIVE_BROWSER_SESSIONS, true);

    // Native-memory safety for true four-user mode. The guard waits rather
    // than crashing the whole JVM when Windows is temporarily short of RAM.
    // A Windows page file is strongly recommended for four simultaneous users.
    private static final long MIN_FREE_MEMORY_MB = 650L;
    private static final long POST_CHROME_MIN_FREE_MEMORY_MB = 350L;
    private static final int MEMORY_CHECK_ATTEMPTS = 18;
    private static final long MEMORY_CHECK_DELAY_MS = 5_000L;
    private static final long CHROME_START_SETTLE_MS = 3_000L;

    private static final Object CHROME_START_LOCK = new Object();

    private static final int CHROME_START_ATTEMPTS = 2;
    private static final long CHROME_START_RETRY_MS = 2_000L;

    private static final String DASHBOARD_URL =
            "https://sagarmeinyog.com/dashboard";

    private static final int MAX_RETRY =
            3;

    private static final int MAX_DASHBOARD_PAGES =
            2;

    public static void runMatchingModulesAndChapters(
            String username,
            String password,
            List<String> moduleNamesToCheck,
            List<QuizPlanRow> completePlan,
            int sheetRowNumber,
            String changedDgPassword,
            String aadhaarNumber
    ) throws Exception {

        Path dgDownloadDir =
                DgProfileSync.prepareWorkingDirectory(sheetRowNumber);

        ChromeOptions options =
                new ChromeOptions();

        // Run Chrome completely in the background.
        // Four-user memory profile: do not load images, keep one renderer per
        // browser where possible, and avoid waiting for non-essential resources.
        options.setPageLoadStrategy(PageLoadStrategy.EAGER);
        Map<String, Object> prefs = new HashMap<>();
        prefs.put("profile.managed_default_content_settings.images", 2);
        prefs.put("profile.default_content_setting_values.notifications", 2);
        prefs.put("profile.default_content_setting_values.popups", 2);
        prefs.put("download.default_directory", dgDownloadDir.toString());
        prefs.put("download.prompt_for_download", false);
        prefs.put("download.directory_upgrade", true);
        prefs.put("plugins.always_open_pdf_externally", true);
        options.setExperimentalOption("prefs", prefs);

        options.addArguments("--headless=new");
        options.addArguments("--window-size=1366,768");
        options.addArguments("--disable-gpu");
        options.addArguments("--disable-notifications");
        options.addArguments("--disable-popup-blocking");
        options.addArguments("--disable-extensions");
        options.addArguments("--disable-background-networking");
        options.addArguments("--disable-default-apps");
        options.addArguments("--disable-sync");
        options.addArguments("--metrics-recording-only");
        options.addArguments("--no-first-run");
        options.addArguments("--mute-audio");
        options.addArguments("--renderer-process-limit=1");
        options.addArguments("--process-per-site");
        options.addArguments("--js-flags=--max-old-space-size=192");
        options.addArguments("--disable-application-cache");
        options.addArguments("--disk-cache-size=1");
        options.addArguments("--disable-software-rasterizer");
        options.addArguments("--disable-component-update");
        options.addArguments("--disable-domain-reliability");
        options.addArguments("--disable-features=Translate,MediaRouter,OptimizationHints,AutofillServerCommunication,BackForwardCache");
        
        WebDriver driver = null;
        boolean browserPermitHeld = false;
        boolean closeBrowserAutomatically = true;

        try {
            System.out.println("BROWSER SLOT: waiting if all four browser sessions are busy");
            BROWSER_SESSION_GATE.acquire();
            browserPermitHeld = true;

            driver = startChromeWithMemoryGuard(options);

            // Headless browser already uses the configured 1920x1080 window size.

            Login.login(
                    driver,
                    username,
                    password
            );

            // Before e-learning starts:
            // 1) read SMY Profile name/mobile -> C/D,
            // 2) open DG/eSamudra in a temporary tab,
            // 3) download DG profile PDF, extract photo + latest ship/IMO,
            // 4) upload/update the SMY profile and submit.
            DgProfileSync.syncBeforeLearning(
                    driver,
                    password,
                    changedDgPassword,
                    aadhaarNumber,
                    sheetRowNumber,
                    dgDownloadDir
            );

            System.out.println();
            System.out.println(
                    "===================================="
            );

            System.out.println(
                    "USERNAME        : "
                            + username
            );

            System.out.println(
                    "MODULES TO CHECK : "
                            + moduleNamesToCheck.size()
            );

            System.out.println(
                    "PLAN ROWS       : "
                            + completePlan.size()
            );

            System.out.println(
                    "===================================="
            );

            /*
             * All 10 modules are checked in sequence.
             * The website green Completed badge is the final truth.
             */
            for (String moduleName : moduleNamesToCheck) {

                int moduleNumber =
                        getOriginalModuleNumber(
                                moduleName
                        );

                if (moduleNumber <= 0) {

                    System.out.println();
                    System.out.println(
                            "UNKNOWN MODULE SKIPPED: "
                                    + moduleName
                    );

                    continue;
                }

                int maximumChapterForModule =
                        findMaximumChapterForModule(
                                completePlan,
                                moduleNumber
                        );

                if (maximumChapterForModule <= 0) {

                    System.out.println();
                    System.out.println(
                            "MODULE SKIPPED"
                    );

                    System.out.println(
                            "Module : "
                                    + moduleName
                    );

                    System.out.println(
                            "Reason : No matching Sheet2 rows"
                    );

                    SheetRepository.updateModuleStatus(
                            sheetRowNumber,
                            moduleName,
                            "Pending"
                    );

                    continue;
                }

                // Do not spend an extra Google Sheets API read here.
                // The website green badge is the source of truth and every
                // chapter is verified from chapter 1 in fast continuous mode.
                String savedModuleProgress = "Website verification mode";

                /*
                 * Always inspect every chapter from Chapter 1.
                 * The website green Completed badge is the final truth.
                 * A saved Sheet position must never hide an earlier chapter
                 * that is still Pending on the website.
                 */
                int firstChapterToRun = 1;

                boolean wholeModuleCompleted =
                        true;

                System.out.println();
                System.out.println(
                        "####################################"
                );

                System.out.println(
                        "STARTING MODULE "
                                + moduleNumber
                                + " - "
                                + moduleName
                );

                System.out.println(
                        "TOTAL CHAPTERS : "
                                + maximumChapterForModule
                );

                System.out.println(
                        "SAVED PROGRESS : "
                                + (
                                savedModuleProgress.isBlank()
                                        ? "Blank"
                                        : savedModuleProgress
                        )
                );

                System.out.println(
                        "WEBSITE CHECK FROM : Part "
                                + firstChapterToRun
                                + "/"
                                + maximumChapterForModule
                );

                System.out.println(
                        "####################################"
                );

                for (
                        int chapterNumber = firstChapterToRun;
                        chapterNumber <= maximumChapterForModule;
                        chapterNumber++
                ) {

                    List<QuizPlanRow> chapterRows =
                            rowsFor(
                                    completePlan,
                                    moduleNumber,
                                    chapterNumber
                            );

                    chapterRows.sort(
                            Comparator.comparingInt(
                                    QuizPlanRow::partNumber
                            )
                    );

                    if (chapterRows.isEmpty()) {

                        System.out.println();
                        System.out.println(
                                "Chapter "
                                        + chapterNumber
                                        + " skipped."
                        );

                        System.out.println(
                                "Reason: No matching Sheet2 rows."
                        );

                        continue;
                    }

                    boolean chapterCompleted =
                            false;

                    for (
                            int attempt = 1;
                            attempt <= MAX_RETRY
                                    && !chapterCompleted;
                            attempt++
                    ) {

                        try {

                            System.out.println();
                            System.out.println(
                                    "===================================="
                            );

                            System.out.println(
                                    "MODULE  : "
                                            + moduleNumber
                                            + " - "
                                            + moduleName
                            );

                            System.out.println(
                                    "CHAPTER : "
                                            + chapterNumber
                            );

                            System.out.println(
                                    "PARTS   : "
                                            + chapterRows.size()
                            );

                            System.out.println(
                                    "ATTEMPT : "
                                            + attempt
                                            + "/"
                                            + MAX_RETRY
                            );

                            System.out.println(
                                    "===================================="
                            );

                            /*
                             * Course.completeChapter() returns to the module
                             * chapter list. Reopen the module only for retry
                             * recovery after an actual failure.
                             */
                            if (chapterNumber == firstChapterToRun
                                    || attempt > 1) {
                                openDashboard(driver);
                                Thread.sleep(300);
                                openModule(driver, moduleName);
                            }

                            /*
                             * If the chapter is green, Course skips it.
                             * If it is Pending, process from material 1 so a
                             * stale Sheet resume marker cannot miss anything.
                             */
                            int firstSubPartToRun = 1;

                            /*
                             * Re-check every chapter from Chapter 1, but if THIS
                             * chapter is still pending after a Selenium timeout,
                             * resume from the sub-part already saved in the Sheet.
                             * This prevents retry attempt 2/3 from replaying
                             * sub-part 1 after sub-part 1 was already finished.
                             */
                            try {
                                String latestProgress =
                                        SheetRepository.readModuleStatus(
                                                sheetRowNumber,
                                                moduleName
                                        );

                                firstSubPartToRun = findResumeSubPart(
                                        latestProgress,
                                        chapterNumber,
                                        chapterRows.size()
                                );
                            } catch (Exception ignored) {
                                firstSubPartToRun = 1;
                            }

                            System.out.println(
                                    "RESUME SUB-PART POSITION : "
                                            + firstSubPartToRun
                                            + "/"
                                            + chapterRows.size()
                            );

                            Course.completeChapter(
                                    driver,
                                    moduleNumber,
                                    chapterNumber,
                                    chapterRows,
                                    firstSubPartToRun,
                                    sheetRowNumber,
                                    moduleName,
                                    maximumChapterForModule
                            );

                            chapterCompleted =
                                    true;

                            System.out.println();
                            System.out.println(
                                    "CHAPTER COMPLETED"
                            );

                            System.out.println(
                                    "Module  : "
                                            + moduleName
                            );

                            System.out.println(
                                    "Chapter : "
                                            + chapterNumber
                            );

                            if (
                                    chapterNumber
                                            < maximumChapterForModule
                            ) {

                                SheetRepository.updateModuleStatus(
                                        sheetRowNumber,
                                        moduleName,
                                        progressText(
                                                "",
                                                chapterNumber + 1,
                                                maximumChapterForModule
                                        )
                                );
                            }

                        } catch (Exception chapterError) {

                            if (isNetworkLikeError(chapterError)) {
                                System.out.println();
                                System.out.println("NETWORK/SITE INTERRUPTION - ENDING THIS BROWSER SESSION.");
                                System.out.println("Main will retry the profile on the next pass with no scheduled cooldown.");
                                throw chapterError;
                            }

                            System.out.println();
                            System.out.println(
                                    "!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!"
                            );

                            System.out.println(
                                    "RETRY NEEDED WHILE PROCESSING"
                            );

                            System.out.println(
                                    "Module  : "
                                            + moduleName
                            );

                            System.out.println(
                                    "Chapter : "
                                            + chapterNumber
                            );

                            System.out.println(
                                    "Attempt : "
                                            + attempt
                                            + "/"
                                            + MAX_RETRY
                            );

                            System.out.println(
                                    "Error   : "
                                            + chapterError
                                            .getClass()
                                            .getSimpleName()
                                            + " - "
                                            + chapterError
                                            .getMessage()
                            );

                            System.out.println(
                                    "!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!"
                            );

                            if (attempt < MAX_RETRY) {

                                System.out.println(
                                        "Retrying immediately..."
                                );

                                try {

                                    driver.get(
                                            DASHBOARD_URL
                                    );

                                } catch (Exception ignored) {
                                }

                                Thread.sleep(
                                        700
                                );
                            }
                        }
                    }

                    /*
                     * If one chapter fails after all retries,
                     * stop this module.
                     */
                    if (!chapterCompleted) {

                        wholeModuleCompleted =
                                false;

                        String lastSavedProgress;

                        try {
                            lastSavedProgress =
                                    SheetRepository.readModuleStatus(
                                            sheetRowNumber,
                                            moduleName
                                    );
                        } catch (Exception ignored) {
                            lastSavedProgress = "";
                        }

                        String errorProgress;

                        if (lastSavedProgress != null
                                && lastSavedProgress.toLowerCase()
                                .contains("sub-part")) {

                            errorProgress = lastSavedProgress.replaceFirst(
                                    "(?i)^\\s*Running\\s*",
                                    "Pending "
                            );

                        } else {

                            errorProgress =
                                    "Pending Part "
                                            + chapterNumber
                                            + "/"
                                            + maximumChapterForModule
                                            + " Sub-part 1/"
                                            + chapterRows.size();
                        }

                        SheetRepository.updateModuleStatus(
                                sheetRowNumber,
                                moduleName,
                                errorProgress
                        );

                        System.out.println();
                        System.out.println(
                                "MODULE STOPPED"
                        );

                        System.out.println(
                                "Module : "
                                        + moduleName
                        );

                        System.out.println(
                                "Failed chapter : "
                                        + chapterNumber
                        );

                        break;
                    }
                }

                /*
                 * All chapters completed successfully.
                 */
                if (wholeModuleCompleted) {
                    // Two independent website passes before allowing the Sheet
                    // to say Completed. This protects against a transient/stale
                    // React render immediately after the last quiz.
                    boolean firstWebsitePass =
                            Course.areAllVisibleChaptersCompleted(
                                    driver,
                                    maximumChapterForModule
                            );

                    boolean secondWebsitePass = false;

                    if (firstWebsitePass) {
                        Thread.sleep(900);
                        secondWebsitePass =
                                Course.areAllVisibleChaptersCompleted(
                                        driver,
                                        maximumChapterForModule
                                );
                    }

                    wholeModuleCompleted =
                            firstWebsitePass && secondWebsitePass;

                    if (!wholeModuleCompleted) {
                        SheetRepository.updateModuleStatus(
                                sheetRowNumber,
                                moduleName,
                                "Pending - Website chapter incomplete"
                        );
                    }
                }

                if (wholeModuleCompleted) {

                    SheetRepository.updateModuleStatus(
                            sheetRowNumber,
                            moduleName,
                            "Completed"
                    );

                    System.out.println();
                    System.out.println(
                            "####################################"
                    );

                    System.out.println(
                            "MODULE COMPLETED"
                    );

                    System.out.println(
                            "Module : "
                                    + moduleName
                    );

                    System.out.println(
                            "Status : Completed"
                    );

                    System.out.println(
                            "Saved in PC1 row : "
                                    + sheetRowNumber
                    );

                    System.out.println(
                            "####################################"
                    );

                } else {

                    System.out.println();
                    System.out.println(
                            "MODULE NOT COMPLETED"
                    );

                    System.out.println(
                            "Module : "
                                    + moduleName
                    );

                    System.out.println(
                            "Status : Pending retry saved"
                    );

                    System.out.println(
                            "STRICT ORDER: later modules will NOT be opened."
                    );
                    System.out.println(
                            "This same pending module will be retried on the next immediate pass."
                    );

                    // CRITICAL: Never continue to a later course while the
                    // current course is still pending. This is the strict
                    // one-by-one behavior requested for no-gap mode.
                    break;
                }
            }

            System.out.println();
            System.out.println(
                    "===================================="
            );

            System.out.println(
                    "USER MODULE RUN FINISHED"
            );

            System.out.println(
                    "Username : "
                            + username
            );

            System.out.println(
                    "===================================="
            );

        } catch (Exception mainRunnerError) {

            System.out.println();

            if (isNetworkLikeError(mainRunnerError)) {
                System.out.println("USER SESSION PAUSED - NETWORK/LOGIN ISSUE");
            } else if (isLowMemoryError(mainRunnerError)) {
                System.out.println("USER SESSION PAUSED - LOW MEMORY");
            } else {
                System.out.println("USER SESSION PAUSED");
            }

            System.out.println("Username : " + username);
            System.out.println("Reason   : " + conciseMessage(mainRunnerError));

            throw mainRunnerError;

        } finally {

            if (closeBrowserAutomatically && driver != null) {

                try {
                    driver.quit();
                } catch (Exception ignored) {
                }

                // Encourage prompt cleanup between modules/users on low-RAM PCs.
                driver = null;
                System.gc();
            }

            if (browserPermitHeld) {
                BROWSER_SESSION_GATE.release();
                System.out.println("BROWSER QUEUE: low-memory browser slot released");
            }
        }
    }

    /*
     * Finds the permanent module number.
     */
    private static int getOriginalModuleNumber(
            String moduleName
    ) {

        for (
                int index = 0;
                index < SheetRepository.ALL_MODULES.size();
                index++
        ) {

            String originalModule =
                    SheetRepository
                            .ALL_MODULES
                            .get(index);

            if (
                    normalizeModuleName(
                            originalModule
                    ).equalsIgnoreCase(
                            normalizeModuleName(
                                    moduleName
                            )
                    )
            ) {

                return index + 1;
            }
        }

        return -1;
    }

    /*
     * Finds the highest chapter number
     * for the module.
     */
    private static int findMaximumChapterForModule(
            List<QuizPlanRow> completePlan,
            int moduleNumber
    ) {

        int maximumChapter =
                0;

        for (QuizPlanRow row : completePlan) {

            if (
                    row.moduleNumber() == moduleNumber
                            && row.chapterNumber()
                            > maximumChapter
            ) {

                maximumChapter =
                        row.chapterNumber();
            }
        }

        return maximumChapter;
    }

    private static void openDashboard(
            WebDriver driver
    ) {

        driver.get(
                DASHBOARD_URL
        );

        WebDriverWait wait =
                new WebDriverWait(
                        driver,
                        Duration.ofSeconds(40)
                );

        wait.until(
                ExpectedConditions.urlContains(
                        "/dashboard"
                )
        );

        wait.until(
                ExpectedConditions
                        .presenceOfElementLocated(
                                By.xpath(
                                        "//h2[normalize-space()='All Courses']"
                                )
                        )
        );

        System.out.println();
        System.out.println(
                "Dashboard Opened"
        );
    }

    /*
     * Searches page 1 and page 2
     * and opens the requested module.
     */
    private static void openModule(
            WebDriver driver,
            String moduleName
    ) throws Exception {

        Exception lastError = null;

        /*
         * The SMY dashboard is a React SPA. Under four parallel headless
         * sessions the course cards can appear a few seconds after the
         * All Courses heading. Never fail a whole user because of one late
         * render: reload the dashboard and search all pages again.
         */
        for (int searchAttempt = 1; searchAttempt <= 3; searchAttempt++) {
            try {
                if (searchAttempt > 1) {
                    System.out.println("MODULE SEARCH RETRY " + searchAttempt + "/3 : " + moduleName);
                    openDashboard(driver);
                    Thread.sleep(1800L);
                }

                openModuleOnce(driver, moduleName);
                return;

            } catch (Exception error) {
                lastError = error;

                if (searchAttempt < 3) {
                    System.out.println("MODULE CARD NOT READY YET - reloading dashboard: " + moduleName);
                    try {
                        driver.get(DASHBOARD_URL);
                    } catch (Exception ignored) {
                    }
                    Thread.sleep(1500L);
                }
            }
        }

        if (lastError != null) {
            throw lastError;
        }

        throw new NoSuchElementException(
                "Module not found after dashboard retries: " + moduleName
        );
    }

    private static void openModuleOnce(
            WebDriver driver,
            String moduleName
    ) throws Exception {

        System.out.println();
        System.out.println(
                "Searching Module : "
                        + moduleName
        );

        WebElement playButton =
                null;

        int modulePage =
                -1;

        /*
         * IMPORTANT: search the page that is already visible BEFORE touching
         * pagination. On the live site the current page-number button can be
         * temporarily missing while React is rendering. The old code treated
         * that as "page cannot be checked" and skipped a course that was
         * already visible on screen.
         */
        waitForCourseCards(driver);
        playButton = findModulePlayButton(driver, moduleName);

        if (playButton != null) {
            modulePage = detectSelectedDashboardPage(driver);
            if (modulePage <= 0) {
                modulePage = 1;
            }
            System.out.println(
                    "Module found on currently visible dashboard page"
            );
        }

        /*
         * Only use pagination if the module was not on the current page.
         * This keeps the strict one-course-at-a-time flow and avoids false
         * NoSuchElementException errors when page button 1 is not rendered.
         */
        if (playButton == null) {
            for (
                    int pageNumber = 1;
                    pageNumber <= MAX_DASHBOARD_PAGES;
                    pageNumber++
            ) {

                System.out.println(
                        "Checking dashboard page : "
                                + pageNumber
                );

                boolean pageOpened =
                        openDashboardPage(
                                driver,
                                pageNumber
                        );

                if (!pageOpened) {

                    System.out.println(
                            "Could not switch to dashboard page : "
                                    + pageNumber
                    );

                    continue;
                }

                waitForCourseCards(driver);

                playButton =
                        findModulePlayButton(
                                driver,
                                moduleName
                        );

                if (playButton != null) {

                    modulePage =
                            pageNumber;

                    break;
                }

                System.out.println(
                        moduleName
                                + " not found on page "
                                + pageNumber
                );
            }
        }

        if (playButton == null) {

            throw new NoSuchElementException(
                    "Module not found on dashboard pages: "
                            + moduleName
            );
        }

        System.out.println(
                "Module found on dashboard page : "
                        + modulePage
        );

        WebDriverWait wait =
                new WebDriverWait(
                        driver,
                        Duration.ofSeconds(40)
                );

        JavascriptExecutor js =
                (JavascriptExecutor) driver;

        js.executeScript(
                "arguments[0].scrollIntoView("
                        + "{block:'center',inline:'center'}"
                        + ");",
                playButton
        );

        Thread.sleep(
                800
        );

        try {

            wait.until(
                    ExpectedConditions
                            .elementToBeClickable(
                                    playButton
                            )
            ).click();

        } catch (Exception normalClickError) {

            playButton =
                    findModulePlayButton(
                            driver,
                            moduleName
                    );

            if (playButton == null) {

                throw new NoSuchElementException(
                        "Module play button disappeared: "
                                + moduleName
                );
            }

            js.executeScript(
                    "arguments[0].click();",
                    playButton
            );
        }

        System.out.println(
                "Play Button Clicked : "
                        + moduleName
        );

        wait.until(
                ExpectedConditions
                        .presenceOfElementLocated(
                                By.xpath(
                                        "//div[@data-slot='accordion-item']"
                                )
                        )
        );

        System.out.println(
                moduleName
                        + " Opened"
        );

        Thread.sleep(
                1500
        );
    }

    private static void waitForCourseCards(
            WebDriver driver
    ) {
        try {
            new WebDriverWait(
                    driver,
                    Duration.ofSeconds(12)
            ).until(
                    webDriver -> !webDriver.findElements(
                            By.xpath(
                                    "//h2[normalize-space()='All Courses']"
                                            + "/following::h3"
                            )
                    ).isEmpty()
            );
        } catch (Exception ignored) {
            // findModulePlayButton() below still gets a chance to inspect DOM.
        }
    }

    private static int detectSelectedDashboardPage(
            WebDriver driver
    ) {
        try {
            for (int pageNumber = 1; pageNumber <= MAX_DASHBOARD_PAGES; pageNumber++) {
                String pageXpath =
                        "//button[normalize-space()='" + pageNumber + "']";

                for (WebElement button : driver.findElements(By.xpath(pageXpath))) {
                    try {
                        String buttonClass = button.getAttribute("class");
                        if (button.isDisplayed()
                                && buttonClass != null
                                && buttonClass.contains("bg-[var(--primary-font)]")) {
                            return pageNumber;
                        }
                    } catch (StaleElementReferenceException ignored) {
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return -1;
    }

    /*
     * Opens dashboard page 1 or 2.
     */
    private static boolean openDashboardPage(
            WebDriver driver,
            int pageNumber
    ) {

        try {

            WebDriverWait wait =
                    new WebDriverWait(
                            driver,
                            Duration.ofSeconds(20)
                    );

            JavascriptExecutor js =
                    (JavascriptExecutor) driver;

            wait.until(
                    ExpectedConditions
                            .presenceOfElementLocated(
                                    By.xpath(
                                            "//h2[normalize-space()='All Courses']"
                                    )
                            )
            );

            js.executeScript(
                    "window.scrollTo("
                            + "0,"
                            + "document.body.scrollHeight"
                            + ");"
            );

            Thread.sleep(
                    1000
            );

            String pageXpath =
                    "//button[normalize-space()='"
                            + pageNumber
                            + "']";

            List<WebElement> buttons =
                    driver.findElements(
                            By.xpath(
                                    pageXpath
                            )
                    );

            WebElement pageButton =
                    null;

            for (WebElement button : buttons) {

                try {

                    if (
                            button.isDisplayed()
                                    && button.isEnabled()
                    ) {

                        pageButton =
                                button;

                        break;
                    }

                } catch (
                        StaleElementReferenceException ignored
                ) {
                }
            }

            if (pageButton == null) {

                System.out.println(
                        "Page button not found: "
                                + pageNumber
                );

                return false;
            }

            String buttonClass =
                    pageButton.getAttribute(
                            "class"
                    );

            boolean alreadySelected =
                    buttonClass != null
                            && buttonClass.contains(
                            "bg-[var(--primary-font)]"
                    );

            if (alreadySelected) {

                System.out.println(
                        "Dashboard Page "
                                + pageNumber
                                + " already selected"
                );

                return true;
            }

            WebElement oldCourseHeading =
                    findFirstVisibleCourseHeading(
                            driver
                    );

            js.executeScript(
                    "arguments[0].scrollIntoView("
                            + "{block:'center'}"
                            + ");",
                    pageButton
            );

            Thread.sleep(
                    500
            );

            js.executeScript(
                    "arguments[0].click();",
                    pageButton
            );

            try {

                wait.until(
                        webDriver -> {

                            List<WebElement> currentButtons =
                                    webDriver.findElements(
                                            By.xpath(
                                                    pageXpath
                                            )
                                    );

                            for (
                                    WebElement currentButton
                                    : currentButtons
                            ) {

                                try {

                                    String currentClass =
                                            currentButton
                                                    .getAttribute(
                                                            "class"
                                                    );

                                    if (
                                            currentButton.isDisplayed()
                                                    && currentClass != null
                                                    && currentClass.contains(
                                                    "bg-[var(--primary-font)]"
                                            )
                                    ) {

                                        return true;
                                    }

                                } catch (
                                        StaleElementReferenceException ignored
                                ) {
                                }
                            }

                            return false;
                        }
                );

            } catch (TimeoutException ignored) {
            }

            if (oldCourseHeading != null) {

                try {

                    new WebDriverWait(
                            driver,
                            Duration.ofSeconds(8)
                    ).until(
                            ExpectedConditions
                                    .stalenessOf(
                                            oldCourseHeading
                                    )
                    );

                } catch (TimeoutException ignored) {
                }
            }

            Thread.sleep(
                    1800
            );

            System.out.println(
                    "Dashboard Page "
                            + pageNumber
                            + " Opened"
            );

            return true;

        } catch (Exception pageError) {

            System.out.println(
                    "Could not open dashboard page "
                            + pageNumber
                            + " : "
                            + pageError
                            .getClass()
                            .getSimpleName()
                            + " - "
                            + pageError
                            .getMessage()
            );

            return false;
        }
    }

    private static WebElement findFirstVisibleCourseHeading(
            WebDriver driver
    ) {

        try {

            List<WebElement> headings =
                    driver.findElements(
                            By.xpath(
                                    "//h2[normalize-space()='All Courses']"
                                            + "/following::h3"
                            )
                    );

            for (WebElement heading : headings) {

                try {

                    if (heading.isDisplayed()) {

                        return heading;
                    }

                } catch (
                        StaleElementReferenceException ignored
                ) {
                }
            }

        } catch (Exception ignored) {
        }

        return null;
    }

    private static WebElement findModulePlayButton(
            WebDriver driver,
            String moduleName
    ) {

        String exactHeading =
                "//h3[normalize-space()="
                        + xpathLiteral(moduleName)
                        + "]";

        String primaryXpath =
                exactHeading
                        + "/ancestor::div["
                        + "contains(@class,'rounded-3xl')"
                        + "][1]//button";

        String fallbackXpath =
                exactHeading
                        + "/ancestor::div[.//button][1]//button";

        /*
         * React can render the All Courses heading first and the individual
         * cards a moment later. Retry the requested card itself instead of
         * immediately declaring the module missing.
         */
        for (int attempt = 1; attempt <= 10; attempt++) {
            WebElement button = firstVisibleEnabledButton(driver, primaryXpath);

            if (button == null) {
                button = firstVisibleEnabledButton(driver, fallbackXpath);
            }

            if (button != null) {
                return button;
            }

            try {
                Thread.sleep(350);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return null;
            }
        }

        return null;
    }

    private static WebElement firstVisibleEnabledButton(
            WebDriver driver,
            String xpath
    ) {
        try {
            List<WebElement> buttons = driver.findElements(By.xpath(xpath));

            for (WebElement button : buttons) {
                try {
                    if (button.isDisplayed() && button.isEnabled()) {
                        return button;
                    }
                } catch (StaleElementReferenceException ignored) {
                }
            }
        } catch (Exception ignored) {
        }

        return null;
    }

    private static List<QuizPlanRow> rowsFor(
            List<QuizPlanRow> allRows,
            int moduleNumber,
            int chapterNumber
    ) {

        List<QuizPlanRow> matchingRows =
                new ArrayList<>();

        for (QuizPlanRow row : allRows) {

            if (
                    row.moduleNumber() == moduleNumber
                            && row.chapterNumber()
                            == chapterNumber
            ) {

                matchingRows.add(
                        row
                );
            }
        }

        return matchingRows;
    }

    private static int findResumeChapter(
            String savedProgress,
            int totalChapters
    ) {

        if (
                savedProgress == null
                        || savedProgress.isBlank()
        ) {

            return 1;
        }

        String cleaned =
                savedProgress.trim();

        if (
                cleaned.equalsIgnoreCase("Pending")
                        || cleaned.equalsIgnoreCase("Running")
                        || cleaned.equalsIgnoreCase("Error")
        ) {

            return 1;
        }

        Pattern pattern =
                Pattern.compile(
                        "(?i)part\\s*:?\\s*(\\d+)"
                );

        Matcher matcher =
                pattern.matcher(cleaned);

        if (!matcher.find()) {
            return 1;
        }

        try {

            int savedPart =
                    Integer.parseInt(
                            matcher.group(1)
                    );

            if (savedPart < 1) {
                return 1;
            }

            return Math.min(
                    savedPart,
                    totalChapters
            );

        } catch (NumberFormatException ignored) {

            return 1;
        }
    }

    /*
     * Reads progress such as:
     *
     * Running Part 1/8 Sub-part 3/4
     *
     * Part is the chapter position and Sub-part is the
     * material/Watch Video button position inside it.
     */
    private static int findResumeSubPart(
            String savedProgress,
            int currentChapter,
            int totalSubParts
    ) {

        if (savedProgress == null
                || savedProgress.isBlank()) {

            return 1;
        }

        Matcher chapterMatcher =
                Pattern.compile(
                        "(?i)part\\s*:?\\s*(\\d+)"
                ).matcher(savedProgress);

        if (!chapterMatcher.find()) {
            return 1;
        }

        int savedChapter;

        try {

            savedChapter =
                    Integer.parseInt(
                            chapterMatcher.group(1)
                    );

        } catch (NumberFormatException ignored) {

            return 1;
        }

        if (savedChapter != currentChapter) {
            return 1;
        }

        Matcher subPartMatcher =
                Pattern.compile(
                        "(?i)sub[-\\s]*part\\s*:?\\s*(\\d+)"
                ).matcher(savedProgress);

        if (!subPartMatcher.find()) {
            return 1;
        }

        try {

            int savedSubPart =
                    Integer.parseInt(
                            subPartMatcher.group(1)
                    );

            if (savedSubPart < 1) {
                return 1;
            }

            return Math.min(
                    savedSubPart,
                    totalSubParts
            );

        } catch (NumberFormatException ignored) {

            return 1;
        }
    }

    private static String progressText(
            String prefix,
            int currentPart,
            int totalParts
    ) {

        String progress =
                "Part "
                        + currentPart
                        + "/"
                        + totalParts;

        if (
                prefix == null
                        || prefix.isBlank()
        ) {

            return progress;
        }

        return prefix.trim()
                + " "
                + progress;
    }

    private static boolean isNetworkLikeError(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            String className = current.getClass().getSimpleName().toLowerCase();
            String message = current.getMessage() == null
                    ? ""
                    : current.getMessage().toLowerCase();

            // A Selenium TimeoutException usually means an element/page step was
            // slow. Treat it as a normal technical retry, not a network failure.
            if (className.contains("sockettimeout")
                    || className.contains("unknownhost")
                    || className.contains("connectexception")
                    || message.contains("net::")
                    || message.contains("err_connection")
                    || message.contains("err_internet")
                    || message.contains("err_name_not_resolved")
                    || message.contains("err_network_changed")
                    || message.contains("connection reset")
                    || message.contains("connection refused")
                    || message.contains("connection timed out")
                    || message.contains("connect timed out")
                    || message.contains("read timed out")
                    || message.contains("no route to host")
                    || message.contains("host is unreachable")
                    || message.contains("network is unreachable")
                    || message.contains("failed to establish")) {
                return true;
            }
        }
        return false;
    }

    private static boolean isLowMemoryError(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            String message = current.getMessage() == null
                    ? ""
                    : current.getMessage().toLowerCase();

            if (message.contains("low system memory")
                    || message.contains("out of memory")
                    || message.contains("cannot allocate memory")
                    || message.contains("native memory")) {
                return true;
            }
        }
        return false;
    }

    private static String conciseMessage(Throwable error) {
        if (error == null) {
            return "Unknown";
        }

        String message = error.getMessage();
        if (message == null || message.isBlank()) {
            return error.getClass().getSimpleName();
        }

        String oneLine = message.replaceAll("\\s+", " ").trim();
        if (oneLine.length() > 220) {
            oneLine = oneLine.substring(0, 220) + "...";
        }
        return error.getClass().getSimpleName() + " - " + oneLine;
    }

    /**
     * IMPORTANT: all workers must pass through this lock before launching Chrome.
     * Without it, multiple workers can see the same free-RAM value at the
     * same instant and launch Chrome together, exhausting native Windows
     * memory before any individual worker notices the drop.
     */
    private static WebDriver startChromeWithMemoryGuard(ChromeOptions options) throws Exception {
        synchronized (CHROME_START_LOCK) {
            ensureEnoughSystemMemory();

            WebDriver newDriver = startChromeSafely(options);

            // Give Windows/Chrome a moment to commit its native memory before
            // allowing the next worker to perform a fresh RAM check.
            Thread.sleep(CHROME_START_SETTLE_MS);

            long freeMb = readFreePhysicalMemoryMb();
            if (freeMb >= 0) {
                System.out.println("SYSTEM FREE MEMORY AFTER CHROME START: " + freeMb + " MB");
            }

            if (freeMb >= 0 && freeMb < POST_CHROME_MIN_FREE_MEMORY_MB) {
                try {
                    newDriver.quit();
                } catch (Exception ignored) {
                }

                System.gc();
                throw new IllegalStateException(
                        "LOW SYSTEM MEMORY AFTER CHROME START - only "
                                + freeMb + " MB free. Browser closed to protect the JVM."
                );
            }

            return newDriver;
        }
    }

    private static WebDriver startChromeSafely(ChromeOptions options) throws Exception {
        Exception lastError = null;

        for (int attempt = 1; attempt <= CHROME_START_ATTEMPTS; attempt++) {
            try {
                System.out.println("CHROME START: attempt " + attempt + "/"
                        + CHROME_START_ATTEMPTS + " (HEADLESS)");
                WebDriver driver = new ChromeDriver(options);
                System.out.println("CHROME START: success - browser remains hidden");
                return driver;
            } catch (Exception error) {
                lastError = error;
                System.out.println("CHROME START PAUSED: " + conciseMessage(error));

                System.gc();
                if (attempt < CHROME_START_ATTEMPTS) {
                    System.out.println("Waiting " + (CHROME_START_RETRY_MS / 1000)
                            + " seconds before one final Chrome start attempt...");
                    Thread.sleep(CHROME_START_RETRY_MS);
                }
            }
        }

        throw lastError;
    }

    private static void ensureEnoughSystemMemory() throws Exception {
        for (int attempt = 1; attempt <= MEMORY_CHECK_ATTEMPTS; attempt++) {
            long freeMb = readFreePhysicalMemoryMb();

            if (freeMb < 0) {
                // If the JVM cannot report Windows memory, do not block the run.
                return;
            }

            System.out.println("SYSTEM FREE MEMORY: " + freeMb + " MB");

            if (freeMb >= MIN_FREE_MEMORY_MB) {
                return;
            }

            System.out.println(
                    "LOW MEMORY: Chrome not started yet. Need at least "
                            + MIN_FREE_MEMORY_MB
                            + " MB free; waiting for Windows to release memory."
            );

            System.gc();

            if (attempt < MEMORY_CHECK_ATTEMPTS) {
                Thread.sleep(MEMORY_CHECK_DELAY_MS);
            }
        }

        long freeMb = readFreePhysicalMemoryMb();
        throw new IllegalStateException(
                "LOW SYSTEM MEMORY - only "
                        + (freeMb < 0 ? "unknown" : freeMb + " MB")
                        + " free. Headless Chrome was NOT started, so the JVM is protected from crashing."
        );
    }

    private static long readFreePhysicalMemoryMb() {
        try {
            java.lang.management.OperatingSystemMXBean baseBean =
                    ManagementFactory.getOperatingSystemMXBean();

            if (baseBean instanceof com.sun.management.OperatingSystemMXBean osBean) {
                return osBean.getFreeMemorySize() / (1024L * 1024L);
            }
        } catch (Throwable ignored) {
        }

        return -1L;
    }

    private static String normalizeModuleName(
            String value
    ) {

        if (value == null) {
            return "";
        }

        return value
                .replace(
                        "\u00A0",
                        " "
                )
                .replace(
                        ",",
                        ""
                )
                .replaceAll(
                        "\\s+",
                        " "
                )
                .trim();
    }

    private static String xpathLiteral(
            String value
    ) {

        if (!value.contains("'")) {

            return "'"
                    + value
                    + "'";
        }

        if (!value.contains("\"")) {

            return "\""
                    + value
                    + "\"";
        }

        return "concat('"
                + value.replace(
                "'",
                "',\"'\",'"
        )
                + "')";
    }
}
