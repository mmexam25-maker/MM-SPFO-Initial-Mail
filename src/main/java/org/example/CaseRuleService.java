package org.example;

import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the shared "MM Case Reminder Rules" Google Sheet as CSV.
 *
 * Supported columns (new master format):
 * Service | Remark Option | Remarks Syntax | Action Code | Type | Enabled
 *
 * The old 4-column format also keeps working:
 * Service | Remarks Syntax | Type | Enabled
 *
 * Rules are cached for one minute.  Editing the Google Sheet therefore changes
 * the bot's service/remarks routing without recompiling Java.
 */
public final class CaseRuleService {

    public static final String ACTION_WELCOME = "WELCOME";
    public static final String ACTION_JSU_BALANCE = "JSU_BALANCE";
    public static final String ACTION_JSU_RETIREMENT = "JSU_RETIREMENT";
    public static final String ACTION_SPFO_BALANCE = "SPFO_BALANCE";
    public static final String ACTION_SPFO_INITIAL_MAIL = "SPFO_INITIAL_MAIL";
    public static final String ACTION_SPFO_INTEREST_MAIL = "SPFO_INTEREST_MAIL";
    public static final String ACTION_SPFO_BALANCE_MAIL = "SPFO_BALANCE_MAIL";
    public static final String ACTION_SPFO_RETIREMENT = "SPFO_RETIREMENT";
    public static final String ACTION_REMINDER = "REMINDER";
    public static final String ACTION_IGNORE = "IGNORE";

    private static final long CACHE_MILLIS = Duration.ofMinutes(1).toMillis();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private static final Pattern GOOGLE_EDIT_SHEET = Pattern.compile(
            "https?://docs\\.google\\.com/spreadsheets/d/([^/]+)/.*",
            Pattern.CASE_INSENSITIVE
    );

    private static volatile Properties config = new Properties();
    private static volatile List<Rule> cachedRules = List.of();
    private static volatile long cachedAt = 0L;
    private static volatile String lastLoadMessage = "not loaded yet";

    private CaseRuleService() {
    }

    public static void configure(Properties properties) {
        config = properties == null ? new Properties() : properties;
        cachedRules = List.of();
        cachedAt = 0L;
    }

    public static String getLastLoadMessage() {
        return lastLoadMessage;
    }

    public static List<Rule> getRules() {
        return loadRules(false);
    }

    public static List<Rule> getRulesForService(String serviceName) {
        String service = normalize(serviceName);
        if (service.isBlank()) {
            return List.of();
        }

        List<Rule> all = loadRules(false);
        List<Rule> exact = new ArrayList<>();
        for (Rule rule : all) {
            if (normalize(rule.service()).equals(service)) {
                exact.add(rule);
            }
        }
        if (!exact.isEmpty()) {
            return List.copyOf(exact);
        }

        List<Rule> fuzzy = new ArrayList<>();
        for (Rule rule : all) {
            String r = normalize(rule.service());
            if (r.length() >= 4 && (service.contains(r) || r.contains(service))) {
                fuzzy.add(rule);
            }
        }
        return List.copyOf(fuzzy);
    }

    /**
     * Resolves the action for one case from Service + Remarks.
     * The Sheet wins when a matching row exists.  Legacy inference is retained
     * so old dashboard service names keep working during migration.
     */
    public static String resolveActionCode(String serviceName, String remarks) {
        String inferred = inferActionCode(serviceName, remarks, "", "");
        List<Rule> candidates = getRulesForService(serviceName);

        if (candidates.isEmpty()) {
            return inferred;
        }

        String remarksNorm = normalize(remarks);
        Rule best = null;
        int bestScore = -1;

        for (Rule rule : candidates) {
            int score = scoreRule(rule, remarksNorm);
            if (score > bestScore) {
                bestScore = score;
                best = rule;
            }
        }

        // For a service with only one Sheet row, that row can safely drive the action.
        if (best != null && (bestScore > 0 || candidates.size() == 1)) {
            return effectiveActionCode(best);
        }

        // For multi-option services such as JSU/SPFO, Remarks must identify the row.
        // If not, use the safe legacy inference instead of accidentally choosing row 1.
        return inferred;
    }

    public static String effectiveActionCode(Rule rule) {
        if (rule == null) {
            return ACTION_WELCOME;
        }

        String configured = normalizeAction(rule.actionCode());
        if (!configured.isBlank()) {
            return configured;
        }

        return inferActionCode(
                rule.service(),
                rule.remarksSyntax(),
                rule.remarkOption(),
                rule.type()
        );
    }

