package org.example;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.cos.COSName;
import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.Keys;
import org.openqa.selenium.OutputType;
import org.openqa.selenium.TimeoutException;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One-time-per-row profile preparation before the wellness modules start.
 *
 * Flow:
 * 1. Open SMY Profile from the top-right profile menu.
 * 2. Read Name + Mobile, normalize mobile to 10 digits and save C/D.
 * 3. Derive DG/eSamudra INDoS from the SMY password by removing trailing '@'.
 * 4. Open DG/eSamudra in a temporary tab, login, open Update Seafarer Profile,
 *    download the DG profile PDF, and extract candidate photo + latest ship + IMO.
 * 5. Close all DG tabs, return to SMY, Edit Profile, upload the DG photo, fill
 *    Aadhaar from column T, latest ship/IMO from DG PDF, and Submit.
 */
public final class DgProfileSync {

    private static final String SMY_DASHBOARD =
            "https://sagarmeinyog.com/dashboard";
    private static final String SMY_PROFILE =
            "https://sagarmeinyog.com/dashboard/profile";
    private static final String DG_LOGIN_URL =
            "http://220.156.189.33/esamudraUI/logOut.do?method=loadIndexPage";

    private static final DateTimeFormatter DG_DATE =
            DateTimeFormatter.ofPattern("d/M/uuuu");

    // A successful row is not repeated again during the same Java run.
    private static final Set<Integer> SYNCED_ROWS =
            ConcurrentHashMap.newKeySet();

    private DgProfileSync() {
    }

    public static Path prepareWorkingDirectory(int sheetRowNumber)
            throws IOException {
        Path dir = Path.of("dg-profile-temp", "row-" + sheetRowNumber)
                .toAbsolutePath();
        Files.createDirectories(dir);

        try (var stream = Files.list(dir)) {
            for (Path file : stream.toList()) {
                if (Files.isRegularFile(file)) {
                    Files.deleteIfExists(file);
                }
            }
        }
        return dir;
    }

    public static void syncBeforeLearning(
            WebDriver driver,
            String smyPassword,
            String changedDgPassword,
            String aadhaarFromSheet,
            int sheetRowNumber,
            Path workingDir
    ) throws Exception {

        if (SYNCED_ROWS.contains(sheetRowNumber)) {
            System.out.println("PROFILE PREP ALREADY DONE THIS RUN | row "
                    + sheetRowNumber);
            driver.get(SMY_DASHBOARD);
            return;
        }

        System.out.println();
        System.out.println("====================================");
        System.out.println("PROFILE PREP START | ROW " + sheetRowNumber);
        System.out.println("====================================");

        openSmyProfileFromMenu(driver);

        String profileName = readSmyProfileName(driver);
        String mobile = readSmyMobile(driver);
        String tenDigitMobile = normalizeTenDigitMobile(mobile);

        SheetRepository.updateProfileIdentity(
                sheetRowNumber,
                profileName,
                tenDigitMobile
        );

        String indos = deriveIndosFromSmyPassword(smyPassword);
        String dgPassword = changedDgPassword == null
                || changedDgPassword.isBlank()
                ? indos + "1"
                : changedDgPassword.trim();

        String aadhaar = normalizeAadhaar(aadhaarFromSheet);
        if (aadhaar.isBlank()) {
            throw new IllegalStateException(
                    "Aadhaar is missing/invalid in PC1!T" + sheetRowNumber
                            + ". Enter 12 digits in column T."
            );
        }

        String smyHandle = driver.getWindowHandle();
        DgProfileData dgData;

        try {
            openNewTab(driver);
            String dgHandle = firstHandleOtherThan(driver, smyHandle);
            if (dgHandle == null) {
                throw new IllegalStateException("Could not open DG/eSamudra tab.");
            }

            driver.switchTo().window(dgHandle);
            dgData = fetchDgProfileData(
                    driver,
                    indos,
                    dgPassword,
                    workingDir,
                    sheetRowNumber
            );

        } finally {
            closeEveryWindowExcept(driver, smyHandle);
            driver.switchTo().window(smyHandle);
        }

        updateSmyProfile(
                driver,
                dgData.photoFile,
                aadhaar,
                dgData.shipName,
                dgData.imoNumber
        );

        // Only after the server-backed SMY profile page proves that the photo
        // is really present do we remove the temporary DG PDF/photo files.
        deleteWorkingDirectoryAfterVerifiedUpload(workingDir);

        SYNCED_ROWS.add(sheetRowNumber);

        System.out.println("PROFILE PREP COMPLETE | " + profileName
                + " | " + tenDigitMobile
                + " | Ship: " + dgData.shipName
                + " | IMO: " + dgData.imoNumber);

        driver.get(SMY_DASHBOARD);
    }

    private static void openSmyProfileFromMenu(WebDriver driver)
            throws Exception {
        WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(25));
        JavascriptExecutor js = (JavascriptExecutor) driver;

        try {
            if (!driver.getCurrentUrl().contains("/dashboard")) {
                driver.get(SMY_DASHBOARD);
            }

            WebElement menuButton = wait.until(
                    ExpectedConditions.elementToBeClickable(
                            By.xpath(
                                    "//button[@aria-haspopup='menu']"
                                            + "[.//img[@alt='Profile'] or .//span]"
                            )
                    )
            );

            click(driver, menuButton);

            WebElement profileLink = wait.until(
                    ExpectedConditions.elementToBeClickable(
                            By.cssSelector("a[href='/dashboard/profile']")
                    )
            );
            click(driver, profileLink);

        } catch (Exception menuError) {
            // Stable direct fallback if Radix menu markup changes.
            driver.get(SMY_PROFILE);
        }

