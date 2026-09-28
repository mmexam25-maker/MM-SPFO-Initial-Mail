package org.example;

import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.sheets.v4.Sheets;
import com.google.api.services.sheets.v4.SheetsScopes;
import com.google.api.services.sheets.v4.model.BatchUpdateSpreadsheetRequest;
import com.google.api.services.sheets.v4.model.CellData;
import com.google.api.services.sheets.v4.model.CellFormat;
import com.google.api.services.sheets.v4.model.Color;
import com.google.api.services.sheets.v4.model.GridRange;
import com.google.api.services.sheets.v4.model.RepeatCellRequest;
import com.google.api.services.sheets.v4.model.Request;
import com.google.api.services.sheets.v4.model.Sheet;
import com.google.api.services.sheets.v4.model.Spreadsheet;
import com.google.api.services.sheets.v4.model.TextFormat;
import com.google.api.services.sheets.v4.model.ValueRange;
import com.google.auth.http.HttpCredentialsAdapter;
import com.google.auth.oauth2.GoogleCredentials;

import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class SheetRepository {

    private static final String APPLICATION_NAME =
            "Sagar Mein Yog Automation";

    private static final String SPREADSHEET_ID =
            "1YCWNaiJENZuNTPfDd0-w6DYOSkLifehy1y0CX-9YSc4";

    private static final String CREDENTIALS_FILE =
            "credentials.json";

    // IMPORTANT: this project is the PC1 four-user runner.
    private static final String USER_SHEET =
            "PC1";

    private static final String QUIZ_SHEET =
            "Sheet2";

    /*
     * PC1 layout AFTER inserting the new Phone column D:
     * A = SMY User Name
     * B = SMY Password
     * C = Name (filled from SMY Profile)
     * D = Phone (filled from SMY Profile)
     * E = Overall Running/Completed Status
     * F = Emotional Wellness
     * G = Economic Wellness
     * H = Physical Wellness
     * I = Occupational Wellness
     * J = Social Wellness
     * K = Environmental Wellness
     * L = Intellectual Wellness
     * M = Spiritual Wellness
     * N = Climatic Wellness
     * O = Cultural Wellness
     * R = URGENT flag (old Q shifts to R after inserting D)
     * S = Changed DG/eSamudra password (optional)
     * T = Aadhaar number
     */
    public static final List<String> ALL_MODULES = List.of(
            "Emotional Wellness",
            "Economic Wellness",
            "Physical Wellness",
            "Occupational Wellness",
            "Social Wellness",
            "Environmental Wellness",
            "Intellectual Wellness",
            "Spiritual Wellness",
            "Climatic Wellness",
            "Cultural Wellness"
    );

    private static volatile Sheets sheetsService;
    private static volatile Integer userSheetId;

    /*
     * Small in-memory caches remove redundant Google Sheets requests.
     * The main A:T bulk read refreshes these values every poll, and successful
     * writes update them immediately. Active course playback never waits for an
     * extra Sheet read just to discover the status we already wrote.
     */
    private static final Map<String, String> MODULE_STATUS_CACHE =
            new ConcurrentHashMap<>();
    private static final Map<Integer, String> OVERALL_STATUS_CACHE =
            new ConcurrentHashMap<>();
    private static final Set<Integer> PENDING_FORMATTED_ROWS =
            ConcurrentHashMap.newKeySet();
    private static final Set<Integer> COMPLETED_FORMATTED_ROWS =
            ConcurrentHashMap.newKeySet();

    private SheetRepository() {
    }

    @FunctionalInterface
    private interface ApiCall<T> {
        T run() throws Exception;
    }

    /*
     * Google Sheets can return HTTP 429 when the per-user quota is reached.
     * Instead of failing the candidate immediately, wait and retry.
     */
    private static <T> T withQuotaRetry(ApiCall<T> call)
            throws Exception {

        long[] delays = {
                5_000L,
                15_000L,
                30_000L,
                60_000L
        };

        int retry = 0;

        while (true) {
            try {
                return call.run();

            } catch (GoogleJsonResponseException e) {
                if (e.getStatusCode() != 429 || retry >= delays.length) {
                    throw e;
                }

                long delay = delays[retry++];

                System.out.println();
                System.out.println("GOOGLE SHEETS 429 RATE LIMIT.");
                System.out.println("Waiting " + (delay / 1000)
                        + " seconds before retry " + retry + "...");

                try {
                    Thread.sleep(delay);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw interrupted;
                }
            }
        }
    }

    private static Sheets getSheetsService()
            throws Exception {

        Sheets current = sheetsService;
        if (current != null) {
            return current;
        }

        synchronized (SheetRepository.class) {
            if (sheetsService != null) {
                return sheetsService;
            }

            GoogleCredentials credentials;

            try (FileInputStream input =
                         new FileInputStream(CREDENTIALS_FILE)) {

                credentials = GoogleCredentials
                        .fromStream(input)
                        .createScoped(
                                Collections.singleton(
                                        SheetsScopes.SPREADSHEETS
                                )
                        );
            }

            sheetsService = new Sheets.Builder(
                    GoogleNetHttpTransport.newTrustedTransport(),
                    GsonFactory.getDefaultInstance(),
                    new HttpCredentialsAdapter(credentials)
            )
                    .setApplicationName(APPLICATION_NAME)
                    .build();

            return sheetsService;
        }
    }

    /*
     * Reads all candidates in ONE request.
     * A SMY Username
     * B SMY Password
     * C Student Name
     * D Phone
     * E Overall Status
     * F:O Module statuses
     * R URGENT flag
     * S Changed DG password
     * T Aadhaar
     */
    public static List<UserCourseRow> readAllUsers()
            throws Exception {

        ValueRange response = withQuotaRetry(
                () -> getSheetsService()
                        .spreadsheets()
                        .values()
                        .get(
                                SPREADSHEET_ID,
                                USER_SHEET + "!A2:T"
                        )
                        .execute()
        );

        List<List<Object>> values = response.getValues();
        List<UserCourseRow> users = new ArrayList<>();

        if (values == null || values.isEmpty()) {
            System.out.println("No users found in " + USER_SHEET + ".");
            return users;
        }

        for (int index = 0; index < values.size(); index++) {
            List<Object> row = values.get(index);
            int sheetRowNumber = index + 2;

            String username = getCell(row, 0);           // A
            String password = getCell(row, 1);           // B
            String studentName = getCell(row, 2);        // C
            String mobileNumber = getCell(row, 3);       // D
            String overallStatus = getCell(row, 4);      // E
            String priorityFlag = getCell(row, 17);      // R
            String changedDgPassword = getCell(row, 18); // S
            String aadhaarNumber = getCell(row, 19);     // T

            boolean urgent =
                    "URGENT".equalsIgnoreCase(priorityFlag.trim());

            if (username.isBlank()
                    && password.isBlank()
                    && studentName.isBlank()) {
                continue;
            }

            OVERALL_STATUS_CACHE.put(sheetRowNumber, overallStatus);

            List<String> moduleNames = new ArrayList<>();

            for (int moduleIndex = 0;
                 moduleIndex < ALL_MODULES.size();
                 moduleIndex++) {

                String moduleName = ALL_MODULES.get(moduleIndex);
                String moduleStatus = getCell(row, 5 + moduleIndex);

                // Keep all 10 module names in the row model and cache their
                // current F:O values from this same A:T bulk read. This avoids
                // separate F:O reads during every chapter retry.
                moduleNames.add(moduleName);
                MODULE_STATUS_CACHE.put(
                        moduleStatusKey(sheetRowNumber, moduleName),
                        moduleStatus
                );
            }

            users.add(
                    new UserCourseRow(
                            sheetRowNumber,
                            username,
                            password,
                            studentName,
                            mobileNumber,
                            overallStatus,
                            moduleNames,
                            urgent,
                            changedDgPassword,
                            aadhaarNumber
                    )
            );
        }

        return users;
    }

    /*
     * Read all 10 module cells F:O in ONE request.
     * This is the main fix for the old 429 problem.
     */
    public static synchronized List<String> readModuleStatuses(
            int sheetRowNumber
    ) throws Exception {

        String range = USER_SHEET
                + "!F" + sheetRowNumber
                + ":O" + sheetRowNumber;

        ValueRange response = withQuotaRetry(
                () -> getSheetsService()
                        .spreadsheets()
                        .values()
                        .get(SPREADSHEET_ID, range)
                        .execute()
        );

        List<String> result = new ArrayList<>();
        List<List<Object>> values = response.getValues();

        List<Object> row =
                values == null || values.isEmpty()
                        ? Collections.emptyList()
                        : values.get(0);

        // Always return exactly 10 positions and refresh the local cache.
        for (int i = 0; i < ALL_MODULES.size(); i++) {
            String status = getCell(row, i);
            result.add(status);
            MODULE_STATUS_CACHE.put(
                    moduleStatusKey(sheetRowNumber, ALL_MODULES.get(i)),
                    status
            );
        }

        return result;
    }

    public static int moduleIndexOf(String moduleName) {
        return findModuleIndex(moduleName);
    }

    public static List<QuizPlanRow> readQuizPlan()
            throws Exception {

        ValueRange response = withQuotaRetry(
                () -> getSheetsService()
                        .spreadsheets()
                        .values()
                        .get(
                                SPREADSHEET_ID,
                                QUIZ_SHEET + "!A2:F"
                        )
                        .execute()
        );

        List<List<Object>> values = response.getValues();
        List<QuizPlanRow> rows = new ArrayList<>();

        if (values == null || values.isEmpty()) {
            System.out.println("No quiz plan rows found in "
                    + QUIZ_SHEET + ".");
            return rows;
        }

        for (List<Object> row : values) {
            String moduleText = getCell(row, 0);
            String chapterText = getCell(row, 1);
            String partCode = getCell(row, 2);

            if (moduleText.isBlank()
                    || chapterText.isBlank()
                    || partCode.isBlank()) {
                continue;
            }

            int moduleNumber;
            int chapterNumber;

            try {
                String moduleDigits = moduleText.replaceAll("[^0-9]", "");
                String chapterDigits = chapterText.replaceAll("[^0-9]", "");

                if (moduleDigits.isBlank() || chapterDigits.isBlank()) {
                    continue;
                }

                moduleNumber = Integer.parseInt(moduleDigits);
                chapterNumber = Integer.parseInt(chapterDigits);

            } catch (NumberFormatException e) {
                System.out.println("Invalid quiz plan row skipped: " + row);
                continue;
            }

            List<Integer> answers = new ArrayList<>();

            for (int columnIndex = 3; columnIndex <= 5; columnIndex++) {
                String answerText = getCell(row, columnIndex);

                if (answerText.isBlank()) {
                    continue;
                }

                String numbersOnly = answerText.replaceAll("[^0-9]", "");

                if (!numbersOnly.isBlank()) {
                    answers.add(Integer.parseInt(numbersOnly));
                }
            }

            rows.add(
                    new QuizPlanRow(
                            moduleNumber,
                            chapterNumber,
                            partCode,
                            answers
                    )
            );
        }

        System.out.println("Quiz plan rows loaded: " + rows.size());
        return rows;
    }

    public static synchronized void updateModuleStatus(
            int sheetRowNumber,
            String moduleName,
            String status
    ) throws Exception {

        // Show the exact live position in the module cell, for example:
        // "Running Part 2/5 Sub-part 3/8". Only the final verified state is
        // shortened to "Completed".
        String displayStatus;
        if (status == null || status.isBlank()) {
            displayStatus = "Running";
        } else if (status.trim().equalsIgnoreCase("Completed")) {
            displayStatus = "Completed";
        } else {
            displayStatus = status.trim();
        }

        int moduleIndex = findModuleIndex(moduleName);

        if (moduleIndex < 0) {
            throw new IllegalArgumentException(
                    "Unknown module: " + moduleName
            );
        }

        String cacheKey = moduleStatusKey(sheetRowNumber, moduleName);
        String cachedStatus = MODULE_STATUS_CACHE.get(cacheKey);
        if (displayStatus.equals(cachedStatus)) {
            return;
        }

        int columnNumber = 6 + moduleIndex; // F = 6
        String columnLetter = columnNumberToLetter(columnNumber);
        String range = USER_SHEET + "!" + columnLetter + sheetRowNumber;

        ValueRange body = new ValueRange()
                .setValues(
                        List.of(
                                List.of(displayStatus)
                        )
                );

        withQuotaRetry(
                () -> getSheetsService()
                        .spreadsheets()
                        .values()
                        .update(
                                SPREADSHEET_ID,
                                range,
                                body
                        )
                        .setValueInputOption("RAW")
                        .execute()
        );

        MODULE_STATUS_CACHE.put(cacheKey, displayStatus);

        // Keep console output compact. For normal progress show only the
        // current module + part/sub-part. Completion/pending gets one line.
        if (displayStatus.matches("(?i)^Running\\s+Part\\s+.*Sub-part\\s+.*$")) {
            String compact = displayStatus
                    .replaceFirst("(?i)^Running\\s+", "")
                    .replaceFirst("(?i)\\s+Sub-part\\s+", " | Sub-part ");
            System.out.println("RUNNING | " + moduleName + " | " + compact);
        } else if (displayStatus.equalsIgnoreCase("Completed")) {
            System.out.println("COMPLETED | " + moduleName);
        } else if (displayStatus.toLowerCase().startsWith("pending")) {
            System.out.println("PENDING | " + moduleName + " | " + displayStatus);
        }
    }

    /*
     * Kept for ChapterRunner compatibility. It is now protected by 429 retry.
     * Main.java does NOT use this repeatedly anymore.
     */
    public static synchronized String readModuleStatus(
            int sheetRowNumber,
            String moduleName
    ) throws Exception {

        int moduleIndex = findModuleIndex(moduleName);

        if (moduleIndex < 0) {
            throw new IllegalArgumentException(
                    "Unknown module: " + moduleName
            );
        }

        String cacheKey = moduleStatusKey(sheetRowNumber, moduleName);
        String cached = MODULE_STATUS_CACHE.get(cacheKey);
        if (cached != null) {
            return cached;
        }

        List<String> statuses = readModuleStatuses(sheetRowNumber);
        return statuses.get(moduleIndex);
    }

    public static void markModuleCompleted(
            int sheetRowNumber,
            String moduleName
    ) throws Exception {
        updateModuleStatus(
                sheetRowNumber,
                moduleName,
                "Completed"
        );
    }

    public static synchronized void updateOverallStatus(
            int sheetRowNumber,
            String status
    ) throws Exception {

        String cleanedStatus = status == null ? "" : status.trim();
        String cachedStatus = OVERALL_STATUS_CACHE.get(sheetRowNumber);
        if (cleanedStatus.equals(cachedStatus)) {
            return;
        }

        String range = USER_SHEET + "!E" + sheetRowNumber;

        ValueRange body = new ValueRange()
                .setValues(
                        List.of(
                                List.of(cleanedStatus)
                        )
                );

        withQuotaRetry(
                () -> getSheetsService()
                        .spreadsheets()
                        .values()
                        .update(
                                SPREADSHEET_ID,
                                range,
                                body
                        )
                        .setValueInputOption("RAW")
                        .execute()
        );

        OVERALL_STATUS_CACHE.put(sheetRowNumber, cleanedStatus);

        System.out.println(
                "OVERALL STATUS UPDATED: "
                        + range + " = " + cleanedStatus
        );
    }

    /**
     * Writes the verified SMY profile identity in one API request.
     * C = Name, D = 10-digit mobile number.
     */
    public static synchronized void updateProfileIdentity(
            int sheetRowNumber,
            String name,
            String mobileNumber
    ) throws Exception {

        String cleanName = name == null ? "" : name.trim();
        String cleanMobile = mobileNumber == null ? "" : mobileNumber.trim();

        String range = USER_SHEET + "!C" + sheetRowNumber + ":D" + sheetRowNumber;
        ValueRange body = new ValueRange()
                .setValues(List.of(List.of(cleanName, cleanMobile)));

        withQuotaRetry(
                () -> getSheetsService()
                        .spreadsheets()
                        .values()
                        .update(SPREADSHEET_ID, range, body)
                        .setValueInputOption("RAW")
                        .execute()
        );

        System.out.println("PROFILE -> SHEET | C" + sheetRowNumber
                + " = " + cleanName + " | D" + sheetRowNumber
                + " = " + cleanMobile);
    }


    /* Cache the numeric sheet id so formatting does not call
       spreadsheets.get() for every candidate. */
    private static int getUserSheetId()
            throws Exception {

        Integer cached = userSheetId;
        if (cached != null) {
            return cached;
        }

        synchronized (SheetRepository.class) {
            if (userSheetId != null) {
                return userSheetId;
            }

            Spreadsheet spreadsheet = withQuotaRetry(
                    () -> getSheetsService()
                            .spreadsheets()
                            .get(SPREADSHEET_ID)
                            .setIncludeGridData(false)
                            .execute()
            );

            for (Sheet sheet : spreadsheet.getSheets()) {
                if (USER_SHEET.equals(sheet.getProperties().getTitle())) {
                    userSheetId = sheet.getProperties().getSheetId();
                    return userSheetId;
                }
            }

            throw new IllegalStateException(
                    "Sheet tab not found: " + USER_SHEET
            );
        }
    }

    public static synchronized void formatCompletedRow(
            int sheetRowNumber
    ) throws Exception {

        if (COMPLETED_FORMATTED_ROWS.contains(sheetRowNumber)) {
            return;
        }

        GridRange range = new GridRange()
                .setSheetId(getUserSheetId())
                .setStartRowIndex(sheetRowNumber - 1)
                .setEndRowIndex(sheetRowNumber)
                .setStartColumnIndex(0)
                .setEndColumnIndex(15); // A:O only

        CellFormat format = new CellFormat()
                .setBackgroundColor(
                        new Color()
                                .setRed(0.80f)
                                .setGreen(0.00f)
                                .setBlue(0.00f)
                )
                .setTextFormat(
                        new TextFormat()
                                .setForegroundColor(
                                        new Color()
                                                .setRed(1.00f)
                                                .setGreen(1.00f)
                                                .setBlue(1.00f)
                                )
                                .setBold(true)
                );

        RepeatCellRequest repeatCell = new RepeatCellRequest()
                .setRange(range)
                .setCell(
                        new CellData()
                                .setUserEnteredFormat(format)
                )
                .setFields(
                        "userEnteredFormat.backgroundColor,"
                                + "userEnteredFormat.textFormat.foregroundColor,"
                                + "userEnteredFormat.textFormat.bold"
                );

        withQuotaRetry(
                () -> getSheetsService()
                        .spreadsheets()
                        .batchUpdate(
                                SPREADSHEET_ID,
                                new BatchUpdateSpreadsheetRequest()
                                        .setRequests(
                                                List.of(
                                                        new Request()
                                                                .setRepeatCell(repeatCell)
                                                )
                                        )
                        )
                        .execute()
        );

        COMPLETED_FORMATTED_ROWS.add(sheetRowNumber);
        PENDING_FORMATTED_ROWS.remove(sheetRowNumber);

        System.out.println(
                "COMPLETED ROW RED/WHITE: "
                        + USER_SHEET + "!A" + sheetRowNumber
                        + ":O" + sheetRowNumber
        );
    }

    public static synchronized void formatPendingRow(
            int sheetRowNumber
    ) throws Exception {

        if (PENDING_FORMATTED_ROWS.contains(sheetRowNumber)) {
            return;
        }

        GridRange range = new GridRange()
                .setSheetId(getUserSheetId())
                .setStartRowIndex(sheetRowNumber - 1)
                .setEndRowIndex(sheetRowNumber)
                .setStartColumnIndex(0)
                .setEndColumnIndex(15); // A:O only

        CellFormat format = new CellFormat()
                .setBackgroundColor(
                        new Color()
                                .setRed(1.00f)
                                .setGreen(1.00f)
                                .setBlue(1.00f)
                )
                .setTextFormat(
                        new TextFormat()
                                .setForegroundColor(
                                        new Color()
                                                .setRed(0.00f)
                                                .setGreen(0.00f)
                                                .setBlue(0.00f)
                                )
                                .setBold(false)
                );

        RepeatCellRequest repeatCell = new RepeatCellRequest()
                .setRange(range)
                .setCell(
                        new CellData()
                                .setUserEnteredFormat(format)
                )
                .setFields(
                        "userEnteredFormat.backgroundColor,"
                                + "userEnteredFormat.textFormat.foregroundColor,"
                                + "userEnteredFormat.textFormat.bold"
                );

        withQuotaRetry(
                () -> getSheetsService()
                        .spreadsheets()
                        .batchUpdate(
                                SPREADSHEET_ID,
                                new BatchUpdateSpreadsheetRequest()
                                        .setRequests(
                                                List.of(
                                                        new Request()
                                                                .setRepeatCell(repeatCell)
                                                )
                                        )
                        )
                        .execute()
        );

        PENDING_FORMATTED_ROWS.add(sheetRowNumber);
        COMPLETED_FORMATTED_ROWS.remove(sheetRowNumber);

        System.out.println(
                "PENDING ROW NORMAL FORMAT: "
                        + USER_SHEET + "!A" + sheetRowNumber
                        + ":O" + sheetRowNumber
        );
    }

    public static boolean areAllModulesCompleted(
            int sheetRowNumber
    ) throws Exception {

        List<String> statuses =
                readModuleStatuses(sheetRowNumber);

        for (int moduleIndex = 0;
             moduleIndex < ALL_MODULES.size();
             moduleIndex++) {

            String moduleName = ALL_MODULES.get(moduleIndex);
            String moduleCell = statuses.get(moduleIndex);

            if (!isModuleCompletedCell(moduleCell, moduleName)) {
                System.out.println(
                        "Still pending: " + moduleName
                                + " | Status: "
                                + (moduleCell.isBlank()
                                ? "Blank"
                                : moduleCell)
                );
                return false;
            }
        }

        return true;
    }

    private static boolean isModuleCompletedCell(
            String moduleCell,
            String expectedModuleName
    ) {
        String cleanedCell = normalizeModuleName(moduleCell);

        if (cleanedCell.isBlank()) {
            return false;
        }

        // Strict final truth in the Sheet: only the literal status Completed.
        // Legacy cells that merely contain the module name are NOT accepted.
        return cleanedCell.equalsIgnoreCase("Completed");
    }

    private static String moduleStatusKey(
            int sheetRowNumber,
            String moduleName
    ) {
        return sheetRowNumber + "|" + normalizeModuleName(moduleName).toLowerCase();
    }

    private static int findModuleIndex(String moduleName) {
        String cleanedModule = normalizeModuleName(moduleName);

        for (int index = 0; index < ALL_MODULES.size(); index++) {
            String expected =
                    normalizeModuleName(ALL_MODULES.get(index));

            if (expected.equalsIgnoreCase(cleanedModule)) {
                return index;
            }
        }

        return -1;
    }

    private static String normalizeModuleName(String value) {
        if (value == null) {
            return "";
        }

        return value
                .replace("\u00A0", " ")
                .replace(",", "")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static String getCell(
            List<Object> row,
            int index
    ) {
        if (row == null
                || index < 0
                || index >= row.size()
                || row.get(index) == null) {
            return "";
        }

        return String.valueOf(row.get(index)).trim();
    }

    private static String columnNumberToLetter(int columnNumber) {
        StringBuilder result = new StringBuilder();
        int number = columnNumber;

        while (number > 0) {
            int remainder = (number - 1) % 26;
            result.insert(0, (char) ('A' + remainder));
            number = (number - 1) / 26;
        }

        return result.toString();
    }
}