    public static String inferActionCode(
            String serviceName,
            String remarks,
            String remarkOption,
            String type
    ) {
        String service = normalize(serviceName);
        String combined = normalize(
                safe(serviceName) + " "
                        + safe(remarks) + " "
                        + safe(remarkOption)
        );

        if (combined.contains("jsu claim all") || combined.equals("claim all")) {
            return "CLAIM_ALL";
        }

        boolean jsu = service.equals("jsu")
                || service.startsWith("jsu ")
                || combined.contains("japan pf")
                || combined.contains("japan pension")
                || combined.contains("retirement pay plan");

        if (jsu) {
            if (combined.contains("balance")) {
                return ACTION_JSU_BALANCE;
            }
            if (combined.contains("retirement")
                    || combined.contains("retired")
                    || combined.contains("rpp")
                    || combined.contains("withdraw")) {
                return ACTION_JSU_RETIREMENT;
            }
        }

        boolean spfo = service.equals("spfo")
                || service.contains("seaman provident fund")
                || service.contains("seamen provident fund")
                || combined.contains("spfo");

        if (spfo) {
            if (combined.contains("initial") && combined.contains("problem")) {
                return ACTION_SPFO_INITIAL_MAIL;
            }
            if ((combined.contains("interest") || combined.contains("intrest"))
                    && combined.contains("problem")) {
                return ACTION_SPFO_INTEREST_MAIL;
            }
            if ((combined.contains("balance problem") || combined.contains("balance error"))) {
                return ACTION_SPFO_BALANCE_MAIL;
            }
            if (combined.contains("retirement") || combined.contains("retired")) {
                return ACTION_SPFO_RETIREMENT;
            }
            if (combined.contains("balance")) {
                return ACTION_SPFO_BALANCE;
            }
        }

        String typeNorm = normalizeAction(type);
        if (typeNorm.equals("LOCATION")
                || safe(remarks).toUpperCase(Locale.ROOT).contains("REM :")) {
            return ACTION_REMINDER;
        }

        return ACTION_WELCOME;
    }

    private static int scoreRule(Rule rule, String remarksNorm) {
        if (remarksNorm.isBlank()) {
            return 0;
        }

        int score = 0;
        String option = normalize(rule.remarkOption());
        String syntax = normalize(stripPlaceholders(rule.remarksSyntax()));

        if (!option.isBlank()) {
            if (remarksNorm.equals(option)) score = Math.max(score, 120);
            if (remarksNorm.contains(option)) score = Math.max(score, 100);
            if (option.contains(remarksNorm) && remarksNorm.length() >= 5) score = Math.max(score, 80);
        }

        if (!syntax.isBlank()) {
            if (remarksNorm.equals(syntax)) score = Math.max(score, 115);
            if (remarksNorm.contains(syntax)) score = Math.max(score, 95);
            if (syntax.contains(remarksNorm) && remarksNorm.length() >= 5) score = Math.max(score, 75);
        }

        String inferredForRule = effectiveActionCode(rule);
        String inferredForCase = inferActionCode(rule.service(), remarksNorm, "", "");
        if (!ACTION_WELCOME.equals(inferredForCase)
                && inferredForCase.equals(inferredForRule)) {
            score = Math.max(score, 70);
        }

        return score;
    }

    private static synchronized List<Rule> loadRules(boolean force) {
        long now = System.currentTimeMillis();
        if (!force && !cachedRules.isEmpty() && (now - cachedAt) < CACHE_MILLIS) {
            return cachedRules;
        }

        String configuredUrl = safe(config.getProperty("case.rules.csv.url", "")).trim();
        if (configuredUrl.isBlank()) {
            cachedRules = defaultRules();
            cachedAt = now;
            lastLoadMessage = "case.rules.csv.url is blank - using built-in migration rules";
            return cachedRules;
        }

        try {
            String csvUrl = normalizeGoogleSheetUrl(
                    configuredUrl,
                    safe(config.getProperty("case.rules.sheet.name", "")).trim()
            );
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(csvUrl))
                    .timeout(Duration.ofSeconds(25))
                    .header("User-Agent", "MMcasesBot/1.0")
                    .GET()
                    .build();

            HttpResponse<String> response = HTTP.send(
                    request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
            );

            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("HTTP " + response.statusCode());
            }

            List<Rule> parsed = parseRules(response.body());
            if (parsed.isEmpty()) {
                throw new IllegalStateException("no enabled rules found");
            }

