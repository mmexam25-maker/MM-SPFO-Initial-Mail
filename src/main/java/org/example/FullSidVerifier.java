package com.marinersmentor.bsid;

import org.openqa.selenium.*;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

public class FullSidVerifier {

    private static WebDriver driver;
    private static WebDriverWait wait;

    public static void main(String[] args) {

        String searchValue = "18GL1761";

        try {

            setupDriver();

            driver.get("https://dgshippingbsid.in/");

            System.out.println("BSID opened.");

            openSidVerification();

            enterSidOrIndos(searchValue);

            System.out.println();
            System.out.println("====================================");
            System.out.println("ENTER CAPTCHA MANUALLY IN BROWSER");
            System.out.println("Then the program will continue.");
            System.out.println("====================================");

            waitForCaptchaEntry();

            clickVerifySid();

            waitForResultPopup();

            SidResult result = readSidResult();

            System.out.println();
            System.out.println("====================================");
            System.out.println("SID RESULT");
            System.out.println("====================================");

            System.out.println("Search Value : " + searchValue);
            System.out.println("SID No       : " + result.sidNo);
            System.out.println("Status       : " + result.status);

            savePopupScreenshot(
                    "sid-front-" + safeFileName(result.sidNo) + ".png"
            );

            if (isFrontSideVisible()) {

                System.out.println("Front side detected.");

                // Front side screenshot already saved

                clickFlipCard();

                Thread.sleep(1200);

                savePopupScreenshot(
                        "sid-back-" + safeFileName(result.sidNo) + ".png"
                );

            } else {

                System.out.println("Back side detected first.");

                savePopupScreenshot(
                        "sid-back-" + safeFileName(result.sidNo) + ".png"
                );
            }

            SidMrzData mrzData =
                    readAndParseMrz();

            if (mrzData != null) {

                System.out.println();
                System.out.println("====================================");
                System.out.println("PARSED SID DETAILS");
                System.out.println("====================================");

                System.out.println(
                        "SID No       : " + mrzData.sidNo
                );

                System.out.println(
                        "Surname      : " + mrzData.surname
                );

                System.out.println(
                        "Given Name   : " + mrzData.givenName
                );

                System.out.println(
                        "Full Name    : " + mrzData.fullName
                );

                System.out.println(
                        "DOB          : " + mrzData.dob
                );

                System.out.println(
                        "Sex          : " + mrzData.sex
                );

                System.out.println(
                        "Nationality  : " + mrzData.nationality
                );

                System.out.println(
                        "Expiry Date  : " + mrzData.expiryDate
                );

                result.mrzData = mrzData;
            }

            System.out.println();
            System.out.println(
                    "SID verification completed successfully."
            );

            /*
             * When you later merge this into MMcasesBot,
             * return result instead of only printing it.
             */

        } catch (Exception e) {

            e.printStackTrace();

        }
    }

    private static void setupDriver() {

        ChromeOptions options =
                new ChromeOptions();

        options.setExperimentalOption(
                "detach",
                true
        );

        driver =
                new ChromeDriver(options);

        driver.manage()
                .window()
                .maximize();

        wait =
                new WebDriverWait(
                        driver,
                        Duration.ofSeconds(30)
                );
    }

    private static void openSidVerification()
            throws Exception {

        Thread.sleep(1200);

        List<WebElement> menuItems =
                driver.findElements(
                        By.xpath(
                                "//*[contains(normalize-space(.),'SID Verification')]"
                        )
                );

        for (WebElement el : menuItems) {

            try {

                if (!el.isDisplayed())
                    continue;

                safeClick(el);

                Thread.sleep(1200);

                if (
                        !driver.findElements(
                                By.cssSelector(
                                        "input[placeholder='Make Sure Correct Indos/Applicationid/SidNo']"
                                )
                        ).isEmpty()
                ) {

                    System.out.println(
                            "SID Verification page opened."
                    );

                    return;
                }

            } catch (Exception ignored) {
            }
        }

        /*
         * If the site already opened directly on the
         * SID Verification page, continue.
         */
        if (
                !driver.findElements(
                        By.cssSelector(
                                "input[placeholder='Make Sure Correct Indos/Applicationid/SidNo']"
                        )
                ).isEmpty()
        ) {

            return;
        }

        throw new RuntimeException(
                "SID Verification section not found."
        );
    }

