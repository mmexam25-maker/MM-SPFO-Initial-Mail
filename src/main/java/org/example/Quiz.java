package org.example;

import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.StaleElementReferenceException;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

public class Quiz {

    public static void completeCurrentQuiz(
            WebDriver driver,
            List<Integer> answerOptionNumbers
    ) throws Exception {

        WebDriverWait wait =
                new WebDriverWait(
                        driver,
                        Duration.ofSeconds(30)
                );

        Thread.sleep(1200);

        /*
         * Find Start Quiz button.
         */
        List<WebElement> startButtons =
                visibleElements(
                        driver,
                        By.xpath(
                                "//button[" +
                                        "normalize-space()='Start Quiz'" +
                                        " or contains(" +
                                        "translate(normalize-space(.)," +
                                        "'ABCDEFGHIJKLMNOPQRSTUVWXYZ'," +
                                        "'abcdefghijklmnopqrstuvwxyz')," +
                                        "'start quiz'" +
                                        ")" +
                                        "]"
                        )
                );

        /*
         * This lesson has no quiz.
         */
        if (startButtons.isEmpty()) {

            System.out.println(
                    "No Quiz Found for this part"
            );

            return;
        }

        /*
         * Quiz exists, but all Q1-Q5 cells are blank.
         * Do not stop the whole program.
         */
        if (answerOptionNumbers == null
                || answerOptionNumbers.isEmpty()) {

            System.out.println(
                    "Quiz found, but no answers are entered in Sheet2."
            );

            System.out.println(
                    "Quiz left without answering."
            );

            return;
        }

        safeClick(
                driver,
                startButtons.get(0)
        );

        System.out.println(
                "Quiz Started"
        );

        Thread.sleep(1200);

        int questionIndex = 0;

        while (true) {

            /*
             * Check whether quiz already finished.
             */
            List<WebElement> finishButtons =
                    findFinishButtons(
                            driver
                    );

            if (!finishButtons.isEmpty()) {

                safeClick(
                        driver,
                        finishButtons.get(0)
                );

                System.out.println(
                        "Quiz Finished"
                );

                Thread.sleep(1000);

                return;
            }

            /*
             * No answer available for this question.
             *
             * Example:
             * Q1 and Q2 exist in Sheet2,
             * but Q3 is blank.
             */
            if (questionIndex
                    >= answerOptionNumbers.size()) {

                System.out.println();
                System.out.println(
                        "No answer entered for Question "
                                + (questionIndex + 1)
                                + " in Sheet2."
                );

                System.out.println(
                        "Leaving this quiz without stopping the program."
                );

                return;
            }

            Integer optionNumber =
                    answerOptionNumbers.get(
                            questionIndex
                    );

            /*
             * Safety check if a null value is present.
             */
            if (optionNumber == null) {

                System.out.println(
                        "Question "
                                + (questionIndex + 1)
                                + " is blank in Sheet2."
                );

                System.out.println(
                        "Quiz left without answering this question."
                );

                return;
            }

            /*
             * Wait until options appear.
             */
            wait.until(driver1 ->
                    !findAnswerOptions(
                            driver1
                    ).isEmpty()
            );

            List<WebElement> options =
                    findAnswerOptions(
                            driver
                    );

            if (optionNumber < 1
                    || optionNumber > options.size()) {

                System.out.println(
                        "Invalid option for Question "
                                + (questionIndex + 1)
                );

                System.out.println(
                        "Sheet option = "
                                + optionNumber
                                + ", available options = "
                                + options.size()
                );

                System.out.println(
                        "Quiz left without stopping the program."
                );

                return;
            }

            /*
             * Sheet option 1 = first option.
             * Sheet option 2 = second option.
             */
            WebElement selectedOption =
                    options.get(
                            optionNumber - 1
                    );

            safeClick(
                    driver,
                    selectedOption
            );

            System.out.println(
                    "Question "
                            + (questionIndex + 1)
                            + " -> Option "
                            + optionNumber
                            + " selected"
            );

            Thread.sleep(500);

            /*
             * Click Submit.
             */
            List<WebElement> submitButtons =
                    visibleElements(
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
                    );

            if (submitButtons.isEmpty()) {

                System.out.println(
                        "Submit button not found for Question "
                                + (questionIndex + 1)
                );

                return;
            }

            safeClick(
                    driver,
                    submitButtons.get(0)
            );

            System.out.println(
                    "Submit Clicked"
            );

            Thread.sleep(1200);

            /*
             * Check Finish after submitting.
             */
            finishButtons =
                    findFinishButtons(
                            driver
                    );

            if (!finishButtons.isEmpty()) {

                safeClick(
                        driver,
                        finishButtons.get(0)
                );

                System.out.println(
                        "Quiz Finished"
                );

                Thread.sleep(1000);

                return;
            }

            /*
             * Find Next Question button.
             */
            List<WebElement> nextButtons =
                    visibleElements(
                            driver,
                            By.xpath(
                                    "//button[" +
                                            "normalize-space()='Next'" +
                                            " or normalize-space()='Next Question'" +
                                            " or contains(" +
                                            "translate(normalize-space(.)," +
                                            "'ABCDEFGHIJKLMNOPQRSTUVWXYZ'," +
                                            "'abcdefghijklmnopqrstuvwxyz')," +
                                            "'next question'" +
                                            ")" +
                                            "]"
                            )
                    );

            if (nextButtons.isEmpty()) {

                Thread.sleep(1000);

                finishButtons =
                        findFinishButtons(
                                driver
                        );

                if (!finishButtons.isEmpty()) {

                    safeClick(
                            driver,
                            finishButtons.get(0)
                    );

                    System.out.println(
                            "Quiz Finished"
                    );

                    return;
                }

                System.out.println(
                        "No Next or Finish button found."
                );

                return;
            }

            /*
             * Before opening another question,
             * check whether Sheet2 has another answer.
             */
            if (questionIndex + 1
                    >= answerOptionNumbers.size()) {

                System.out.println(
                        "No more answers entered in Sheet2."
                );

                System.out.println(
                        "Quiz left at the next unanswered question."
                );

                return;
            }

            safeClick(
                    driver,
                    nextButtons.get(0)
            );

            questionIndex++;

            System.out.println(
                    "Next Question Opened"
            );

            Thread.sleep(900);
        }
    }