            cachedRules = List.copyOf(parsed);
            cachedAt = now;
            lastLoadMessage = "loaded " + cachedRules.size() + " enabled rules from MM Case Reminder Rules";
            return cachedRules;

        } catch (Exception e) {
            if (cachedRules.isEmpty()) {
                cachedRules = defaultRules();
            }
            cachedAt = now;
            lastLoadMessage = "Sheet read failed (" + e.getMessage() + ") - using last/built-in rules";
            return cachedRules;
        }
    }

    private static List<Rule> parseRules(String csv) {
        List<List<String>> rows = parseCsv(csv);
        if (rows.isEmpty()) {
            return List.of();
        }

        Map<String, Integer> header = new LinkedHashMap<>();
        int headerRow = -1;
        for (int r = 0; r < Math.min(rows.size(), 10); r++) {
            Map<String, Integer> candidate = new LinkedHashMap<>();
            List<String> cells = rows.get(r);
            for (int i = 0; i < cells.size(); i++) {
                candidate.put(normalizeHeader(cells.get(i)), i);
            }
            if (findColumn(candidate, "service") >= 0
                    && findColumn(candidate, "remarks syntax", "remark syntax", "remarks", "syntax") >= 0) {
                header = candidate;
                headerRow = r;
                break;
            }
        }

        int serviceCol = findColumn(header, "service");
        int optionCol = findColumn(header, "remark option", "remarks option", "option", "sub service", "subservice");
        int syntaxCol = findColumn(header, "remarks syntax", "remark syntax", "remarks", "syntax");
        int actionCol = findColumn(header, "action code", "action", "code");
        int typeCol = findColumn(header, "type");
        int enabledCol = findColumn(header, "enabled", "enable", "active");

        if (serviceCol < 0 || syntaxCol < 0) {
            throw new IllegalStateException(
                    "Rules tab '" + safe(config.getProperty("case.rules.sheet.name", ""))
                            + "' needs Service and Remarks Syntax headers in its first 10 rows; first row was "
                            + rows.get(0).stream().limit(8).map(CaseRuleService::normalizeHeader).toList()
            );
        }

        List<Rule> rules = new ArrayList<>();
        for (int r = headerRow + 1; r < rows.size(); r++) {
            List<String> row = rows.get(r);
            String service = cell(row, serviceCol);
            String option = cell(row, optionCol);
            String syntax = cell(row, syntaxCol);
            String action = cell(row, actionCol);
            String type = cell(row, typeCol);
            String enabledRaw = cell(row, enabledCol);

            if (service.isBlank() || syntax.isBlank()) {
                continue;
            }

            boolean enabled = enabledRaw.isBlank()
                    || !(enabledRaw.equalsIgnoreCase("NO")
                    || enabledRaw.equalsIgnoreCase("FALSE")
                    || enabledRaw.equals("0")
                    || enabledRaw.equalsIgnoreCase("OFF")
                    || enabledRaw.equalsIgnoreCase("DISABLED"));

            if (!enabled) {
                continue;
            }

            rules.add(new Rule(
                    service.trim(),
                    option.trim(),
                    syntax.trim(),
                    normalizeAction(action),
                    type.isBlank() ? "TEXT" : type.trim().toUpperCase(Locale.ROOT),
                    true
            ));
        }

        return rules;
    }

    private static List<List<String>> parseCsv(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }

        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean inQuotes = false;

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);

            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    field.append(c);
                }
                continue;
            }

            if (c == '"') {
                inQuotes = true;
            } else if (c == ',') {
                row.add(field.toString());
                field.setLength(0);
            } else if (c == '\n') {
                row.add(field.toString());
                field.setLength(0);
                rows.add(row);
                row = new ArrayList<>();
            } else if (c != '\r') {
                field.append(c);
            }
        }

        row.add(field.toString());
        boolean nonBlank = false;
        for (String value : row) {
            if (!safe(value).isBlank()) {
                nonBlank = true;
                break;
            }
        }
        if (nonBlank) {
            rows.add(row);
        }

        return rows;
    }

    /**
     * Accepts the normal Google Sheet URL and, when sheetName is supplied,
     * reads that tab by name.  This avoids depending on a changing numeric gid.
     */
    public static String normalizeGoogleSheetUrl(String rawUrl, String sheetName) {
        String url = safe(rawUrl).trim();
        String tab = safe(sheetName).trim();

        if (!tab.isBlank()) {
            Matcher m = GOOGLE_EDIT_SHEET.matcher(url);
            if (m.matches()) {
                String sheetId = m.group(1);
                return "https://docs.google.com/spreadsheets/d/"
                        + sheetId
                        + "/gviz/tq?tqx=out:csv&sheet="
                        + java.net.URLEncoder.encode(tab, StandardCharsets.UTF_8);
            }
        }

        return normalizeGoogleSheetUrl(url);
    }

    /** Accepts either the normal Google Sheet edit URL or a published/export CSV URL. */
    public static String normalizeGoogleSheetUrl(String rawUrl) {
        String url = safe(rawUrl).trim();
        if (url.isBlank()) {
            return url;
        }

        if (url.contains("output=csv") || url.contains("format=csv")) {
            return url;
        }

        Matcher m = GOOGLE_EDIT_SHEET.matcher(url);
        if (m.matches()) {
            String sheetId = m.group(1);
            String gid = "0";
            Matcher gidMatcher = Pattern.compile("(?:[?&#]gid=)(\\d+)", Pattern.CASE_INSENSITIVE).matcher(url);
            if (gidMatcher.find()) {
                gid = gidMatcher.group(1);
            }
            return "https://docs.google.com/spreadsheets/d/"
                    + sheetId
                    + "/export?format=csv&gid="
                    + gid;
        }

        return url;
    }

    private static List<Rule> defaultRules() {
        List<Rule> rules = new ArrayList<>();
        rules.add(new Rule("Passport Fresh", "Appointment Reminder", "REM : dd/mm/yyyy @ {LOCATION}", ACTION_REMINDER, "LOCATION", true));
        rules.add(new Rule("SID Card Fresh", "Appointment Reminder", "REM : dd/mm/yyyy @ {LOCATION}", ACTION_REMINDER, "LOCATION", true));
        rules.add(new Rule("US Visa", "Appointment Reminder", "REM : dd/mm/yyyy @ {LOCATION}", ACTION_REMINDER, "LOCATION", true));
        rules.add(new Rule("JSU", "Retirement Claim", "JSU Retirement Claim\nRPP NO : <number>", ACTION_JSU_RETIREMENT, "TEXT", true));
        rules.add(new Rule("JSU", "Balance Query", "JSU Balance Query", ACTION_JSU_BALANCE, "TEXT", true));
        rules.add(new Rule("Seaman Provident Fund", "SPFO Balance", "SPFO Balance\nUser Id : <userid>\nPassword : <password>", ACTION_SPFO_BALANCE, "TEXT", true));
        rules.add(new Rule("Seaman Provident Fund", "SPFO Initial A/C Problem", "SPFO Initial A/C Problem", ACTION_SPFO_INITIAL_MAIL, "TEXT", true));
        rules.add(new Rule("Seaman Provident Fund", "SPFO Interest Problem", "SPFO Interest Problem", ACTION_SPFO_INTEREST_MAIL, "TEXT", true));
        rules.add(new Rule("Seaman Provident Fund", "SPFO Balance Problem", "SPFO Balance Problem", ACTION_SPFO_BALANCE_MAIL, "TEXT", true));
        rules.add(new Rule("Seaman Provident Fund", "SPFO Retirement Claim", "SPFO Retirement Claim", ACTION_SPFO_RETIREMENT, "TEXT", true));
        return Collections.unmodifiableList(rules);
    }

    private static int findColumn(Map<String, Integer> header, String... names) {
        for (String name : names) {
            Integer value = header.get(normalizeHeader(name));
            if (value != null) {
                return value;
            }
        }
        return -1;
    }

    private static String cell(List<String> row, int index) {
        if (index < 0 || index >= row.size()) {
            return "";
        }
        return safe(row.get(index));
    }

    private static String normalizeHeader(String value) {
        return safe(value)
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static String normalizeAction(String action) {
        return safe(action)
                .trim()
                .toUpperCase(Locale.ROOT)
                .replaceAll("[^A-Z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
    }

    private static String normalize(String value) {
        return safe(value)
                .toLowerCase(Locale.ROOT)
                .replace("\\n", " ")
                .replaceAll("[^a-z0-9]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static String stripPlaceholders(String syntax) {
        return safe(syntax)
                .replaceAll("<[^>]+>", " ")
                .replaceAll("\\{[^}]+}", " ")
                .replaceAll("(?i)dd/mm/yyyy", " ")
                .replace("\\n", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static String safe(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    public record Rule(
            String service,
            String remarkOption,
            String remarksSyntax,
            String actionCode,
            String type,
            boolean enabled
    ) {
    }
}
