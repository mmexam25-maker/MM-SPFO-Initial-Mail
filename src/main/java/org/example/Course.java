package org.example;

import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.NoSuchElementException;
import org.openqa.selenium.StaleElementReferenceException;
import org.openqa.selenium.TimeoutException;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Course {

    private static final int WAIT_SECONDS = 40;

    /*
     * Actual HTML5 video playback speed.
     *
     * 1.0 = normal
     * 2.0 = 2x
     * 4.0 = 4x
     */
    private static final double VIDEO_SPEED = 4.0;


    /*
     * ============================================================
     * COMPLETE ONE CHAPTER / COURSE CARD
     * ============================================================
     *
     * IMPORTANT RULE:
     *
     * Website green "Completed" = final truth.
     *
     * Example:
     *
     * 7. 1.3 Navigating Anger...
     *                  Completed   4 Materials
     *
     * In that case this method RETURNS immediately.
     *
     * It will NOT:
     * - open the chapter
     * - replay video
     * - redo quiz
     * - restart sub-part 1/4
     *
     * The Sheet resume point is considered only if the
     * website does NOT show the whole chapter as Completed.
     */
    public static void completeChapter(
            WebDriver driver,
            int moduleNumber,
            int chapterNumber,
            List<QuizPlanRow> parts,
            int startSubPart,
            int sheetRowNumber,
            String moduleName,
            int totalChapters
    ) throws Exception {

        System.out.println();
        System.out.println("====================================");
        System.out.println("CHECKING CHAPTER " + chapterNumber);
        System.out.println("MODULE NUMBER   : " + moduleNumber);
        System.out.println("TOTAL PARTS     : " + parts.size());
        System.out.println("SHEET START     : " + startSubPart);
        System.out.println("====================================");

        /*
         * Always return to the page containing
         * the chapter/course cards first.
         */
        returnToCoursePage(driver);


        /*
         * ========================================================
         * NEW IMPORTANT CHECK
         * ========================================================
         *
         * Check the actual website before looking at the
         * Sheet resume information.
         */
        if (isChapterCompleted(
                driver,
                chapterNumber
        )) {

            System.out.println();
            System.out.println("====================================");
            System.out.println(
                    "CHAPTER "
                            + chapterNumber
                            + " IS GREEN COMPLETED"
            );
            System.out.println(
                    "SKIPPING ALL SUB-PARTS"
            );
            System.out.println("====================================");
            System.out.println();

            return;
        }


        System.out.println(
                "Chapter "
                        + chapterNumber
                        + " is NOT completed on website."
        );

        System.out.println(
                "Resume sub-part from Sheet: "
                        + startSubPart
        );


        /*
         * ========================================================
         * PROCESS SUB-PARTS
         * ========================================================
         */
        for (int partIndex = 0;
             partIndex < parts.size();
             partIndex++) {

            QuizPlanRow part =
                    parts.get(partIndex);

            int lessonPosition =
                    part.partNumber();


            if (lessonPosition <= 0) {

                throw new IllegalArgumentException(
                        "Invalid part code in Sheet2: "
                                + part.partCode()
                );
            }


            /*
             * Sheet resume logic.
             *
             * Website Completed check has already happened.
             * Now we can use the Sheet to resume an unfinished
             * chapter.
             */
            if (lessonPosition < startSubPart) {

                System.out.println(
                        "Skipping previous Sheet sub-part: "
                                + lessonPosition
                );

                continue;
            }


            boolean lastPart =
                    partIndex == parts.size() - 1;


            System.out.println();
            System.out.println("------------------------------------");
            System.out.println(
                    "PROCESSING PART : "
                            + part.partCode()
            );
            System.out.println(
                    "POSITION        : "
                            + lessonPosition
            );
            System.out.println(
                    "ANSWERS         : "
                            + part.answers()
            );
            System.out.println("------------------------------------");


            /*
             * Save the current resume point.
             */
            SheetRepository.updateModuleStatus(
                    sheetRowNumber,
                    moduleName,
                    subPartProgressText(
                            chapterNumber,
                            totalChapters,
                            lessonPosition,
                            parts.size()
                    )
            );


            /*
             * Before EVERY material, return to course page.
             */
            returnToCoursePage(driver);


            /*
             * ====================================================
             * CHECK AGAIN
             * ====================================================
             *
             * The previous material may have caused the whole
             * chapter to become Completed.
             *
             * If so, stop immediately.
             */
            if (isChapterCompleted(
                    driver,
                    chapterNumber
            )) {

                System.out.println();
                System.out.println(
                        "Chapter "
                                + chapterNumber
                                + " is now GREEN COMPLETED."
                );

                System.out.println(
                        "Stopping remaining sub-parts."
                );

                return;
            }


            /*
             * Open the chapter accordion.
             */
            openChapter(
                    driver,
                    chapterNumber
            );


            /*
             * Open the required material based on its position.
             */
            openLessonByPosition(
                    driver,
                    chapterNumber,
                    lessonPosition
            );


            /*
             * Wait for lesson/video/quiz page.
             */
            waitForCurrentMaterial(
                    driver
            );


            /*
             * Some pages contain another Watch Video button.
             */
            clickWatchVideoIfDisplayed(
                    driver
            );


            /*
             * ====================================================
             * VIDEO
             * ====================================================
             */
            if (waitForVideoIfAvailable(
                    driver,
                    12
            )) {

                playCurrentVideo(
                        driver
                );

                System.out.println(
                        "Video Completed for Part "
                                + part.partCode()
                );

            } else {

                System.out.println(
                        "No video found for Part "
                                + part.partCode()
                );
            }


            /*
             * Wait for quiz or next state.
             */
            waitForQuizOrCompletion(
                    driver,
                    30
            );


            /*
             * ====================================================
             * QUIZ
             * ====================================================
             */
            System.out.println(
                    "Checking quiz with Sheet2 answers: "
                            + part.answers()
            );


            Quiz.completeCurrentQuiz(
                    driver,
                    part.answers()
            );


            /*
             * Return to course/chapter list.
             */
            returnToCoursePage(
                    driver
            );


            /*
             * ====================================================
             * WEBSITE COMPLETED CHECK AFTER VIDEO + QUIZ
             * ====================================================
             */
            if (isChapterCompleted(
                    driver,
                    chapterNumber
            )) {

                System.out.println();
                System.out.println("====================================");

                System.out.println(
                        "WEBSITE NOW SHOWS CHAPTER "
                                + chapterNumber
                                + " AS COMPLETED"
                );

                System.out.println(
                        "NO MORE SUB-PARTS WILL BE OPENED"
                );

                System.out.println("====================================");

                return;
            }


            /*
             * ====================================================
             * SAVE NEXT SUB-PART
             * ====================================================
             */
            if (lastPart) {

                System.out.println();
                System.out.println(
                        "LAST PART FINISHED FOR CHAPTER "
                                + chapterNumber
                );

            } else {

                int nextSubPart =
                        parts.get(partIndex + 1)
                                .partNumber();


                /*
                 * Do not write the next sub-part here. The next loop iteration
                 * writes that exact status immediately before opening it.
                 * Avoiding this duplicate write cuts the Google Sheets request
                 * count roughly in half without changing playback order.
                 */
                System.out.println(
                        "Sub-part completed. Next position: "
                                + nextSubPart
                );
            }
        }


        /*
         * Processing every planned material is not enough to declare success.
         * The website badge is the final truth. If it is still Pending after
         * the last material, throw so ChapterRunner keeps this module pending
         * instead of writing Completed to the Sheet.
         */
        returnToCoursePage(driver);

        if (!isChapterCompleted(driver, chapterNumber)) {
            throw new IllegalStateException(
                    "Chapter " + chapterNumber
                            + " is still Pending on the website after all "
                            + parts.size() + " materials were processed."
            );
        }


        System.out.println();
        System.out.println("====================================");
        System.out.println(
                "CHAPTER "
                        + chapterNumber
                        + " PROCESSING FINISHED"
        );
        System.out.println("====================================");
    }


    /*
     * ============================================================
     * NEW METHOD
     * CHECK WEBSITE GREEN COMPLETED STATUS
     * ============================================================
     *
     * This checks only inside the requested chapter/course card.
     *
     * It does NOT check the whole page for the word Completed.
     * Therefore Completed belonging to Chapter 7 cannot cause
     * Chapter 6 to be skipped.
     */
    public static boolean isChapterCompleted(
            WebDriver driver,
            int chapterNumber
    ) {

        try {
            WebElement chapter = getChapter(driver, chapterNumber);
            WebElement trigger = findChapterTrigger(chapter);

            /*
             * STRICT RULE:
             * Only the chapter's OWN top-level header/trigger may prove that
             * the chapter is complete. Never scan the expanded chapter card
             * for Completed, because child material rows can be Completed
             * while the chapter header is still Pending.
             */
            if (hasExactStatusInElement(trigger, "Pending")) {
                return false;
            }

            if (hasExactStatusInElement(trigger, "Completed")) {
                return true;
            }

            // Unknown/missing status must never be treated as completed.
            return false;

        } catch (Exception e) {
            // Fail closed. Selenium/markup uncertainty means Pending, never
            // Completed. The caller can retry the chapter safely.
            System.out.println(
                    "CHAPTER STATUS UNCLEAR | Chapter "
                            + chapterNumber
                            + " | treating as Pending"
            );
            return false;
        }
    }

    /*
     * Strict module-level verification.
     *
     * RULES:
     * 1. Verify the chapter HEADER badge, not material rows inside an expanded
     *    accordion.
     * 2. One Pending chapter makes the whole module Pending.
     * 3. Every chapter card must have its own exact Completed badge.
     * 4. Never accept fewer website cards than the quiz plan expects.
     */
    public static boolean areAllVisibleChaptersCompleted(
            WebDriver driver,
            int expectedMinimumChapters
    ) throws Exception {

        returnToCoursePage(driver);

        // Give React a moment to refresh status badges after the last quiz.
        sleepQuietly(900);

        List<WebElement> chapters =
                getChapterCardsForTarget(driver, expectedMinimumChapters);

        if (chapters.isEmpty()) {
            System.out.println("MODULE VERIFY FAILED: No chapter cards found.");
            return false;
        }

        LinkedHashMap<Integer, WebElement> bySerial = new LinkedHashMap<>();

        for (WebElement chapter : chapters) {
            try {
                WebElement trigger = findChapterTrigger(chapter);
                int serial = parseChapterSerial(safeElementText(trigger));
                if (serial > 0) {
                    bySerial.putIfAbsent(serial, chapter);
                }
            } catch (Exception ignored) {
            }
        }

        if (expectedMinimumChapters > 0) {
            if (!bySerial.isEmpty()) {
                for (int serial = 1; serial <= expectedMinimumChapters; serial++) {
                    if (!bySerial.containsKey(serial)) {
                        System.out.println(
                                "MODULE VERIFY FAILED: Visible chapter serial "
                                        + serial
                                        + " is missing. Found serials: "
                                        + bySerial.keySet()
                        );
                        return false;
                    }
                }
            } else if (chapters.size() < expectedMinimumChapters) {
                System.out.println(
                        "MODULE VERIFY FAILED: Website cards "
                                + chapters.size()
                                + " < expected "
                                + expectedMinimumChapters
                );
                return false;
            }
        }

        int completedCount = 0;
        Set<Integer> seenSerials = new HashSet<>();

        for (int index = 0; index < chapters.size(); index++) {
            WebElement chapter = chapters.get(index);
            WebElement trigger;
            String headerText;

            try {
                trigger = findChapterTrigger(chapter);
                headerText = safeElementText(trigger);
            } catch (Exception e) {
                System.out.println(
                        "MODULE VERIFY FAILED: chapter header trigger not found at card "
                                + (index + 1)
                );
                return false;
            }

            int serial = parseChapterSerial(headerText);
            if (serial > 0 && !seenSerials.add(serial)) {
                // Duplicate rendered card: ignore the duplicate, not the unique chapter.
                continue;
            }

            String label = serial > 0
                    ? "Chapter " + serial
                    : "Card " + (index + 1);

            boolean pending = hasExactStatusInElement(trigger, "Pending");

            if (pending) {
                System.out.println(
                        "MODULE VERIFY PENDING: "
                                + label
                                + " -> "
                                + oneLine(headerText)
                );
                return false;
            }

            boolean completed = hasExactStatusInElement(trigger, "Completed");

            if (!completed) {
                System.out.println(
                        "MODULE VERIFY NOT COMPLETED: "
                                + label
                                + " -> "
                                + oneLine(headerText)
                );
                return false;
            }

            System.out.println(
                    "MODULE VERIFY COMPLETED: "
                            + label
                            + " -> "
                            + oneLine(headerText)
            );

            completedCount++;
        }

        int uniqueChapterCount = !bySerial.isEmpty()
                ? bySerial.size()
                : chapters.size();

        if (expectedMinimumChapters > 0
                && uniqueChapterCount < expectedMinimumChapters) {
            System.out.println(
                    "MODULE VERIFY FAILED: Unique chapter count "
                            + uniqueChapterCount
                            + " < expected "
                            + expectedMinimumChapters
            );
            return false;
        }

        System.out.println(
                "MODULE WEBSITE VERIFIED: "
                        + completedCount
                        + "/"
                        + uniqueChapterCount
                        + " unique chapter cards are green Completed."
        );

        return completedCount == uniqueChapterCount;
    }

    // Backward-compatible overload for any older caller.
    public static boolean areAllVisibleChaptersCompleted(
            WebDriver driver
    ) throws Exception {
        return areAllVisibleChaptersCompleted(driver, 0);
    }

    private static boolean containsExactLine(
            String text,
            String expected
    ) {
        if (text == null || expected == null) {
            return false;
        }

        for (String line : text.split("\\R")) {
            if (expected.equalsIgnoreCase(line.trim())) {
                return true;
            }
        }

        return false;
    }

    private static boolean hasExactStatusInElement(
            WebElement root,
            String expected
    ) {
        if (root == null || expected == null) {
            return false;
        }

        if (containsExactLine(safeElementText(root), expected)) {
            return true;
        }

        try {
            List<WebElement> descendants = root.findElements(By.xpath(".//*"));

            for (WebElement element : descendants) {
                try {
                    if (element.isDisplayed()
                            && expected.equalsIgnoreCase(
                            safeElementText(element).trim()
                    )) {
                        return true;
                    }
                } catch (StaleElementReferenceException ignored) {
                }
            }
        } catch (Exception ignored) {
        }

        return false;
    }

    private static String safeElementText(WebElement element) {
        if (element == null) {
            return "";
        }

        try {
            String text = element.getText();
            return text == null ? "" : text;
        } catch (StaleElementReferenceException e) {
            return "";
        }
    }

    private static String oneLine(String text) {
        if (text == null || text.isBlank()) {
            return "Unknown chapter";
        }
        return text.replaceAll("\\s+", " ").trim();
    }


    /*
     * ============================================================
     * PROGRESS TEXT
     * ============================================================
     */
    private static String subPartProgressText(
            int chapterNumber,
            int totalChapters,
            int subPartNumber,
            int totalSubParts
    ) {

        return "Running Part "
                + chapterNumber
                + "/"
                + totalChapters
                + " Sub-part "
                + subPartNumber
                + "/"
                + totalSubParts;
    }


    /*
     * ============================================================
     * OPEN CHAPTER
     * ============================================================
     */
    private static void openChapter(
            WebDriver driver,
            int chapterNumber
    ) throws Exception {

        WebElement chapter =
                getChapter(
                        driver,
                        chapterNumber
                );


        WebElement trigger =
                findChapterTrigger(
                        chapter
                );


        if (!isChapterOpen(trigger)) {

            safeClick(
                    driver,
                    trigger
            );
        }


        Thread.sleep(
                1200
        );


        System.out.println(
                "Chapter "
                        + chapterNumber
                        + " Opened"
        );
    }


    /*
     * ============================================================
     * OPEN MATERIAL BY POSITION
     * ============================================================
     */
    private static void openLessonByPosition(
            WebDriver driver,
            int chapterNumber,
            int lessonPosition
    ) throws Exception {

        WebDriverWait wait =
                new WebDriverWait(
                        driver,
                        Duration.ofSeconds(WAIT_SECONDS)
                );


        wait.until(driver1 -> {

            try {

                return getWatchButtonsForChapter(
                        driver1,
                        chapterNumber
                ).size() >= lessonPosition;

            } catch (Exception ignored) {

                return false;
            }
        });


        List<WebElement> buttons =
                getWatchButtonsForChapter(
                        driver,
                        chapterNumber
                );


        if (lessonPosition < 1
                || lessonPosition > buttons.size()) {

            throw new NoSuchElementException(
                    "Part "
                            + lessonPosition
                            + " not available in Chapter "
                            + chapterNumber
                            + ". Watch buttons found: "
                            + buttons.size()
            );
        }


        WebElement lessonButton =
                buttons.get(
                        lessonPosition - 1
                );


        safeClick(
                driver,
                lessonButton
        );


        System.out.println(
                "Watch Video Clicked for Part Position "
                        + lessonPosition
        );


        Thread.sleep(
                1500
        );
    }


    /*
     * ============================================================
     * RETURN TO COURSE PAGE
     * ============================================================
     */
    private static void returnToCoursePage(
            WebDriver driver
    ) throws Exception {

        if (hasChapterAccordions(driver)) {

            return;
        }


        /*
         * Try visible Back / Close / Course button.
         */
        List<WebElement> returnButtons =
                findReturnButtons(
                        driver
                );


        if (!returnButtons.isEmpty()) {

            safeClick(
                    driver,
                    returnButtons.get(0)
            );


            if (waitForChapterAccordions(
                    driver,
                    12
            )) {

                System.out.println(
                        "Returned to chapter page using page button."
                );

                return;
            }
        }


        /*
         * Browser Back fallback.
         */
        for (int attempt = 1;
             attempt <= 4;
             attempt++) {

            if (hasChapterAccordions(driver)) {

                return;
            }


            try {

                driver.navigate()
                        .back();

            } catch (Exception ignored) {
            }


            if (waitForChapterAccordions(
                    driver,
                    10
            )) {

                System.out.println(
                        "Returned to chapter page using browser Back."
                );

                return;
            }
        }


        throw new NoSuchElementException(
                "Could not return to module chapter page after lesson."
        );
    }


    /*
     * ============================================================
     * FIND RETURN BUTTONS
     * ============================================================
     */
    private static List<WebElement> findReturnButtons(
            WebDriver driver
    ) {

        return visibleElements(
                driver,
                By.xpath(
                        "//button[" +
                                "contains(" +
                                "translate(normalize-space(.)," +
                                "'ABCDEFGHIJKLMNOPQRSTUVWXYZ'," +
                                "'abcdefghijklmnopqrstuvwxyz')," +
                                "'back'" +
                                ")" +
                                " or contains(" +
                                "translate(normalize-space(.)," +
                                "'ABCDEFGHIJKLMNOPQRSTUVWXYZ'," +
                                "'abcdefghijklmnopqrstuvwxyz')," +
                                "'close'" +
                                ")" +
                                " or contains(" +
                                "translate(normalize-space(.)," +
                                "'ABCDEFGHIJKLMNOPQRSTUVWXYZ'," +
                                "'abcdefghijklmnopqrstuvwxyz')," +
                                "'course'" +
                                ")" +
                                " or @aria-label='Close'" +
                                "]"
                )
        );
    }


    /*
     * ============================================================
     * WAIT FOR CHAPTER PAGE
     * ============================================================
     */
    private static boolean waitForChapterAccordions(
            WebDriver driver,
            int seconds
    ) {

        try {

            WebDriverWait wait =
                    new WebDriverWait(
                            driver,
                            Duration.ofSeconds(seconds)
                    );


            return wait.until(
                    Course::hasChapterAccordions
            );

        } catch (TimeoutException e) {

            return false;
        }
    }


    /*
     * ============================================================
     * CHECK CHAPTER ACCORDIONS
     * ============================================================
     */
    private static boolean hasChapterAccordions(
            WebDriver driver
    ) {
        try {
            return !getChapterCards(driver).isEmpty();
        } catch (Exception ignored) {
            return false;
        }
    }


    /*
     * ============================================================
     * WAIT FOR CURRENT MATERIAL
     * ============================================================
     */
    private static void waitForCurrentMaterial(
            WebDriver driver
    ) {

        WebDriverWait wait =
                new WebDriverWait(
                        driver,
                        Duration.ofSeconds(WAIT_SECONDS)
                );


        wait.until(driver1 ->
                hasVideo(driver1)
                        || hasWatchVideoButton(driver1)
                        || hasStartQuizButton(driver1)
                        || hasSubmitButton(driver1)
                        || hasFinishButton(driver1)
        );


        sleepQuietly(
                800
        );
    }


    /*
     * ============================================================
     * INNER WATCH VIDEO
     * ============================================================
     */
    private static void clickWatchVideoIfDisplayed(
            WebDriver driver
    ) {

        if (hasVideo(driver)) {

            return;
        }


        List<WebElement> buttons =
                findVisibleWatchVideoButtons(
                        driver
                );


        if (buttons.isEmpty()) {

            return;
        }


        /*
         * Do not accidentally click Watch Video
         * from the chapter list.
         */
        if (hasChapterAccordions(driver)) {

            return;
        }


        safeClick(
                driver,
                buttons.get(0)
        );


        System.out.println(
                "Inner Watch Video Clicked"
        );


        sleepQuietly(
                1500
        );
    }


    /*
     * ============================================================
     * WAIT FOR VIDEO
     * ============================================================
     */
    private static boolean waitForVideoIfAvailable(
            WebDriver driver,
            int seconds
    ) {

        try {

            WebDriverWait wait =
                    new WebDriverWait(
                            driver,
                            Duration.ofSeconds(seconds)
                    );


            return wait.until(
                    Course::hasVideo
            );

        } catch (TimeoutException e) {

            return false;
        }
    }


    /*
     * ============================================================
     * WAIT FOR QUIZ / COMPLETION
     * ============================================================
     */
    private static void waitForQuizOrCompletion(
            WebDriver driver,
            int seconds
    ) {

        try {

            WebDriverWait wait =
                    new WebDriverWait(
                            driver,
                            Duration.ofSeconds(seconds)
                    );


            wait.until(driver1 ->
                    hasStartQuizButton(driver1)
                            || hasSubmitButton(driver1)
                            || hasFinishButton(driver1)
                            || hasChapterAccordions(driver1)
                            || !hasVideo(driver1)
            );

        } catch (TimeoutException e) {

            System.out.println(
                    "Quiz did not appear. Continuing without quiz."
            );
        }
    }


    /*
     * Return only the top-level chapter/course cards.
     * The chapter header contains the "N Materials" label seen on the SMY
     * page. Filtering by that header prevents nested material accordions from
     * being mistaken for chapter numbers when a card is expanded.
     */
    private static List<WebElement> getChapterCards(
            WebDriver driver
    ) {
        List<WebElement> all = driver.findElements(
                By.xpath("//*[@data-slot='accordion-item']")
        );

        /*
         * IMPORTANT: the SMY page can contain nested accordion items.
         * Only accept the real chapter header cards (the ones whose header
         * shows "N Materials"). Also de-duplicate by the visible leading
         * chapter serial: 1., 2., 3. ...
         *
         * This prevents a nested/duplicate accordion item from shifting the
         * positional index and making (for example) visible Chapter 7 Pending
         * get checked as some other Completed card.
         */
        LinkedHashMap<Integer, WebElement> numberedCards =
                new LinkedHashMap<>();
        List<WebElement> unnumberedCards = new ArrayList<>();

        for (WebElement item : all) {
            try {
                if (!item.isDisplayed()) {
                    continue;
                }

                WebElement trigger = findChapterTrigger(item);
                String headerText = safeElementText(trigger);

                if (!headerText.matches("(?is).*\\b\\d+\\s+Materials?\\b.*")) {
                    continue;
                }

                int serial = parseChapterSerial(headerText);

                if (serial > 0) {
                    numberedCards.putIfAbsent(serial, item);
                } else {
                    unnumberedCards.add(item);
                }
            } catch (Exception ignored) {
            }
        }

        if (!numberedCards.isEmpty()) {
            List<Integer> serials = new ArrayList<>(numberedCards.keySet());
            serials.sort(Integer::compareTo);

            List<WebElement> result = new ArrayList<>();
            for (Integer serial : serials) {
                result.add(numberedCards.get(serial));
            }
            return result;
        }

        if (!unnumberedCards.isEmpty()) {
            return unnumberedCards;
        }

        // Fail closed. If we cannot positively identify real top-level
        // chapter cards, return none. Falling back to every accordion item is
        // unsafe because nested material accordions may contain Completed.
        return new ArrayList<>();
    }

    /*
     * Some course pages render only the first group of chapter cards until the
     * scroll container reaches the bottom. When a later serial (for example
     * Physical Wellness chapter 12) is requested, gently scroll the chapter
     * container and re-read the DOM before deciding that the serial is missing.
     */
    private static List<WebElement> getChapterCardsForTarget(
            WebDriver driver,
            int targetChapter
    ) {
        List<WebElement> chapters = getChapterCards(driver);

        if (targetChapter <= 0) {
            return chapters;
        }

        for (int attempt = 1; attempt <= 6; attempt++) {
            if (containsChapterSerial(chapters, targetChapter)
                    || chapters.size() >= targetChapter) {
                return chapters;
            }

            if (chapters.isEmpty()) {
                sleepQuietly(500);
                chapters = getChapterCards(driver);
                continue;
            }

            try {
                WebElement last = chapters.get(chapters.size() - 1);
                JavascriptExecutor js = (JavascriptExecutor) driver;

                js.executeScript(
                        "arguments[0].scrollIntoView({block:'end'});"
                                + "let e=arguments[0],p=e.parentElement;"
                                + "while(p){"
                                + "let s=getComputedStyle(p);"
                                + "if((s.overflowY==='auto'||s.overflowY==='scroll')"
                                + "&&p.scrollHeight>p.clientHeight){"
                                + "p.scrollTop=p.scrollHeight;break;}"
                                + "p=p.parentElement;}"
                                + "window.scrollTo(0,document.body.scrollHeight);",
                        last
                );
            } catch (Exception ignored) {
            }

            sleepQuietly(650);
            chapters = getChapterCards(driver);
        }

        return chapters;
    }

    private static boolean containsChapterSerial(
            List<WebElement> chapters,
            int targetChapter
    ) {
        for (WebElement chapter : chapters) {
            try {
                WebElement trigger = findChapterTrigger(chapter);
                if (parseChapterSerial(safeElementText(trigger)) == targetChapter) {
                    return true;
                }
            } catch (Exception ignored) {
            }
        }

        return false;
    }

    private static int parseChapterSerial(String headerText) {
        if (headerText == null || headerText.isBlank()) {
            return -1;
        }

        Matcher matcher = Pattern.compile(
                "(?s)^\\s*(\\d+)\\s*\\."
        ).matcher(headerText);

        if (!matcher.find()) {
            return -1;
        }

        try {
            return Integer.parseInt(matcher.group(1));
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    /*
     * ============================================================
     * GET CHAPTER
     * ============================================================
     */
    private static WebElement getChapter(
            WebDriver driver,
            int chapterNumber
    ) {

        WebDriverWait wait =
                new WebDriverWait(
                        driver,
                        Duration.ofSeconds(WAIT_SECONDS)
                );


        wait.until(
                Course::hasChapterAccordions
        );


        List<WebElement> chapters =
                getChapterCardsForTarget(driver, chapterNumber);

        if (chapterNumber < 1) {
            throw new NoSuchElementException(
                    "Invalid chapter number: " + chapterNumber
            );
        }

        /*
         * Prefer the visible serial printed at the beginning of the chapter
         * header ("7. ...") instead of trusting DOM position.
         */
        boolean anySerialFound = false;

        for (WebElement chapter : chapters) {
            try {
                WebElement trigger = findChapterTrigger(chapter);
                int serial = parseChapterSerial(safeElementText(trigger));

                if (serial > 0) {
                    anySerialFound = true;
                }

                if (serial == chapterNumber) {
                    return chapter;
                }
            } catch (Exception ignored) {
            }
        }

        /*
         * If the live page exposes serial numbers but the requested serial is
         * missing, FAIL SAFE. Do not silently check the wrong positional card.
         */
        if (anySerialFound) {
            throw new NoSuchElementException(
                    "Visible chapter serial "
                            + chapterNumber
                            + " not found. Chapter cards detected: "
                            + chapters.size()
            );
        }

        // Older markup fallback only when no visible serial can be read at all.
        if (chapterNumber > chapters.size()) {
            throw new NoSuchElementException(
                    "Chapter "
                            + chapterNumber
                            + " not available. Total chapters: "
                            + chapters.size()
            );
        }

        return chapters.get(chapterNumber - 1);
    }


    /*
     * ============================================================
     * FIND CHAPTER TRIGGER
     * ============================================================
     */
    private static WebElement findChapterTrigger(
            WebElement chapter
    ) {

        List<WebElement> triggers =
                chapter.findElements(
                        By.xpath(".//*[@data-slot='accordion-trigger']")
                );

        /*
         * A chapter can contain nested material accordions. Select only a
         * trigger whose nearest accordion-item ancestor is THIS chapter.
         * This prevents a nested material's Completed badge from being used
         * as the chapter status.
         */
        for (WebElement trigger : triggers) {
            try {
                List<WebElement> owners = trigger.findElements(
                        By.xpath("ancestor::*[@data-slot='accordion-item'][1]")
                );

                if (!owners.isEmpty() && owners.get(0).equals(chapter)) {
                    return trigger;
                }
            } catch (StaleElementReferenceException ignored) {
            }
        }

        // Older markup fallback: only buttons whose nearest accordion-item
        // owner is still this chapter. Never accept a nested material button.
        List<WebElement> buttons = chapter.findElements(By.xpath(".//button"));
        for (WebElement button : buttons) {
            try {
                List<WebElement> owners = button.findElements(
                        By.xpath("ancestor::*[@data-slot='accordion-item'][1]")
                );
                if (!owners.isEmpty() && owners.get(0).equals(chapter)) {
                    return button;
                }
            } catch (StaleElementReferenceException ignored) {
            }
        }

        throw new NoSuchElementException(
                "Top-level chapter header trigger not found."
        );
    }

    /*
     * ============================================================
     * CHAPTER OPEN?
     * ============================================================
     */
    private static boolean isChapterOpen(
            WebElement trigger
    ) {

        String state =
                trigger.getAttribute(
                        "data-state"
                );


        String expanded =
                trigger.getAttribute(
                        "aria-expanded"
                );


        return "open".equalsIgnoreCase(state)
                || "true".equalsIgnoreCase(expanded);
    }


    /*
     * ============================================================
     * GET WATCH BUTTONS FOR ONE CHAPTER
     * ============================================================
     */
    private static List<WebElement> getWatchButtonsForChapter(
            WebDriver driver,
            int chapterNumber
    ) {

        WebElement chapter =
                getChapter(
                        driver,
                        chapterNumber
                );


        List<WebElement> buttons =
                chapter.findElements(
                        By.xpath(
                                ".//button[" +
                                        "contains(" +
                                        "translate(normalize-space(.)," +
                                        "'ABCDEFGHIJKLMNOPQRSTUVWXYZ'," +
                                        "'abcdefghijklmnopqrstuvwxyz')," +
                                        "'watch video'" +
                                        ")" +
                                        "]"
                        )
                );


        return visibleOnly(
                buttons
        );
    }


    /*
     * ============================================================
     * FIND VISIBLE WATCH VIDEO BUTTONS
     * ============================================================
     */
    private static List<WebElement> findVisibleWatchVideoButtons(
            WebDriver driver
    ) {

        return visibleElements(
                driver,
                By.xpath(
                        "//button[" +
                                "contains(" +
                                "translate(normalize-space(.)," +
                                "'ABCDEFGHIJKLMNOPQRSTUVWXYZ'," +
                                "'abcdefghijklmnopqrstuvwxyz')," +
                                "'watch video'" +
                                ")" +
                                "]"
                )
        );
    }


    private static boolean hasWatchVideoButton(
            WebDriver driver
    ) {

        return !findVisibleWatchVideoButtons(
                driver
        ).isEmpty();
    }


    /*
     * ============================================================
     * START QUIZ BUTTON
     * ============================================================
     */
    private static boolean hasStartQuizButton(
            WebDriver driver
    ) {

        return !visibleElements(
                driver,
                By.xpath(
                        "//button[" +
                                "contains(" +
                                "translate(normalize-space(.)," +
                                "'ABCDEFGHIJKLMNOPQRSTUVWXYZ'," +
                                "'abcdefghijklmnopqrstuvwxyz')," +
                                "'start quiz'" +
                                ")" +
                                "]"
                )
        ).isEmpty();
    }


    /*
     * ============================================================
     * SUBMIT BUTTON
     * ============================================================
     */
    private static boolean hasSubmitButton(
            WebDriver driver
    ) {

        return !visibleElements(
                driver,
                By.xpath(
                        "//button[" +
                                "normalize-space()='Submit'" +
                                " or contains(" +
                                "translate(normalize-space(.)," +
                                "'ABCDEFGHIJKLMNOPQRSTUVWXYZ'," +
                                "'abcdefghijklmnopqrstuvwxyz')," +
                                "'submit'" +
                                ")" +
                                "]"
                )
        ).isEmpty();
    }


    /*
     * ============================================================
     * FINISH BUTTON
     * ============================================================
     */
    private static boolean hasFinishButton(
            WebDriver driver
    ) {

        return !visibleElements(
                driver,
                By.xpath(
                        "//button[" +
                                "normalize-space()='Finish'" +
                                " or contains(" +
                                "translate(normalize-space(.)," +
                                "'ABCDEFGHIJKLMNOPQRSTUVWXYZ'," +
                                "'abcdefghijklmnopqrstuvwxyz')," +
                                "'finish'" +
                                ")" +
                                "]"
                )
        ).isEmpty();
    }


    /*
     * ============================================================
     * VIDEO EXISTS?
     * ============================================================
     */
    private static boolean hasVideo(
            WebDriver driver
    ) {

        List<WebElement> videos =
                driver.findElements(
                        By.tagName(
                                "video"
                        )
                );


        for (WebElement video : videos) {

            try {

                if (video.isDisplayed()) {

                    return true;
                }

            } catch (
                    StaleElementReferenceException ignored
            ) {
            }
        }


        return false;
    }


    /*
     * ============================================================
     * PLAY CURRENT VIDEO
     * ============================================================
     */
    private static void playCurrentVideo(
            WebDriver driver
    ) throws Exception {

        WebDriverWait wait =
                new WebDriverWait(
                        driver,
                        Duration.ofSeconds(WAIT_SECONDS)
                );


        JavascriptExecutor js =
                (JavascriptExecutor) driver;


        /*
         * Find current visible video.
         */
        WebElement video =
                wait.until(driver1 -> {

                    List<WebElement> videos =
                            driver1.findElements(
                                    By.tagName("video")
                            );


                    for (WebElement item : videos) {

                        try {

                            if (item.isDisplayed()) {

                                return item;
                            }

                        } catch (
                                StaleElementReferenceException ignored
                        ) {
                        }
                    }


                    return null;
                });


        /*
         * Start video and set playback speed.
         */
        Object initialSpeed =
                js.executeScript(
                        """
                        const video = arguments[0];
                        const speed = arguments[1];

                        if (!video) {
                            return 0;
                        }

                        video.muted = true;
                        video.volume = 0;

                        try {
                            video.defaultPlaybackRate = speed;
                        } catch (e) {
                        }

                        try {
                            video.playbackRate = speed;
                        } catch (e) {
                        }

                        const playResult = video.play();

                        if (playResult !== undefined) {
                            playResult.catch(() => {
                            });
                        }

                        return Number(
                            video.playbackRate || 1
                        );
                        """,
                        video,
                        VIDEO_SPEED
                );


        System.out.println();
        System.out.println(
                "===================================="
        );

        System.out.println(
                "VIDEO STARTED"
        );

        System.out.println(
                "REQUESTED SPEED : "
                        + VIDEO_SPEED
                        + "x"
        );

        System.out.println(
                "ACTUAL SPEED    : "
                        + initialSpeed
                        + "x"
        );

        System.out.println(
                "===================================="
        );


        int zeroDurationCount = 0;


        /*
         * ========================================================
         * VIDEO MONITOR LOOP
         * ========================================================
         */
        while (true) {

            List<WebElement> visibleVideos =
                    new ArrayList<>();


            for (WebElement item :
                    driver.findElements(
                            By.tagName("video")
                    )) {

                try {

                    if (item.isDisplayed()) {

                        visibleVideos.add(
                                item
                        );
                    }

                } catch (
                        StaleElementReferenceException ignored
                ) {
                }
            }


            /*
             * Video disappeared.
             */
            if (visibleVideos.isEmpty()) {

                System.out.println();
                System.out.println(
                        "Video element closed."
                );

                break;
            }


            video =
                    visibleVideos.get(0);


            Object rawStatus;


            try {

                rawStatus =
                        js.executeScript(
                                """
                                const video = arguments[0];
                                const speed = arguments[1];

                                if (!video) {
                                    return null;
                                }

                                video.muted = true;
                                video.volume = 0;

                                try {

                                    if (
                                        Math.abs(
                                            Number(
                                                video.playbackRate || 1
                                            ) - speed
                                        ) > 0.01
                                    ) {

                                        video.defaultPlaybackRate =
                                            speed;

                                        video.playbackRate =
                                            speed;
                                    }

                                } catch (e) {
                                }

                                return {

                                    currentTime:
                                        Number(
                                            video.currentTime || 0
                                        ),

                                    duration:
                                        Number(
                                            video.duration || 0
                                        ),

                                    ended:
                                        Boolean(
                                            video.ended
                                        ),

                                    paused:
                                        Boolean(
                                            video.paused
                                        ),

                                    playbackRate:
                                        Number(
                                            video.playbackRate || 1
                                        ),

                                    readyState:
                                        Number(
                                            video.readyState || 0
                                        )
                                };
                                """,
                                video,
                                VIDEO_SPEED
                        );


            } catch (
                    StaleElementReferenceException e
            ) {

                sleepQuietly(
                        700
                );

                continue;


            } catch (Exception e) {

                System.out.println(
                        "Video status check failed: "
                                + e.getMessage()
                );

                sleepQuietly(
                        700
                );

                continue;
            }


            if (!(rawStatus instanceof Map)) {

                sleepQuietly(
                        700
                );

                continue;
            }


            @SuppressWarnings("unchecked")
            Map<String, Object> status =
                    (Map<String, Object>) rawStatus;


            double current =
                    toDouble(
                            status.get(
                                    "currentTime"
                            )
                    );


            double duration =
                    toDouble(
                            status.get(
                                    "duration"
                            )
                    );


            double actualSpeed =
                    toDouble(
                            status.get(
                                    "playbackRate"
                            )
                    );


            boolean ended =
                    Boolean.TRUE.equals(
                            status.get(
                                    "ended"
                            )
                    );


            boolean paused =
                    Boolean.TRUE.equals(
                            status.get(
                                    "paused"
                            )
                    );





            /*
             * Invalid duration.
             */
            if (duration <= 0) {

                zeroDurationCount++;


                if (zeroDurationCount > 45) {

                    throw new RuntimeException(
                            "Video duration remained 0 "
                                    + "for too long."
                    );
                }

            } else {

                zeroDurationCount = 0;
            }


            /*
             * Resume if unexpectedly paused.
             */
            if (paused
                    && !ended
                    && duration > 0
                    && current < duration - 1) {

                try {

                    js.executeScript(
                            """
                            const video = arguments[0];
                            const speed = arguments[1];

                            if (!video) {
                                return;
                            }

                            video.muted = true;
                            video.volume = 0;

                            try {

                                video.defaultPlaybackRate =
                                    speed;

                                video.playbackRate =
                                    speed;

                            } catch (e) {
                            }

                            const playResult =
                                video.play();

                            if (
                                playResult !== undefined
                            ) {

                                playResult.catch(
                                    () => {
                                    }
                                );
                            }
                            """,
                            video,
                            VIDEO_SPEED
                    );


                    System.out.println(
                            "Video resumed at "
                                    + VIDEO_SPEED
                                    + "x"
                    );


                } catch (Exception ignored) {
                }
            }


            /*
             * Actual video completion.
             */
            if (ended
                    ||
                    (
                            duration > 0
                                    && current
                                    >= duration - 0.75
                    )) {

                System.out.println();
                System.out.println(
                        "===================================="
                );

                System.out.println(
                        "VIDEO REACHED END"
                );

                System.out.println(
                        "Final Time : "
                                + current
                                + " / "
                                + duration
                );

                System.out.println(
                        "Speed      : "
                                + actualSpeed
                                + "x"
                );

                System.out.println(
                        "===================================="
                );

                break;
            }


            Thread.sleep(
                    1000
            );
        }


        /*
         * Allow website to update completion/quiz state.
         */
        Thread.sleep(
                1500
        );
    }


    /*
     * ============================================================
     * VISIBLE ELEMENT HELPERS
     * ============================================================
     */
    private static List<WebElement> visibleElements(
            WebDriver driver,
            By locator
    ) {

        return visibleOnly(
                driver.findElements(
                        locator
                )
        );
    }


    private static List<WebElement> visibleOnly(
            List<WebElement> elements
    ) {

        List<WebElement> visible =
                new ArrayList<>();


        for (WebElement element : elements) {

            try {

                if (element.isDisplayed()
                        && element.isEnabled()) {

                    visible.add(
                            element
                    );
                }

            } catch (
                    StaleElementReferenceException ignored
            ) {
            }
        }


        return visible;
    }


    /*
     * ============================================================
     * NUMBER CONVERSION
     * ============================================================
     */
    private static double toDouble(
            Object value
    ) {

        if (value instanceof Number number) {

            return number.doubleValue();
        }


        try {

            return Double.parseDouble(
                    String.valueOf(
                            value
                    )
            );

        } catch (Exception e) {

            return 0;
        }
    }


    /*
     * ============================================================
     * SAFE CLICK
     * ============================================================
     */
    private static void safeClick(
            WebDriver driver,
            WebElement element
    ) {

        JavascriptExecutor js =
                (JavascriptExecutor) driver;


        try {

            js.executeScript(
                    "arguments[0].scrollIntoView(" +
                            "{block:'center'});",
                    element
            );


            Thread.sleep(
                    300
            );


            element.click();


        } catch (Exception firstError) {

            js.executeScript(
                    "arguments[0].click();",
                    element
            );
        }
    }


    /*
     * ============================================================
     * SAFE SLEEP
     * ============================================================
     */
    private static void sleepQuietly(
            long milliseconds
    ) {

        try {

            Thread.sleep(
                    milliseconds
            );

        } catch (InterruptedException e) {

            Thread.currentThread()
                    .interrupt();
        }
    }
}
