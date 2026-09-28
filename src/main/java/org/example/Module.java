package org.example;

import org.openqa.selenium.*;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.time.Duration;
import java.util.List;

public class Module {

    public static void openModule(
            WebDriver driver,
            String moduleName
    ) throws Exception {

        WebDriverWait wait =
                new WebDriverWait(driver, Duration.ofSeconds(40));

        JavascriptExecutor js =
                (JavascriptExecutor) driver;

        System.out.println();
        System.out.println("Searching Module : " + moduleName);
        System.out.println("Current URL      : " + driver.getCurrentUrl());

        /*
         * Wait for dashboard/course list page.
         */
        wait.until(webDriver ->
                webDriver.findElements(
                        By.xpath(
                                "//*[self::h2 or self::h3 or self::h4]" +
                                        "[normalize-space()=" +
                                        xpathLiteral(moduleName) +
                                        "]"
                        )
                ).size() > 0
                        ||
                        webDriver.findElements(
                                By.xpath(
                                        "//input[contains(" +
                                                "translate(@placeholder," +
                                                "'ABCDEFGHIJKLMNOPQRSTUVWXYZ'," +
                                                "'abcdefghijklmnopqrstuvwxyz')," +
                                                "'search')]"
                                )
                        ).size() > 0
        );

        By moduleHeading = By.xpath(
                "//*[self::h2 or self::h3 or self::h4]" +
                        "[normalize-space()=" +
                        xpathLiteral(moduleName) +
                        "]"
        );

        WebElement heading = findModuleHeading(
                driver,
                wait,
                moduleHeading,
                moduleName
        );

        js.executeScript(
                "arguments[0].scrollIntoView(" +
                        "{block:'center',inline:'nearest'});",
                heading
        );

        Thread.sleep(1000);

        /*
         * Find a parent container containing this course heading
         * and its Play button.
         */
        WebElement card = findCourseCard(
                heading
        );

        /*
         * Find the Play button in the selected course card.
         */
        WebElement playButton = findPlayButton(
                card
        );

        wait.until(
                ExpectedConditions.elementToBeClickable(
                        playButton
                )
        );

        js.executeScript(
                "arguments[0].scrollIntoView(" +
                        "{block:'center',inline:'nearest'});",
                playButton
        );

        Thread.sleep(1000);

        safeClick(
                driver,
                playButton
        );

        System.out.println(
                "Play Button Clicked : " + moduleName
        );

        /*
         * Wait until the selected course opens.
         */
        wait.until(driver1 -> {

            boolean accordionExists =
                    !driver1.findElements(
                            By.xpath(
                                    "//*[@data-slot='accordion-item']"
                            )
                    ).isEmpty();

            boolean videoExists =
                    !driver1.findElements(
                            By.tagName("video")
                    ).isEmpty();

            boolean watchVideoExists =
                    !driver1.findElements(
                            By.xpath(
                                    "//button[contains(" +
                                            "normalize-space(.)," +
                                            "'Watch Video')]"
                            )
                    ).isEmpty();

            return accordionExists
                    || videoExists
                    || watchVideoExists;
        });

        System.out.println(moduleName + " Opened");

        Thread.sleep(3000);
    }

    private static WebElement findModuleHeading(
            WebDriver driver,
            WebDriverWait wait,
            By moduleHeading,
            String moduleName
    ) throws Exception {

        List<WebElement> headings =
                driver.findElements(moduleHeading);

        if (!headings.isEmpty()) {

            return wait.until(
                    ExpectedConditions.visibilityOf(
                            headings.get(0)
                    )
            );
        }

        /*
         * Search only when the module card is not currently visible.
         */
        List<WebElement> searchBoxes =
                driver.findElements(
                        By.xpath(
                                "//input[" +
                                        "contains(" +
                                        "translate(@placeholder," +
                                        "'ABCDEFGHIJKLMNOPQRSTUVWXYZ'," +
                                        "'abcdefghijklmnopqrstuvwxyz')," +
                                        "'search'" +
                                        ")" +
                                        "]"
                        )
                );

        if (searchBoxes.isEmpty()) {

            throw new Exception(
                    "Module card was not found and the Search courses " +
                            "box is not available.\n" +
                            "Current URL: " + driver.getCurrentUrl() + "\n" +
                            "Make sure Login.java does not call " +
                            "openEmotionalWellness(driver)."
            );
        }

        WebElement search =
                wait.until(
                        ExpectedConditions.visibilityOf(
                                searchBoxes.get(0)
                        )
                );

        search.click();

        search.sendKeys(
                Keys.CONTROL,
                "a"
        );

        search.sendKeys(moduleName);

        Thread.sleep(2500);

        return wait.until(
                ExpectedConditions.visibilityOfElementLocated(
                        moduleHeading
                )
        );
    }