    private static void enterSidOrIndos(
            String value
    ) {

        WebElement input =
                wait.until(
                        ExpectedConditions.visibilityOfElementLocated(
                                By.cssSelector(
                                        "input[placeholder='Make Sure Correct Indos/Applicationid/SidNo']"
                                )
                        )
                );

        input.click();

        input.sendKeys(
                Keys.CONTROL,
                "a"
        );

        input.sendKeys(value);

        System.out.println(
                "SID / INDoS entered: "
                        + value
        );
    }

    private static void waitForCaptchaEntry()
            throws Exception {

        /*
         * CAPTCHA must be entered manually.
         *
         * We only wait until the user has typed something
         * into the security-code field.
         */

        long start =
                System.currentTimeMillis();

        long maxWait =
                120_000;

        while (
                System.currentTimeMillis()
                        - start
                        < maxWait
        ) {

            List<WebElement> inputs =
                    driver.findElements(
                            By.xpath(
                                    "//input[contains(@placeholder,'code shown') "
                                            + "or contains(@placeholder,'Security') "
                                            + "or contains(@placeholder,'security')]"
                            )
                    );

            for (WebElement el : inputs) {

                try {

                    String value =
                            el.getAttribute(
                                    "value"
                            );

                    if (
                            value != null
                                    &&
                                    !value.isBlank()
                    ) {

                        System.out.println(
                                "CAPTCHA entered."
                        );

                        return;
                    }

                } catch (Exception ignored) {
                }
            }

            Thread.sleep(500);
        }

        throw new RuntimeException(
                "CAPTCHA was not entered within 2 minutes."
        );
    }

    private static void clickVerifySid() {

        WebElement verify =
                wait.until(
                        ExpectedConditions.elementToBeClickable(
                                By.xpath(
                                        "//button[@type='submit' "
                                                + "and contains(normalize-space(.),'Verify SID')]"
                                )
                        )
                );

        safeClick(verify);

        System.out.println(
                "Verify SID clicked."
        );
    }

    private static void waitForResultPopup() {

        wait.until(
                d -> {

                    String body =
                            d.findElement(
                                    By.tagName("body")
                            ).getText();

                    return
                            body.contains("FLIP CARD")
                                    ||
                                    body.contains("Flip Card")
                                    ||
                                    body.contains("ISSUED")
                                    ||
                                    body.contains("VALID");
                }
        );

        System.out.println(
                "SID result popup opened."
        );
    }

    private static SidResult readSidResult() {

        SidResult result =
                new SidResult();

        String body =
                driver.findElement(
                        By.tagName("body")
                ).getText();

        /*
         * SID number shown on blue popup header.
         *
         * Example:
         * SID: C33218431
         */

        List<WebElement> sidLabels =
                driver.findElements(
                        By.xpath(
                                "//*[contains(normalize-space(.),'SID:')]"
                        )
                );

        for (WebElement el : sidLabels) {

            try {

                if (!el.isDisplayed())
                    continue;

                String text =
                        el.getText()
                                .trim();

                String sid =
                        extractSidFromText(text);

                if (!sid.isBlank()) {

                    result.sidNo =
                            sid;

                    break;
                }

            } catch (Exception ignored) {
            }
        }

        if (
                body.toUpperCase()
                        .contains("ISSUED")
        ) {

            result.status =
                    "ISSUED";

        } else if (
                body.toUpperCase()
                        .contains("VALID")
        ) {

            result.status =
                    "VALID";

        } else if (
                body.toUpperCase()
                        .contains("EXPIRED")
        ) {

            result.status =
                    "EXPIRED";

        } else {

            result.status =
                    "";
        }

        return result;
    }

    private static String extractSidFromText(
            String text
    ) {

        if (text == null)
            return "";

        String upper =
                text.toUpperCase();

        int index =
                upper.indexOf("SID:");

        if (index < 0)
            return "";

        String remaining =
                upper.substring(
                        index + 4
                ).trim();

        StringBuilder sid =
                new StringBuilder();

        for (
                int i = 0;
                i < remaining.length();
                i++
        ) {

            char c =
                    remaining.charAt(i);

            if (
                    Character.isLetterOrDigit(c)
            ) {

                sid.append(c);

            } else {

                if (sid.length() > 0)
                    break;
            }
        }

        return sid.toString();
    }

    private static boolean isFrontSideVisible() {

        String body =
                driver.findElement(
                                By.tagName("body")
                        ).getText()
                        .toUpperCase();

        return
                body.contains(
                        "CLICK CARD TO VIEW BACK SIDE"
                );
    }

