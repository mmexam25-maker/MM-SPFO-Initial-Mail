package org.example;

import org.openqa.selenium.*;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.time.Duration;

public class Login {

    public static void login(WebDriver driver,
                             String username,
                             String password) throws Exception {

        JavascriptExecutor js = (JavascriptExecutor) driver;
        Exception lastError = null;

        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                driver.get("https://sagarmeinyog.com/");

                WebDriverWait wait =
                        new WebDriverWait(driver, Duration.ofSeconds(25));

                WebElement email = wait.until(
                        ExpectedConditions.visibilityOfElementLocated(By.id("email"))
                );
                email.clear();
                email.sendKeys(username);

                WebElement passwordBox = driver.findElement(By.id("password"));
                passwordBox.clear();
                passwordBox.sendKeys(password);

                WebElement loginBtn = wait.until(
                        ExpectedConditions.elementToBeClickable(
                                By.xpath("//button[@type='submit']")
                        )
                );

                try {
                    loginBtn.click();
                } catch (Exception clickError) {
                    js.executeScript("arguments[0].click();", loginBtn);
                }

                if (waitForDashboard(driver, 20)) {
                    System.out.println("Login Successful");
                    Thread.sleep(1200);
                    return;
                }

                /*
                 * The live SPA occasionally authenticates successfully but
                 * leaves the browser on "/". In that case open the dashboard
                 * directly using the already-created authenticated session.
                 */
                driver.get("https://sagarmeinyog.com/dashboard");

                if (waitForDashboard(driver, 15)) {
                    System.out.println("Login Successful");
                    Thread.sleep(1200);
                    return;
                }

                lastError = new TimeoutException(
                        "Login did not reach dashboard. Current url: "
                                + driver.getCurrentUrl()
                );

            } catch (Exception e) {
                lastError = e;
            }

            if (attempt < 3) {
                Thread.sleep(1000);
            }
        }

        if (lastError != null) {
            throw lastError;
        }

        throw new TimeoutException("Login did not reach dashboard.");
    }

    private static boolean waitForDashboard(
            WebDriver driver,
            int seconds
    ) {
        try {
            return new WebDriverWait(
                    driver,
                    Duration.ofSeconds(seconds)
            ).until(webDriver -> {
                String url = webDriver.getCurrentUrl();
                boolean dashboardUrl =
                        url != null && url.contains("/dashboard");

                boolean courseHeading = !webDriver.findElements(
                        By.xpath("//h2[normalize-space()='All Courses']")
                ).isEmpty();

                return dashboardUrl && courseHeading;
            });
        } catch (TimeoutException ignored) {
            return false;
        }
    }

    public static void openEmotionalWellness(WebDriver driver) throws Exception {

        WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(30));
        JavascriptExecutor js = (JavascriptExecutor) driver;

        WebElement play = wait.until(
                ExpectedConditions.elementToBeClickable(
                        By.xpath(
                                "//h3[normalize-space()='Emotional Wellness']" +
                                        "/ancestor::div[contains(@class,'rounded-3xl')]" +
                                        "//button"
                        )
                )
        );

        js.executeScript(
                "arguments[0].scrollIntoView({block:'center'});",
                play);

        Thread.sleep(1000);

        js.executeScript("arguments[0].click();", play);

        // Wait until course page opens
        wait.until(ExpectedConditions.presenceOfElementLocated(
                By.xpath("//div[@data-slot='accordion-item']")
        ));

        System.out.println("Emotional Wellness Opened");

        Thread.sleep(3000);
    }
}