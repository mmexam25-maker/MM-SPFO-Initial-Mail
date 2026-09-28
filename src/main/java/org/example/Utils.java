package org.example;

import org.openqa.selenium.*;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.time.Duration;

public class Utils {

    public static void click(WebDriver driver, By by) throws Exception {

        WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(30));

        Exception last = null;

        for (int i = 0; i < 10; i++) {

            try {

                WebElement element = wait.until(
                        ExpectedConditions.presenceOfElementLocated(by));

                ((JavascriptExecutor) driver).executeScript(
                        "arguments[0].scrollIntoView({block:'center'});",
                        element);

                Thread.sleep(500);

                try {
                    element.click();
                } catch (Exception e) {
                    ((JavascriptExecutor) driver).executeScript(
                            "arguments[0].click();",
                            element);
                }

                return;

            } catch (Exception e) {

                last = e;
                Thread.sleep(1000);

            }
        }

        throw last;
    }

    public static void waitForSeconds(int sec) throws Exception {
        Thread.sleep(sec * 1000L);
    }

}