    private static void clickFlipCard()
            throws Exception {

        WebElement flip =
                wait.until(
                        ExpectedConditions.elementToBeClickable(
                                By.xpath(
                                        "//button[@type='button' "
                                                + "and contains("
                                                + "translate(normalize-space(.),"
                                                + "'abcdefghijklmnopqrstuvwxyz',"
                                                + "'ABCDEFGHIJKLMNOPQRSTUVWXYZ'),"
                                                + "'FLIP CARD')]"
                                )
                        )
                );

        safeClick(flip);

        System.out.println(
                "Flip Card clicked."
        );
    }

    private static SidMrzData readAndParseMrz()
            throws Exception {

        /*
         * Make sure back side is visible.
         */

        if (isFrontSideVisible()) {

            clickFlipCard();

            Thread.sleep(1000);
        }

        List<WebElement> paragraphs =
                driver.findElements(
                        By.xpath(
                                "//p"
                        )
                );

        List<String> mrz =
                new ArrayList<>();

        for (WebElement el : paragraphs) {

            try {

                if (!el.isDisplayed())
                    continue;

                String text =
                        el.getText()
                                .trim();

                if (
                        text.startsWith("ISIND")
                                ||
                                text.contains("<<")
                ) {

                    /*
                     * Ignore ordinary UI text.
                     */
                    if (
                            text.length() >= 20
                    ) {

                        if (
                                !mrz.contains(text)
                        ) {

                            mrz.add(text);
                        }
                    }
                }

            } catch (Exception ignored) {
            }
        }

        if (mrz.size() < 3) {

            System.out.println(
                    "MRZ data was not found."
            );

            return null;
        }

        /*
         * Use the final 3 MRZ rows.
         */

        String line1 =
                mrz.get(
                        mrz.size() - 3
                );

        String line2 =
                mrz.get(
                        mrz.size() - 2
                );

        String line3 =
                mrz.get(
                        mrz.size() - 1
                );

        System.out.println();
        System.out.println("MRZ RAW DATA");

        System.out.println(
                "1: " + line1
        );

        System.out.println(
                "2: " + line2
        );

        System.out.println(
                "3: " + line3
        );

        return parseSidMrz(
                line1,
                line2,
                line3
        );
    }

    private static SidMrzData parseSidMrz(
            String line1,
            String line2,
            String line3
    ) {

        SidMrzData data =
                new SidMrzData();

        /*
         * Example:
         *
         * ISINDC332184319<<<<<<<<<<<<<<<
         */

        if (
                line1.startsWith("ISIND")
        ) {

            String part =
                    line1.substring(5);

            int less =
                    part.indexOf('<');

            if (less >= 0) {

                part =
                        part.substring(
                                0,
                                less
                        );
            }

            /*
             * Last digit is MRZ check digit.
             *
             * C332184319
             * becomes
             * C33218431
             */

            if (
                    part.length() > 1
            ) {

                data.sidNo =
                        part.substring(
                                0,
                                part.length() - 1
                        );
            }
        }

        /*
         * Example:
         *
         * 9906013M3303296IND<<<<<<<<<<<6
         *
         * DOB:
         * 990601 -> 01/06/1999
         *
         * Sex:
         * M
         *
         * Expiry:
         * 330329 -> 29/03/2033
         *
         * Nationality:
         * IND
         */

        if (
                line2.length() >= 18
        ) {

            String dobRaw =
                    line2.substring(
                            0,
                            6
                    );

            data.sex =
                    line2.substring(
                            7,
                            8
                    );

            String expiryRaw =
                    line2.substring(
                            8,
                            14
                    );

            data.nationality =
                    line2.substring(
                            15,
                            18
                    );

            data.dob =
                    convertMrzDate(
                            dobRaw,
                            false
                    );

            data.expiryDate =
                    convertMrzDate(
                            expiryRaw,
                            true
                    );
        }

        /*
         * Example:
         *
         * THOMMAI<<SANJIV<<<<<<<<<<
         */

        String nameLine =
                line3.replaceAll(
                        "<+$",
                        ""
                );

        String[] parts =
                nameLine.split(
                        "<<",
                        2
                );

        if (
                parts.length > 0
        ) {

            data.surname =
                    parts[0]
                            .replace(
                                    "<",
                                    " "
                            )
                            .trim();
        }

        if (
                parts.length > 1
        ) {

            data.givenName =
                    parts[1]
                            .replace(
                                    "<",
                                    " "
                            )
                            .trim();
        }

        data.fullName =
                (
                        data.surname
                                + " "
                                + data.givenName
                ).trim();

        return data;
    }