    private static WebElement findCourseCard(
            WebElement heading
    ) {

        /*
         * First try a card containing a button.
         */
        List<WebElement> cards =
                heading.findElements(
                        By.xpath(
                                "./ancestor::div[" +
                                        ".//button" +
                                        "][1]"
                        )
                );

        if (!cards.isEmpty()) {
            return cards.get(0);
        }

        /*
         * Original rounded card fallback.
         */
        cards = heading.findElements(
                By.xpath(
                        "./ancestor::div[" +
                                "contains(@class,'rounded')" +
                                "][1]"
                )
        );

        if (!cards.isEmpty()) {
            return cards.get(0);
        }

        throw new NoSuchElementException(
                "Could not locate the course card containing: "
                        + heading.getText()
        );
    }

    private static WebElement findPlayButton(
            WebElement card
    ) {

        /*
         * Try Play text first.
         */
        List<WebElement> buttons =
                card.findElements(
                        By.xpath(
                                ".//button[" +
                                        "contains(" +
                                        "translate(normalize-space(.)," +
                                        "'ABCDEFGHIJKLMNOPQRSTUVWXYZ'," +
                                        "'abcdefghijklmnopqrstuvwxyz')," +
                                        "'play'" +
                                        ")" +
                                        "]"
                        )
                );

        if (!buttons.isEmpty()) {
            return buttons.get(0);
        }

        /*
         * Try lucide-play SVG.
         */
        buttons = card.findElements(
                By.xpath(
                        ".//button[" +
                                ".//*[name()='svg' and (" +
                                "contains(@class,'lucide-play') or " +
                                "contains(@data-lucide,'play')" +
                                ")]" +
                                "]"
                )
        );

        if (!buttons.isEmpty()) {
            return buttons.get(0);
        }

        /*
         * Try any button containing an SVG.
         */
        buttons = card.findElements(
                By.xpath(
                        ".//button[.//*[name()='svg']]"
                )
        );

        if (!buttons.isEmpty()) {
            return buttons.get(0);
        }

        throw new NoSuchElementException(
                "Play button was not found inside course card."
        );
    }

    private static void safeClick(
            WebDriver driver,
            WebElement element
    ) {

        JavascriptExecutor js =
                (JavascriptExecutor) driver;

        try {

            element.click();

        } catch (Exception firstError) {

            try {

                new ActionsHelper(driver)
                        .click(element);

            } catch (Exception secondError) {

                js.executeScript(
                        "arguments[0].click();",
                        element
                );
            }
        }
    }

    private static String xpathLiteral(
            String text
    ) {

        if (!text.contains("'")) {
            return "'" + text + "'";
        }

        if (!text.contains("\"")) {
            return "\"" + text + "\"";
        }

        String[] parts =
                text.split("'", -1);

        StringBuilder value =
                new StringBuilder("concat(");

        for (int i = 0; i < parts.length; i++) {

            if (i > 0) {
                value.append(",\"'\",");
            }

            value.append("'")
                    .append(parts[i])
                    .append("'");
        }

        value.append(")");

        return value.toString();
    }

    /*
     * Small helper for a normal Selenium click.
     */
    private static class ActionsHelper {

        private final WebDriver driver;

        public ActionsHelper(
                WebDriver driver
        ) {
            this.driver = driver;
        }

        public void click(
                WebElement element
        ) {

            new org.openqa.selenium.interactions.Actions(driver)
                    .moveToElement(element)
                    .click()
                    .perform();
        }
    }
}