package org.example;

import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.sheets.v4.Sheets;
import com.google.api.services.sheets.v4.SheetsScopes;
import com.google.api.services.sheets.v4.model.ValueRange;
import com.google.auth.http.HttpCredentialsAdapter;
import com.google.auth.oauth2.GoogleCredentials;

import java.io.FileInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Keeps shared Case Reminder rules in the master Google Sheet in sync.
 *
 * Staff Chrome extensions already read the same Sheet2 every minute, so a row
 * inserted here becomes available on every staff PC without replacing the
 * unpacked extension on those PCs.
 */
public final class CentralCaseRulesSync {

    private static final Pattern SHEET_ID_PATTERN = Pattern.compile(
            "https?://docs\\.google\\.com/spreadsheets/d/([^/]+)",
            Pattern.CASE_INSENSITIVE
    );

    private CentralCaseRulesSync() {
    }

    public static void ensureAgentRules(
            Properties config,
            Collection<String> discoveredServiceNames
    ) {
        try {
            Properties cfg = config == null ? new Properties() : config;
            boolean enabled = Boolean.parseBoolean(
                    safe(cfg.getProperty("case.rules.central.sync.enabled", "true"))
            );
            if (!enabled) {
                return;
            }

            String rulesUrl = safe(cfg.getProperty("case.rules.csv.url", ""));
            String sheetName = safe(cfg.getProperty("case.rules.sheet.name", "Sheet2"));
            String spreadsheetId = extractSpreadsheetId(rulesUrl);

            if (spreadsheetId.isBlank()) {
                System.out.println("CENTRAL AGENT RULE SYNC SKIPPED - invalid case.rules.csv.url");
                return;
            }

            Path credentialsFile = Path.of(
                    safe(cfg.getProperty("case.rules.service.account.file", "service-account.json"))
            );
            if (!Files.isRegularFile(credentialsFile)) {
                System.out.println(
                        "CENTRAL AGENT RULE SYNC SKIPPED - "
                                + credentialsFile
                                + " not found"
                );
                return;
            }

            Sheets sheets = createSheetsService(credentialsFile);
            String range = quoteSheetName(sheetName) + "!A:Z";
            ValueRange current = sheets.spreadsheets()
                    .values()
                    .get(spreadsheetId, range)
                    .execute();

            List<List<Object>> values = current.getValues();
            if (values == null || values.isEmpty()) {
                System.out.println("CENTRAL AGENT RULE SYNC SKIPPED - rules sheet is empty");
                return;
            }

            List<Object> headerRow = values.get(0);
            Map<String, Integer> header = indexHeaders(headerRow);
            int serviceCol = findColumn(header, "service");
            int optionCol = findColumn(header, "remark option", "remarks option", "option", "sub service", "subservice");
            int syntaxCol = findColumn(header, "remarks syntax", "remark syntax", "remarks", "syntax");
            int actionCol = findColumn(header, "action code", "action", "code");
            int typeCol = findColumn(header, "type");
            int enabledCol = findColumn(header, "enabled", "enable", "active");

            if (serviceCol < 0 || syntaxCol < 0) {
                System.out.println(
                        "CENTRAL AGENT RULE SYNC SKIPPED - Sheet2 needs Service and Remarks Syntax headers"
                );
                return;
            }

            Set<String> servicesToEnsure = new LinkedHashSet<>();

            // Every service that already appears anywhere in Sheet2.
            for (int i = 1; i < values.size(); i++) {
                String service = cell(values.get(i), serviceCol);
                if (!service.isBlank()) {
                    servicesToEnsure.add(service.trim());
                }
            }

            // Every service seen in the Mariners Mentor cases API this month.
            if (discoveredServiceNames != null) {
                for (String service : discoveredServiceNames) {
                    String clean = safe(service);
                    if (!clean.isBlank()) {
                        servicesToEnsure.add(clean);
                    }
                }
            }

            // Optional extra service names can be kept in config.properties.
            String configured = safe(cfg.getProperty("case.rules.agent.services", ""));
            for (String service : configured.split("[;\\r\\n]+")) {
                String clean = safe(service);
                if (!clean.isBlank()) {
                    servicesToEnsure.add(clean);
                }
            }

            Map<String, Boolean> hasAgentByService = new LinkedHashMap<>();
            for (int i = 1; i < values.size(); i++) {
                List<Object> row = values.get(i);
                String service = cell(row, serviceCol);
                if (service.isBlank()) {
                    continue;
                }
                String option = cell(row, optionCol);
                String syntax = cell(row, syntaxCol);
                boolean agent = normalize(option).equals("agent")
                        || normalize(syntax).equals("agent agent name");
                if (agent) {
                    hasAgentByService.put(normalize(service), true);
                }
            }

            int width = Math.max(1, maxIndex(
                    serviceCol,
                    optionCol,
                    syntaxCol,
                    actionCol,
                    typeCol,
                    enabledCol
            ) + 1);

            List<List<Object>> rowsToAppend = new ArrayList<>();
            for (String service : servicesToEnsure) {
                String key = normalize(service);
                if (key.isBlank() || Boolean.TRUE.equals(hasAgentByService.get(key))) {
                    continue;
                }

                List<Object> row = blankRow(width);
                set(row, serviceCol, service);
                set(row, optionCol, "Agent");
                set(row, syntaxCol, "Agent : Agent Name");
                set(row, actionCol, "WELCOME");
                set(row, typeCol, "TEXT");
                set(row, enabledCol, "TRUE");
                rowsToAppend.add(row);
            }

            if (rowsToAppend.isEmpty()) {
                System.out.println(
                        "CENTRAL AGENT RULE SYNC OK - Agent syntax already present for all known services"
                );
                return;
            }

            ValueRange appendBody = new ValueRange().setValues(rowsToAppend);
            sheets.spreadsheets()
                    .values()
                    .append(
                            spreadsheetId,
                            quoteSheetName(sheetName) + "!A:Z",
                            appendBody
                    )
                    .setValueInputOption("RAW")
                    .setInsertDataOption("INSERT_ROWS")
                    .execute();

            System.out.println(
                    "CENTRAL AGENT RULE SYNC ADDED "
                            + rowsToAppend.size()
                            + " Agent rule(s) to "
                            + sheetName
                            + " - staff PCs will refresh automatically"
            );
        } catch (Exception e) {
            // This helper must never stop normal case processing.
            System.out.println(
                    "CENTRAL AGENT RULE SYNC WARNING - "
                            + e.getClass().getSimpleName()
                            + ": "
                            + safe(e.getMessage())
            );
        }
    }