    private static String convertMrzDate(
            String raw,
            boolean expiry
    ) {

        if (
                raw == null
                        ||
                        raw.length() != 6
        ) {

            return "";
        }

        try {

            int yy =
                    Integer.parseInt(
                            raw.substring(
                                    0,
                                    2
                            )
                    );

            String mm =
                    raw.substring(
                            2,
                            4
                    );

            String dd =
                    raw.substring(
                            4,
                            6
                    );

            int year;

            if (expiry) {

                year =
                        2000 + yy;

            } else {

                int currentYear =
                        LocalDate.now()
                                .getYear();

                int currentYY =
                        currentYear % 100;

                if (
                        yy > currentYY
                ) {

                    year =
                            1900 + yy;

                } else {

                    year =
                            2000 + yy;
                }
            }

            return
                    dd
                            + "/"
                            + mm
                            + "/"
                            + year;

        } catch (Exception e) {

            return raw;
        }
    }

    private static void savePopupScreenshot(
            String filename
    ) {

        try {

            WebElement popup =
                    findResultPopup();

            File source;

            if (popup != null) {

                source =
                        popup.getScreenshotAs(
                                OutputType.FILE
                        );

            } else {

                source =
                        ((TakesScreenshot) driver)
                                .getScreenshotAs(
                                        OutputType.FILE
                                );
            }

            File destination =
                    new File(filename);

            Files.copy(
                    source.toPath(),
                    destination.toPath(),
                    StandardCopyOption.REPLACE_EXISTING
            );

            System.out.println(
                    "Screenshot saved: "
                            + destination.getAbsolutePath()
            );

        } catch (Exception e) {

            System.out.println(
                    "Screenshot failed: "
                            + e.getMessage()
            );
        }
    }

    private static WebElement findResultPopup() {

        /*
         * React/MUI dialog fallback selectors.
         */

        List<By> selectors =
                List.of(

                        By.cssSelector(
                                "[role='dialog']"
                        ),

                        By.xpath(
                                "//*[contains(normalize-space(.),'FLIP CARD')]/ancestor::div[contains(@class,'Mui')][1]"
                        ),

                        By.xpath(
                                "//*[contains(normalize-space(.),'SID:')]/ancestor::div[contains(@class,'Mui')][1]"
                        )
                );

        for (By selector : selectors) {

            List<WebElement> elements =
                    driver.findElements(
                            selector
                    );

            for (WebElement el : elements) {

                try {

                    if (
                            el.isDisplayed()
                    ) {

                        return el;
                    }

                } catch (Exception ignored) {
                }
            }
        }

        return null;
    }

    private static void safeClick(
            WebElement element
    ) {

        try {

            ((JavascriptExecutor) driver)
                    .executeScript(
                            "arguments[0].scrollIntoView({block:'center'});",
                            element
                    );

            element.click();

        } catch (Exception e) {

            ((JavascriptExecutor) driver)
                    .executeScript(
                            "arguments[0].click();",
                            element
                    );
        }
    }

    private static String safeFileName(
            String value
    ) {

        if (
                value == null
                        ||
                        value.isBlank()
        ) {

            return "unknown";
        }

        return value.replaceAll(
                "[^A-Za-z0-9_-]",
                "_"
        );
    }

    public static class SidResult {

        public String sidNo = "";

        public String status = "";

        public SidMrzData mrzData;
    }

    public static class SidMrzData {

        public String sidNo = "";

        public String surname = "";

        public String givenName = "";

        public String fullName = "";

        public String dob = "";

        public String sex = "";

        public String nationality = "";

        public String expiryDate = "";

        @Override
        public String toString() {

            return
                    "SID No      : "
                            + sidNo
                            + "\nSurname     : "
                            + surname
                            + "\nGiven Name  : "
                            + givenName
                            + "\nFull Name   : "
                            + fullName
                            + "\nDOB         : "
                            + dob
                            + "\nSex         : "
                            + sex
                            + "\nNationality : "
                            + nationality
                            + "\nExpiry Date : "
                            + expiryDate;
        }
    }
}