package org.example;

import org.openqa.selenium.*;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.time.Duration;
import java.util.Map;

public class Video {

    public static void playCurrentVideo(
            WebDriver driver
    ) throws Exception {

        WebDriverWait wait =
                new WebDriverWait(driver, Duration.ofSeconds(40));

        JavascriptExecutor js =
                (JavascriptExecutor) driver;

        WebElement video = wait.until(
                ExpectedConditions.visibilityOfElementLocated(
                        By.tagName("video")
                )
        );

        js.executeScript(
                "arguments[0].muted=true;" +
                        "arguments[0].play();",
                video
        );

        System.out.println("Video Started");

        int missingVideoCount = 0;

        while (true) {

            Object result = js.executeScript(
                    "var v=document.querySelector('video');" +
                            "if(!v) return null;" +
                            "return {" +
                            "ended:v.ended," +
                            "current:v.currentTime," +
                            "duration:isFinite(v.duration)" +
                            "?v.duration:0," +
                            "paused:v.paused" +
                            "};"
            );

            if (result == null) {

                missingVideoCount++;

                /*
                 * The site may remove the video element
                 * when playback is complete.
                 */
                if (missingVideoCount >= 2) {
                    break;
                }

                Thread.sleep(1000);
                continue;
            }

            missingVideoCount = 0;

            Map<?, ?> data =
                    (Map<?, ?>) result;

            boolean ended =
                    Boolean.TRUE.equals(
                            data.get("ended")
                    );

            boolean paused =
                    Boolean.TRUE.equals(
                            data.get("paused")
                    );

            double current =
                    ((Number) data.get("current"))
                            .doubleValue();

            double duration =
                    ((Number) data.get("duration"))
                            .doubleValue();

            if (ended) {
                break;
            }

            if (duration > 0 &&
                    current >= duration - 1.5) {

                break;
            }

            if (paused) {

                js.executeScript(
                        "var v=document.querySelector('video');" +
                                "if(v && !v.ended) {" +
                                "v.muted=true;" +
                                "v.play();" +
                                "}"
                );
            }

            Thread.sleep(5000);
        }

        System.out.println("Video Completed");

        Thread.sleep(2000);
    }
}