    private static Sheets createSheetsService(Path credentialsFile) throws Exception {
        try (FileInputStream inputStream = new FileInputStream(credentialsFile.toFile())) {
            GoogleCredentials credentials = GoogleCredentials.fromStream(inputStream)
                    .createScoped(Collections.singleton(SheetsScopes.SPREADSHEETS));

            return new Sheets.Builder(
                    GoogleNetHttpTransport.newTrustedTransport(),
                    GsonFactory.getDefaultInstance(),
                    new HttpCredentialsAdapter(credentials)
            )
                    .setApplicationName("Mariners Mentor Central Case Rules Sync")
                    .build();
        }
    }

    private static Map<String, Integer> indexHeaders(List<Object> row) {
        Map<String, Integer> result = new LinkedHashMap<>();
        for (int i = 0; i < row.size(); i++) {
            result.put(normalizeHeader(safe(row.get(i))), i);
        }
        return result;
    }

    private static int findColumn(Map<String, Integer> header, String... names) {
        for (String name : names) {
            Integer index = header.get(normalizeHeader(name));
            if (index != null) {
                return index;
            }
        }
        return -1;
    }

    private static int maxIndex(int... indexes) {
        int max = -1;
        for (int index : indexes) {
            if (index > max) {
                max = index;
            }
        }
        return max;
    }

    private static List<Object> blankRow(int width) {
        List<Object> row = new ArrayList<>(width);
        for (int i = 0; i < width; i++) {
            row.add("");
        }
        return row;
    }

    private static void set(List<Object> row, int index, Object value) {
        if (index >= 0 && index < row.size()) {
            row.set(index, value);
        }
    }

    private static String cell(List<Object> row, int index) {
        if (row == null || index < 0 || index >= row.size()) {
            return "";
        }
        return safe(row.get(index));
    }

    private static String extractSpreadsheetId(String url) {
        Matcher matcher = SHEET_ID_PATTERN.matcher(safe(url));
        return matcher.find() ? safe(matcher.group(1)) : "";
    }

    private static String quoteSheetName(String sheetName) {
        return "'" + safe(sheetName).replace("'", "''") + "'";
    }

    private static String normalizeHeader(String value) {
        return normalize(value);
    }

    private static String normalize(String value) {
        return safe(value)
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static String safe(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