        wait.until(webDriver ->
                webDriver.getCurrentUrl() != null
                        && webDriver.getCurrentUrl().contains("/dashboard/profile"));

        wait.until(ExpectedConditions.presenceOfElementLocated(
                By.xpath("//*[normalize-space()='Profile' or contains(normalize-space(.),'SEAFARER')]")
        ));

        js.executeScript("window.scrollTo(0,0);");
    }

    private static String readSmyProfileName(WebDriver driver) {
        WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(20));

        List<By> locators = List.of(
                By.xpath("//div[contains(@class,'font-bold') and contains(@class,'text-2xl')][normalize-space()]"),
                By.xpath("//span[contains(@class,'font-semibold') and normalize-space()]")
        );

        for (By locator : locators) {
            try {
                WebElement element = wait.until(
                        ExpectedConditions.visibilityOfElementLocated(locator)
                );
                String name = cleanText(element.getText());
                if (!name.isBlank()
                        && !name.equalsIgnoreCase("Profile")
                        && !name.equalsIgnoreCase("Profile Creation")) {
                    return name;
                }
            } catch (Exception ignored) {
            }
        }

        throw new IllegalStateException("SMY Profile name could not be read.");
    }

    private static String readSmyMobile(WebDriver driver)
            throws Exception {
        WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(15));

        // First try the normal Profile page Additional Details field.
        String mobile = findMobileValue(driver);
        if (!mobile.isBlank()) {
            return mobile;
        }

        // Fallback: open Edit Profile and read the mobile field there.
        WebElement edit = wait.until(
                ExpectedConditions.elementToBeClickable(
                        By.xpath("//button[normalize-space()='Edit Profile']")
                )
        );
        click(driver, edit);

        wait.until(driverInstance -> {
            String value = findMobileValue(driverInstance);
            return value.isBlank() ? null : value;
        });

        mobile = findMobileValue(driver);
        if (!mobile.isBlank()) {
            return mobile;
        }

        throw new IllegalStateException("SMY Profile mobile number could not be read.");
    }

    private static String findMobileValue(WebDriver driver) {
        List<By> locators = List.of(
                By.xpath("//*[normalize-space()='Mobile Number']/following::input[1]"),
                By.cssSelector("input[name='mobileNumber']"),
                By.cssSelector("input[name='phoneNumber']"),
                By.cssSelector("input[name='phone']")
        );

        for (By locator : locators) {
            for (WebElement element : driver.findElements(locator)) {
                try {
                    if (!element.isDisplayed()) {
                        continue;
                    }
                    String value = cleanText(element.getAttribute("value"));
                    if (!value.isBlank()) {
                        return value;
                    }
                } catch (Exception ignored) {
                }
            }
        }
        return "";
    }

    private static String normalizeTenDigitMobile(String raw) {
        String digits = raw == null ? "" : raw.replaceAll("\\D", "");

        // Examples: 08838634579 -> 8838634579; +91 8838634579 -> 8838634579.
        if (digits.length() > 10) {
            digits = digits.substring(digits.length() - 10);
        }

        if (digits.length() != 10) {
            throw new IllegalStateException(
                    "Valid 10-digit mobile number not found in SMY Profile: " + raw
            );
        }
        return digits;
    }

    private static String deriveIndosFromSmyPassword(String smyPassword) {
        String value = smyPassword == null ? "" : smyPassword.trim();
        value = value.replaceFirst("@+$", "");

        if (value.isBlank()) {
            throw new IllegalStateException(
                    "Cannot derive INDoS from SMY password. Expected value like 07EL0740@."
            );
        }
        return value;
    }

    private static String normalizeAadhaar(String raw) {
        String digits = raw == null ? "" : raw.replaceAll("\\D", "");
        if (digits.length() != 12) {
            return "";
        }
        return digits.substring(0, 4)
                + " " + digits.substring(4, 8)
                + " " + digits.substring(8, 12);
    }

    private static DgProfileData fetchDgProfileData(
            WebDriver driver,
            String indos,
            String dgPassword,
            Path workingDir,
            int sheetRowNumber
    ) throws Exception {

        WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(35));
        driver.get(DG_LOGIN_URL);

        WebElement passwordBox;
        try {
            passwordBox = wait.until(
                    ExpectedConditions.visibilityOfElementLocated(
                            By.cssSelector("input[type='password']")
                    )
            );
        } catch (TimeoutException firstPageNotLogin) {
            // Some sessions show an intermediate page with a Login link.
            WebElement loginLink = firstVisibleEnabled(
                    driver,
                    List.of(
                            By.linkText("Login"),
                            By.xpath("//a[normalize-space()='Login']"),
                            By.xpath("//button[normalize-space()='Login']")
                    )
            );
            if (loginLink != null) {
                click(driver, loginLink);
            }
            passwordBox = wait.until(
                    ExpectedConditions.visibilityOfElementLocated(
                            By.cssSelector("input[type='password']")
                    )
            );
        }

        WebElement userIdBox = firstVisibleEnabled(
                driver,
                List.of(
                        By.cssSelector("input[name='userId']"),
                        By.cssSelector("input[id='userId']"),
                        By.cssSelector("input[name*='user' i]"),
                        By.cssSelector("input[id*='user' i]"),
                        By.xpath("//input[(@type='text' or not(@type)) and not(@disabled)]")
                )
        );

        if (userIdBox == null) {
            throw new IllegalStateException("DG User Id field not found.");
        }

        userIdBox.clear();
        userIdBox.sendKeys(indos);
        passwordBox.clear();
        passwordBox.sendKeys(dgPassword);

        WebElement loginButton = firstVisibleEnabled(
                driver,
                List.of(
                        By.xpath("//input[@type='submit' and translate(@value,'abcdefghijklmnopqrstuvwxyz','ABCDEFGHIJKLMNOPQRSTUVWXYZ')='LOGIN']"),
                        By.xpath("//input[@type='button' and translate(@value,'abcdefghijklmnopqrstuvwxyz','ABCDEFGHIJKLMNOPQRSTUVWXYZ')='LOGIN']"),
                        By.xpath("//button[normalize-space()='Login']"),
                        By.xpath("//input[@value='Login']")
                )
        );

        if (loginButton == null) {
            throw new IllegalStateException("DG Login button not found.");
        }
        click(driver, loginButton);

        try {
            wait.until(webDriver ->
                    !webDriver.findElements(By.linkText("Update Seafarer Profile")).isEmpty()
                            || !webDriver.findElements(
                            By.xpath("//a[contains(normalize-space(.),'Update Seafarer Profile')]")
                    ).isEmpty());
        } catch (TimeoutException loginFailed) {
            String body = safeBodyText(driver);
            throw new IllegalStateException(
                    "DG/eSamudra login failed for INDoS " + indos
                            + ". If the DG password was changed, put it in column S."
                            + (body.isBlank() ? "" : " Page says: " + oneLine(body, 180))
            );
        }

        System.out.println("DG LOGIN SUCCESS | INDoS " + indos);

        WebElement updateProfile = firstVisibleEnabled(
                driver,
                List.of(
                        By.linkText("Update Seafarer Profile"),
                        By.xpath("//a[contains(normalize-space(.),'Update Seafarer Profile')]")
                )
        );

        if (updateProfile == null) {
            throw new IllegalStateException("Update Seafarer Profile link not found.");
        }
        click(driver, updateProfile);

        WebElement pdfLink = wait.until(webDriver -> {
            for (WebElement element : webDriver.findElements(
                    By.xpath(
                            "//a[contains(translate(normalize-space(.),"
                                    + "'abcdefghijklmnopqrstuvwxyz','ABCDEFGHIJKLMNOPQRSTUVWXYZ'),"
                                    + "'CLICK TO VIEW')]"
                    )
            )) {
                try {
                    if (element.isDisplayed() && element.isEnabled()) {
                        return element;
                    }
                } catch (Exception ignored) {
                }
            }
            return null;
        });

        Set<String> beforeHandles = new HashSet<>(driver.getWindowHandles());
        String href = cleanText(pdfLink.getAttribute("href"));
        click(driver, pdfLink);

        Path pdf = waitForDownloadedPdf(workingDir, 45);

        if (pdf == null) {
            // If the site opened a reportServlet tab instead of downloading,
            // switch to it and navigate once more with PDF-external preference.
            for (String handle : driver.getWindowHandles()) {
                if (!beforeHandles.contains(handle)) {
                    driver.switchTo().window(handle);
                    String reportUrl = driver.getCurrentUrl();
                    if (reportUrl != null && !reportUrl.isBlank()) {
                        driver.get(reportUrl);
                    }
                    break;
                }
            }
            pdf = waitForDownloadedPdf(workingDir, 30);
        }

        if (pdf == null && !href.isBlank()) {
            driver.get(href);
            pdf = waitForDownloadedPdf(workingDir, 30);
        }

        if (pdf == null) {
            throw new IllegalStateException("DG Profile PDF was not downloaded.");
        }

        System.out.println("DG PROFILE PDF SAVED | " + pdf.toAbsolutePath());
        return parseDgProfilePdf(pdf, workingDir, sheetRowNumber);
    }

    private static Path waitForDownloadedPdf(Path dir, int timeoutSeconds)
            throws Exception {
        long end = System.currentTimeMillis() + timeoutSeconds * 1000L;

        while (System.currentTimeMillis() < end) {
            try (var stream = Files.list(dir)) {
                List<Path> candidates = stream
                        .filter(Files::isRegularFile)
                        .filter(path -> !path.getFileName().toString()
                                .toLowerCase(Locale.ROOT).endsWith(".crdownload"))
                        .sorted(Comparator.comparingLong(DgProfileSync::lastModifiedSafe)
                                .reversed())
                        .toList();

                for (Path candidate : candidates) {
                    if (Files.size(candidate) > 1000 && looksLikePdf(candidate)) {
                        return candidate;
                    }
                }
            }
            Thread.sleep(500);
        }
        return null;
    }

    private static boolean looksLikePdf(Path path) {
        try (var input = Files.newInputStream(path)) {
            byte[] header = input.readNBytes(4);
            return header.length == 4
                    && header[0] == '%'
                    && header[1] == 'P'
                    && header[2] == 'D'
                    && header[3] == 'F';
        } catch (Exception ignored) {
            return false;
        }
    }

    private static long lastModifiedSafe(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException ignored) {
            return 0L;
        }
    }

    private static DgProfileData parseDgProfilePdf(
            Path pdf,
            Path workingDir,
            int sheetRowNumber
    ) throws Exception {

        try (PDDocument document = Loader.loadPDF(pdf.toFile())) {
            String text = new PDFTextStripper().getText(document);
            SeaService service = findLatestSeaService(text);

            if (service == null
                    || service.shipName.isBlank()
                    || service.imoNumber.isBlank()) {
                throw new IllegalStateException(
                        "Latest Ship Name / IMO Number could not be read from DG Profile PDF."
                );
            }

            BufferedImage photo = extractCandidatePhoto(document);
            if (photo == null) {
                throw new IllegalStateException(
                        "Candidate photo could not be extracted from DG Profile PDF."
                );
            }

            Path photoFile = workingDir.resolve(
                    "dg-photo-row-" + sheetRowNumber + ".jpg"
            );
            saveJpegForSmy(photo, photoFile);

            System.out.println("DG PHOTO EXTRACTED | " + photoFile.toAbsolutePath());
            System.out.println("DG LATEST SHIP     | " + service.shipName);
            System.out.println("DG IMO NUMBER      | " + service.imoNumber);

            return new DgProfileData(
                    photoFile.toFile(),
                    service.shipName,
                    service.imoNumber
            );
        }
    }

    private static SeaService findLatestSeaService(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }

        Pattern shipPattern = Pattern.compile(
                "(?im)Ship\\s*Name\\s*:\\s*([^\\r\\n]+)"
        );

        Matcher matcher = shipPattern.matcher(text);
        List<ShipMatch> ships = new ArrayList<>();
        while (matcher.find()) {
            ships.add(new ShipMatch(
                    matcher.start(),
                    matcher.end(),
                    cleanShipName(matcher.group(1))
            ));
        }

        List<SeaService> services = new ArrayList<>();

        for (int i = 0; i < ships.size(); i++) {
            ShipMatch ship = ships.get(i);
            if (ship.name.isBlank()) {
                continue;
            }

            int end = (i + 1 < ships.size())
                    ? ships.get(i + 1).start
                    : Math.min(text.length(), ship.end + 3000);

            if (end <= ship.start) {
                continue;
            }

            String segment = text.substring(ship.start, end);
            String imo = findFirst(
                    segment,
                    "(?i)IMO\\s*Number\\s*:\\s*([A-Z0-9-]+)"
            );

            if (imo.isBlank()) {
                continue;
            }

            String fromText = findFirst(
                    segment,
                    "(?i)Service\\s*From\\s*\\(Date\\)\\s*:\\s*(\\d{1,2}/\\d{1,2}/\\d{4})"
            );
            String toText = findFirst(
                    segment,
                    "(?i)Service\\s*To\\s*\\(Date\\)\\s*:\\s*(\\d{1,2}/\\d{1,2}/\\d{4})"
            );

            LocalDate from = parseDgDate(fromText);
            LocalDate to = parseDgDate(toText);

            services.add(new SeaService(ship.name, imo, from, to));
        }

        if (services.isEmpty()) {
            return null;
        }

        // "Last/current ship" = most recent service, independent of PDF order.
        return services.stream()
                .max(Comparator
                        .comparing((SeaService s) -> s.toDate == null
                                ? LocalDate.MIN : s.toDate)
                        .thenComparing(s -> s.fromDate == null
                                ? LocalDate.MIN : s.fromDate))
                .orElse(services.get(0));
    }

    private static String cleanShipName(String raw) {
        String value = cleanText(raw)
                .replaceFirst("^[.:\\-\\s]+", "");

        value = value.replaceFirst(
                "(?i)\\s+(Flag|Official\\s*No\\.?|Port\\s+of\\s+Registry|"
                        + "IMO\\s*Number|Ship\\s*Type|GT|Trade\\s*Area|Rank|"
                        + "Nature\\s+of\\s+watch|Service\\s+From).*$",
                ""
        ).trim();

        if (value.equalsIgnoreCase("Ship Name")) {
            return "";
        }
        return value;
    }

    private static LocalDate parseDgDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value.trim(), DG_DATE);
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    private static String findFirst(String text, String regex) {
        Matcher matcher = Pattern.compile(regex).matcher(text == null ? "" : text);
        return matcher.find() ? cleanText(matcher.group(1)) : "";
    }

    private static BufferedImage extractCandidatePhoto(PDDocument document)
            throws IOException {
        PhotoCandidate best = null;

        int maxPages = Math.min(document.getNumberOfPages(), 4);
        for (int pageIndex = 0; pageIndex < maxPages; pageIndex++) {
            PDPage page = document.getPage(pageIndex);
            List<PhotoCandidate> found = new ArrayList<>();
            collectImages(page.getResources(), pageIndex, 0, found);

            for (PhotoCandidate candidate : found) {
                if (best == null || candidate.score > best.score) {
                    best = candidate;
                }
            }
        }

        return best == null ? null : best.image;
    }

    private static void collectImages(
            PDResources resources,
            int pageIndex,
            int depth,
            List<PhotoCandidate> out
    ) throws IOException {
        if (resources == null || depth > 3) {
            return;
        }

        for (COSName name : resources.getXObjectNames()) {
            PDXObject object;
            try {
                object = resources.getXObject(name);
            } catch (Exception ignored) {
                continue;
            }

            if (object instanceof PDImageXObject imageObject) {
                BufferedImage image;
                try {
                    image = imageObject.getImage();
                } catch (Exception ignored) {
                    continue;
                }

                if (image == null) {
                    continue;
                }

                int width = image.getWidth();
                int height = image.getHeight();
                if (width < 45 || height < 55 || height <= width) {
                    continue;
                }

                double ratio = width / (double) height;
                if (ratio < 0.45 || ratio > 0.95) {
                    continue;
                }

                double score = width * (double) height;
                if (pageIndex == 0) {
                    score *= 4.0;
                }
                if (ratio >= 0.62 && ratio <= 0.86) {
                    score *= 2.0;
                }

                out.add(new PhotoCandidate(image, score));

            } else if (object instanceof PDFormXObject form) {
                collectImages(form.getResources(), pageIndex, depth + 1, out);
            }
        }
    }

    private static void saveJpegForSmy(BufferedImage source, Path target)
            throws IOException {
        int maxWidth = 600;
        int maxHeight = 800;

        double scale = Math.min(
                1.0,
                Math.min(maxWidth / (double) source.getWidth(),
                        maxHeight / (double) source.getHeight())
        );

        int width = Math.max(1, (int) Math.round(source.getWidth() * scale));
        int height = Math.max(1, (int) Math.round(source.getHeight() * scale));

        BufferedImage rgb = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = rgb.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING,
                    RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(source, 0, 0, width, height, null);
        } finally {
            g.dispose();
        }

        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpg");
        if (!writers.hasNext()) {
            throw new IOException("JPEG writer not available.");
        }

        ImageWriter writer = writers.next();
        try (ImageOutputStream output = ImageIO.createImageOutputStream(target.toFile())) {
            writer.setOutput(output);
            ImageWriteParam param = writer.getDefaultWriteParam();
            if (param.canWriteCompressed()) {
                param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                param.setCompressionQuality(0.86f);
            }
            writer.write(null, new IIOImage(rgb, null, null), param);
        } finally {
            writer.dispose();
        }

        if (Files.size(target) >= 2_000_000L) {
            throw new IOException("Extracted DG photo is still larger than 2MB: " + target);
        }
    }

    private static void updateSmyProfile(
            WebDriver driver,
            File dgPhoto,
            String aadhaar,
            String shipName,
            String imoNumber
    ) throws Exception {

        if (dgPhoto == null || !dgPhoto.isFile() || dgPhoto.length() <= 0) {
            throw new IllegalStateException("DG photo file is missing before SMY upload.");
        }

        // Do not trust the site's success toast alone.  The backend can
        // occasionally say that the profile was saved while the photo itself
        // was not persisted.  Therefore upload -> submit -> reopen Profile ->
        // verify the real rendered image.  Retry the whole save if needed.
        final int maxPhotoUploadAttempts = 4;
        String originalPhotoSrc = readLargestLoadedProfilePhotoSrc(driver);
        Exception lastFailure = null;

        for (int attempt = 1; attempt <= maxPhotoUploadAttempts; attempt++) {
            try {
                System.out.println("SMY PHOTO UPLOAD ATTEMPT " + attempt
                        + "/" + maxPhotoUploadAttempts);

                driver.get(SMY_PROFILE);
                WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(30));
                wait.until(webDriver -> webDriver.getCurrentUrl().contains("/dashboard/profile"));

                WebElement editProfile = wait.until(
                        ExpectedConditions.elementToBeClickable(
                                By.xpath("//button[normalize-space()='Edit Profile']")
                        )
                );
                click(driver, editProfile);

                WebElement aadhaarInput = wait.until(
                        ExpectedConditions.presenceOfElementLocated(
                                By.cssSelector("input[name='aadhaarNumber']")
                        )
                );

                WebElement photoInput = findProfilePhotoInput(driver);
                if (photoInput == null) {
                    // Some SMY builds create the file input only after clicking
                    // the visible upload icon.
                    WebElement uploadIcon = firstVisibleEnabled(
                            driver,
                            List.of(
                                    By.cssSelector("[title='Upload image']"),
                                    By.xpath("//*[@title='Upload image']")
                            )
                    );
                    if (uploadIcon != null) {
                        click(driver, uploadIcon);
                        Thread.sleep(600);
                        photoInput = findProfilePhotoInput(driver);
                    }
                }

                if (photoInput == null) {
                    throw new IllegalStateException(
                            "SMY Profile Picture file input not found."
                    );
                }

                try {
                    photoInput.sendKeys(dgPhoto.getAbsolutePath());
                } catch (Exception hiddenInput) {
                    ((JavascriptExecutor) driver).executeScript(
                            "arguments[0].style.display='block';"
                                    + "arguments[0].removeAttribute('hidden');",
                            photoInput
                    );
                    photoInput.sendKeys(dgPhoto.getAbsolutePath());
                }

                // Confirm that the browser accepted the local file before Submit.
                String chosenFile = cleanText(photoInput.getAttribute("value"));
                if (chosenFile.isBlank()) {
                    throw new IllegalStateException(
                            "SMY browser did not accept the DG photo file."
                    );
                }

                setInputValue(aadhaarInput, aadhaar);

                WebElement shipInput = wait.until(
                        ExpectedConditions.presenceOfElementLocated(
                                By.cssSelector("input[name='currentShipName']")
                        )
                );
                setInputValue(shipInput, shipName);

                WebElement imoInput = wait.until(
                        ExpectedConditions.presenceOfElementLocated(
                                By.cssSelector("input[name='currentShipImo']")
                        )
                );
                setInputValue(imoInput, imoNumber);

                // Allow the local preview/upload state to settle before saving.
                Thread.sleep(1800);

                WebElement submit = firstVisibleEnabled(
                        driver,
                        List.of(By.xpath("//button[normalize-space()='Submit']"))
                );
                if (submit == null) {
                    throw new IllegalStateException(
                            "SMY Profile Submit button not found."
                    );
                }
                click(driver, submit);

                // A toast is not proof.  Give the backend a moment, then force a
                // new Profile page request and verify the actual persisted photo.
                Thread.sleep(3000);

                String body = safeBodyText(driver).toLowerCase(Locale.ROOT);
                if (body.contains("please complete your profile")
                        && body.contains("required")) {
                    throw new IllegalStateException(
                            "SMY Profile did not save because another required field is missing."
                    );
                }

                verifyPersistedProfilePhoto(
                        driver,
                        dgPhoto,
                        originalPhotoSrc,
                        attempt
                );

                System.out.println(
                        "SMY PROFILE VERIFIED | DG photo really exists after fresh reload"
                );
                System.out.println(
                        "SMY PROFILE SUBMITTED | photo + Aadhaar + ship + IMO"
                );
                return;

            } catch (Exception uploadFailure) {
                lastFailure = uploadFailure;
                System.out.println(
                        "SMY PHOTO NOT VERIFIED | attempt " + attempt
                                + "/" + maxPhotoUploadAttempts
                                + " | " + oneLine(uploadFailure.getMessage(), 180)
                );

                if (attempt < maxPhotoUploadAttempts) {
                    // Server-side image processing can briefly lag/fail.  Retry
                    // from a completely fresh Profile page instead of trusting
                    // a stale preview or success message.
                    Thread.sleep(2500L * attempt);
                }
            }
        }

        throw new IllegalStateException(
                "SMY profile photo could not be verified after "
                        + maxPhotoUploadAttempts
                        + " upload attempts. Temporary DG PDF/photo were KEPT for retry/debug.",
                lastFailure
        );
    }

    private static void verifyPersistedProfilePhoto(
            WebDriver driver,
            File localDgPhoto,
            String originalPhotoSrc,
            int uploadAttempt
    ) throws Exception {

        // Navigate away first, then reopen Profile.  This avoids validating only
        // the optimistic React preview that existed before the server save.
        driver.get(SMY_DASHBOARD);
        Thread.sleep(900);
        driver.get(SMY_PROFILE);

        WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(30));
        wait.until(webDriver -> webDriver.getCurrentUrl().contains("/dashboard/profile"));

        WebElement persistedPhoto = wait.until(webDriver -> {
            WebElement candidate = findLargestVisibleProfileImage(webDriver);
            if (candidate == null) {
                return null;
            }

            try {
                Object loaded = ((JavascriptExecutor) webDriver).executeScript(
                        "return !!arguments[0].complete"
                                + " && arguments[0].naturalWidth > 30"
                                + " && arguments[0].naturalHeight > 30;",
                        candidate
                );
                return Boolean.TRUE.equals(loaded) ? candidate : null;
            } catch (Exception ignored) {
                return null;
            }
        });

        String persistedSrc = cleanText(persistedPhoto.getAttribute("src"));
        if (persistedSrc.isBlank()
                || persistedSrc.toLowerCase(Locale.ROOT).contains("placeholder")) {
            throw new IllegalStateException(
                    "Fresh SMY Profile page has no valid profile-photo URL."
            );
        }

        // Most successful SMY uploads create a new timestamped image URL.  A
        // changed loaded URL after a forced reload is strong server-side proof.
        if (!originalPhotoSrc.isBlank()
                && !persistedSrc.equals(originalPhotoSrc)) {
            System.out.println("PHOTO VERIFY: persisted image URL changed on server.");
            return;
        }

        // Some deployments reuse the same URL.  In that case compare what the
        // browser actually renders with the local DG photo. This also accepts a
        // case where the correct DG photo was already present before this run.
        File screenshot = persistedPhoto.getScreenshotAs(OutputType.FILE);
        BufferedImage rendered = ImageIO.read(screenshot);
        BufferedImage local = ImageIO.read(localDgPhoto);
        if (rendered == null || local == null) {
            throw new IllegalStateException(
                    "Could not decode SMY/DG photo while verifying persisted image."
            );
        }

        double similarity = imageSimilarityForProfile(local, rendered);
        System.out.printf(
                Locale.ROOT,
                "PHOTO VERIFY: visual similarity %.1f%%%n",
                similarity * 100.0
        );

        if (similarity < 0.72) {
            throw new IllegalStateException(
                    "SMY Profile reloaded, but the displayed photo does not match the DG photo"
                            + " (attempt " + uploadAttempt + ")."
            );
        }
    }

    private static String readLargestLoadedProfilePhotoSrc(WebDriver driver) {
        try {
            driver.get(SMY_PROFILE);
            WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(20));
            wait.until(webDriver -> webDriver.getCurrentUrl().contains("/dashboard/profile"));
            WebElement image = findLargestVisibleProfileImage(driver);
            if (image == null) {
                return "";
            }
            Object loaded = ((JavascriptExecutor) driver).executeScript(
                    "return !!arguments[0].complete"
                            + " && arguments[0].naturalWidth > 30"
                            + " && arguments[0].naturalHeight > 30;",
                    image
            );
            return Boolean.TRUE.equals(loaded)
                    ? cleanText(image.getAttribute("src"))
                    : "";
        } catch (Exception ignored) {
            return "";
        }
    }

    private static WebElement findLargestVisibleProfileImage(WebDriver driver) {
        List<WebElement> candidates = new ArrayList<>();

        // Prefer the real Profile images.  Fallback to any visible image whose
        // source looks like a user-uploaded image.
        candidates.addAll(driver.findElements(By.cssSelector("img[alt='Profile']")));
        if (candidates.isEmpty()) {
            candidates.addAll(driver.findElements(
                    By.xpath("//img[contains(@src,'/uploads/')]")
            ));
        }

        WebElement best = null;
        double bestArea = 0.0;
        for (WebElement image : candidates) {
            try {
                if (!image.isDisplayed()) {
                    continue;
                }
                double area = image.getRect().getWidth()
                        * (double) image.getRect().getHeight();
                if (area > bestArea) {
                    best = image;
                    bestArea = area;
                }
            } catch (Exception ignored) {
            }
        }
        return best;
    }

    private static double imageSimilarityForProfile(
            BufferedImage source,
            BufferedImage rendered
    ) {
        final int size = 32;
        double[] a = normalizedGraySignature(
                source,
                size,
                rendered.getWidth(),
                rendered.getHeight()
        );
        double[] b = normalizedGraySignature(
                rendered,
                size,
                rendered.getWidth(),
                rendered.getHeight()
        );

        double difference = 0.0;
        for (int i = 0; i < a.length; i++) {
            difference += Math.abs(a[i] - b[i]);
        }
        double pixelSimilarity = Math.max(
                0.0,
                1.0 - (difference / a.length)
        );

        boolean[] sourceHash = differenceHash(
                source,
                rendered.getWidth(),
                rendered.getHeight()
        );
        boolean[] renderedHash = differenceHash(
                rendered,
                rendered.getWidth(),
                rendered.getHeight()
        );

        int equalBits = 0;
        for (int i = 0; i < sourceHash.length; i++) {
            if (sourceHash[i] == renderedHash[i]) {
                equalBits++;
            }
        }
        double edgeSimilarity = equalBits / (double) sourceHash.length;

        System.out.printf(
                Locale.ROOT,
                "PHOTO VERIFY DETAIL: pixels %.1f%% | edges %.1f%%%n",
                pixelSimilarity * 100.0,
                edgeSimilarity * 100.0
        );

        // Pixel structure + edge structure together are much harder for a
        // different portrait to pass accidentally than a simple brightness check.
        return (pixelSimilarity * 0.45) + (edgeSimilarity * 0.55);
    }

    private static boolean[] differenceHash(
            BufferedImage image,
            int targetWidth,
            int targetHeight
    ) {
        final int width = 9;
        final int height = 8;
        BufferedImage scaled = resizeCoverForComparison(
                image,
                width,
                height,
                targetWidth,
                targetHeight
        );

        boolean[] hash = new boolean[64];
        int bit = 0;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width - 1; x++) {
                hash[bit++] = grayValue(scaled.getRGB(x, y))
                        < grayValue(scaled.getRGB(x + 1, y));
            }
        }
        return hash;
    }

    private static double grayValue(int rgb) {
        int r = (rgb >>> 16) & 0xff;
        int g = (rgb >>> 8) & 0xff;
        int b = rgb & 0xff;
        return 0.299 * r + 0.587 * g + 0.114 * b;
    }

    private static BufferedImage resizeCoverForComparison(
            BufferedImage image,
            int outputWidth,
            int outputHeight,
            int targetWidth,
            int targetHeight
    ) {
        double targetAspect = targetHeight <= 0
                ? 1.0
                : targetWidth / (double) targetHeight;
        double sourceAspect = image.getWidth() / (double) image.getHeight();

        int cropX = 0;
        int cropY = 0;
        int cropWidth = image.getWidth();
        int cropHeight = image.getHeight();

        if (sourceAspect > targetAspect) {
            cropWidth = Math.max(
                    1,
                    (int) Math.round(image.getHeight() * targetAspect)
            );
            cropX = Math.max(0, (image.getWidth() - cropWidth) / 2);
        } else if (sourceAspect < targetAspect) {
            cropHeight = Math.max(
                    1,
                    (int) Math.round(image.getWidth() / targetAspect)
            );
            cropY = Math.max(0, (image.getHeight() - cropHeight) / 2);
        }

        BufferedImage scaled = new BufferedImage(
                outputWidth,
                outputHeight,
                BufferedImage.TYPE_INT_RGB
        );
        Graphics2D graphics = scaled.createGraphics();
        try {
            graphics.setRenderingHint(
                    RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR
            );
            graphics.drawImage(
                    image,
                    0,
                    0,
                    outputWidth,
                    outputHeight,
                    cropX,
                    cropY,
                    cropX + cropWidth,
                    cropY + cropHeight,
                    null
            );
        } finally {
            graphics.dispose();
        }
        return scaled;
    }

    private static double[] normalizedGraySignature(
            BufferedImage image,
            int size,
            int targetWidth,
            int targetHeight
    ) {
        double targetAspect = targetHeight <= 0
                ? 1.0
                : targetWidth / (double) targetHeight;
        double sourceAspect = image.getWidth() / (double) image.getHeight();

        int cropX = 0;
        int cropY = 0;
        int cropWidth = image.getWidth();
        int cropHeight = image.getHeight();

        // Mirror CSS object-fit: cover; object-position: center.
        if (sourceAspect > targetAspect) {
            cropWidth = Math.max(1, (int) Math.round(image.getHeight() * targetAspect));
            cropX = Math.max(0, (image.getWidth() - cropWidth) / 2);
        } else if (sourceAspect < targetAspect) {
            cropHeight = Math.max(1, (int) Math.round(image.getWidth() / targetAspect));
            cropY = Math.max(0, (image.getHeight() - cropHeight) / 2);
        }

        BufferedImage scaled = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = scaled.createGraphics();
        try {
            graphics.setRenderingHint(
                    RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR
            );
            graphics.drawImage(
                    image,
                    0,
                    0,
                    size,
                    size,
                    cropX,
                    cropY,
                    cropX + cropWidth,
                    cropY + cropHeight,
                    null
            );
        } finally {
            graphics.dispose();
        }

        double[] values = new double[size * size];
        double mean = 0.0;
        int index = 0;
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                int rgb = scaled.getRGB(x, y);
                int r = (rgb >>> 16) & 0xff;
                int g = (rgb >>> 8) & 0xff;
                int bl = rgb & 0xff;
                double gray = (0.299 * r + 0.587 * g + 0.114 * bl) / 255.0;
                values[index++] = gray;
                mean += gray;
            }
        }
        mean /= values.length;

        // Normalize overall brightness so minor browser/server compression and
        // exposure differences do not cause false failures.
        for (int i = 0; i < values.length; i++) {
            values[i] = Math.max(0.0, Math.min(1.0, values[i] - mean + 0.5));
        }
        return values;
    }

    private static void deleteWorkingDirectoryAfterVerifiedUpload(Path workingDir) {
        if (workingDir == null || !Files.exists(workingDir)) {
            return;
        }

        try (var paths = Files.walk(workingDir)) {
            List<Path> all = paths
                    .sorted(Comparator.reverseOrder())
                    .toList();
            for (Path path : all) {
                Files.deleteIfExists(path);
            }
            System.out.println(
                    "DG TEMP CLEANED | verified SMY photo upload succeeded"
            );
        } catch (Exception cleanupError) {
            // Cleanup failure must never turn a successfully verified profile
            // into a failed candidate.  It can be removed on the next run.
            System.out.println(
                    "DG TEMP CLEANUP WARNING | "
                            + oneLine(cleanupError.getMessage(), 160)
            );
        }
    }

    private static WebElement findProfilePhotoInput(WebDriver driver) {
        List<By> locators = List.of(
                By.xpath("//*[normalize-space()='Profile Picture']/following::input[@type='file'][1]"),
                By.xpath("//input[@type='file'][1]")
        );

        for (By locator : locators) {
            List<WebElement> elements = driver.findElements(locator);
            if (!elements.isEmpty()) {
                return elements.get(0);
            }
        }
        return null;
    }

    private static void setInputValue(WebElement input, String value) {
        input.click();
        input.sendKeys(Keys.chord(Keys.CONTROL, "a"));
        input.sendKeys(Keys.BACK_SPACE);
        input.sendKeys(value);
    }

    private static void openNewTab(WebDriver driver) {
        ((JavascriptExecutor) driver).executeScript(
                "window.open('about:blank','_blank');"
        );
    }

    private static String firstHandleOtherThan(WebDriver driver, String excluded)
            throws InterruptedException {
        for (int attempt = 0; attempt < 20; attempt++) {
            for (String handle : driver.getWindowHandles()) {
                if (!handle.equals(excluded)) {
                    return handle;
                }
            }
            Thread.sleep(200);
        }
        return null;
    }

    private static void closeEveryWindowExcept(WebDriver driver, String keep) {
        for (String handle : new ArrayList<>(driver.getWindowHandles())) {
            if (handle.equals(keep)) {
                continue;
            }
            try {
                driver.switchTo().window(handle);
                driver.close();
            } catch (Exception ignored) {
            }
        }
    }

    private static WebElement firstVisibleEnabled(
            WebDriver driver,
            List<By> locators
    ) {
        for (By locator : locators) {
            for (WebElement element : driver.findElements(locator)) {
                try {
                    if (element.isDisplayed() && element.isEnabled()) {
                        return element;
                    }
                } catch (Exception ignored) {
                }
            }
        }
        return null;
    }

    private static void click(WebDriver driver, WebElement element) {
        try {
            element.click();
        } catch (Exception normalClickFailed) {
            ((JavascriptExecutor) driver).executeScript(
                    "arguments[0].click();",
                    element
            );
        }
    }

    private static String safeBodyText(WebDriver driver) {
        try {
            return cleanText(driver.findElement(By.tagName("body")).getText());
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String cleanText(String value) {
        return value == null
                ? ""
                : value.replace('\u00A0', ' ')
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static String oneLine(String value, int max) {
        String text = cleanText(value);
        return text.length() <= max ? text : text.substring(0, max) + "...";
    }

    private static final class DgProfileData {
        private final File photoFile;
        private final String shipName;
        private final String imoNumber;

        private DgProfileData(File photoFile, String shipName, String imoNumber) {
            this.photoFile = photoFile;
            this.shipName = shipName;
            this.imoNumber = imoNumber;
        }
    }

    private static final class PhotoCandidate {
        private final BufferedImage image;
        private final double score;

        private PhotoCandidate(BufferedImage image, double score) {
            this.image = image;
            this.score = score;
        }
    }

    private static final class ShipMatch {
        private final int start;
        private final int end;
        private final String name;

        private ShipMatch(int start, int end, String name) {
            this.start = start;
            this.end = end;
            this.name = name;
        }
    }

    private static final class SeaService {
        private final String shipName;
        private final String imoNumber;
        private final LocalDate fromDate;
        private final LocalDate toDate;

        private SeaService(
                String shipName,
                String imoNumber,
                LocalDate fromDate,
                LocalDate toDate
        ) {
            this.shipName = shipName;
            this.imoNumber = imoNumber;
            this.fromDate = fromDate;
            this.toDate = toDate;
        }
    }
}