    private static List<WebElement> findFinishButtons(
            WebDriver driver
    ) {

        return visibleElements(
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
        );
    }

    private static List<WebElement> findAnswerOptions(
            WebDriver driver
    ) {

        List<WebElement> possibleOptions =
                driver.findElements(
                        By.cssSelector(
                                "div.space-y-3 > div > button"
                        )
                );

        if (possibleOptions.isEmpty()) {

            possibleOptions =
                    driver.findElements(
                            By.xpath(
                                    "//button[" +
                                            "@role='radio'" +
                                            " or ancestor::*[" +
                                            "contains(@class,'space-y-3')" +
                                            "]" +
                                            "]"
                            )
                    );
        }

        List<WebElement> validOptions =
                new ArrayList<>();

        for (WebElement option :
                possibleOptions) {

            try {

                if (!option.isDisplayed()
                        || !option.isEnabled()) {

                    continue;
                }

                String text =
                        option.getText()
                                .trim()
                                .toLowerCase();

                if (text.equals("submit")
                        || text.equals("next")
                        || text.equals("next question")
                        || text.equals("finish")
                        || text.equals("start quiz")
                        || text.contains(
                        "next material"
                )) {

                    continue;
                }

                validOptions.add(
                        option
                );

            } catch (
                    StaleElementReferenceException ignored
            ) {
            }
        }

        return validOptions;
    }

    private static List<WebElement> visibleElements(
            WebDriver driver,
            By locator
    ) {

        List<WebElement> visible =
                new ArrayList<>();

        for (WebElement element :
                driver.findElements(locator)) {

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

            Thread.sleep(250);

            element.click();

        } catch (Exception firstError) {

            js.executeScript(
                    "arguments[0].click();",
                    element
            );
        }
    }
}