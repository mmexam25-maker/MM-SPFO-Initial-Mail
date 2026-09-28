package org.example;

import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.sheets.v4.Sheets;
import com.google.api.services.sheets.v4.SheetsScopes;
import com.google.api.services.sheets.v4.model.AddSheetRequest;
import com.google.api.services.sheets.v4.model.BatchUpdateSpreadsheetRequest;
import com.google.api.services.sheets.v4.model.BatchUpdateValuesRequest;
import com.google.api.services.sheets.v4.model.Request;
import com.google.api.services.sheets.v4.model.Sheet;
import com.google.api.services.sheets.v4.model.SheetProperties;
import com.google.api.services.sheets.v4.model.Spreadsheet;
import com.google.api.services.sheets.v4.model.ValueRange;
import com.google.auth.http.HttpCredentialsAdapter;
import com.google.auth.oauth2.GoogleCredentials;

import java.io.FileInputStream;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Normalized Google-Sheet writer.
 *
 * New design requested by admin:
 *   1) Customer Profile  -> one row per customer
 *   2) Course Details    -> one row per course
 *   3) Sea Service       -> one row per sea-going-service entry
 *
 * Customer ID is the primary join key on all three tabs. INDoS is retained as a
 * secondary reference. Existing non-blank cells are never overwritten.
 */
public final class ResumeEntrySheetService {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private static final List<Object> CUSTOMER_HEADERS = List.of(
            "Customer ID", "INDoS No", "INDoS Password", "Given Name", "Surname", "Last Name",
            "Father Name", "DOB", "Email", "Mobile No", "Alt Mobile No", "Address", "City", "State",
            "Country", "Pincode", "Place of Birth", "Height (cm)", "Weight (kg)", "Hair Color",
            "Eye Color", "Complexion", "Identification Mark",
            "Passport No", "Passport Issue Date", "Passport Expiry Date", "Passport Place of Issue",
            "CDC No", "CDC Flag", "CDC Issue Date", "CDC Expiry Date", "CDC Place of Issue",
            "SID No", "SID Issue Date", "SID Expiry Date", "SID Place of Issue",
            "CoP No", "CoP Issue Date", "CoP Expiry Date", "CoP Place of Issue",
            "Watchkeeping Type", "WK No",
            "CoC No", "CoC Issue Date", "CoC Expiry Date", "CoC Place of Issue",
            "Latest Role", "Latest Vessel Name", "Latest RPSL Name",
            "Created By", "Created At", "Updated At"
    );

    private static final List<Object> COURSE_HEADERS = List.of(
            "Customer ID", "INDoS No", "Customer Name", "Mobile No", "Created By", "Course No",
            "Course Name", "STCW Code", "Training Institute", "Attended From", "Attended To",
            "Certificate No", "Date of Issue", "Valid Upto / Expiry Date",
            "eLearning Start Date", "eLearning End Date", "Source", "Updated At",
            "Digital Certificate Link"
    );

    private static final List<Object> SEA_HEADERS = List.of(
            "Customer ID", "INDoS No", "Customer Name", "Created By", "Service No", "RPSL Name",
            "Vessel Name", "Official No", "IMO No", "Flag", "Port of Registry", "Ship Type", "GT",
            "Trade Area", "Rank", "Nature of Watch", "Service From", "Service To", "Article Months",
            "Article Days", "Propulsion Power (KW)", "Propulsion Type", "Propelling Days", "Remarks",
            "Updated At"
    );

    private final boolean enabled;
    private final String spreadsheetId;
    private final String customerSheetName;
    private final String courseSheetName;
    private final String seaSheetName;
    private final String serviceAccountFile;

    private Sheets sheets;

    private boolean customerIndexLoaded;
    private final Map<String, Integer> customerIdToRow = new HashMap<>();
    private final Map<Integer, List<Object>> customerRows = new HashMap<>();
    private int nextCustomerRow = 2;

    private boolean courseIndexLoaded;
    private final Map<String, Integer> courseKeyToRow = new HashMap<>();
    private final Map<Integer, List<Object>> courseRows = new HashMap<>();
    private int nextCourseRow = 2;

    private boolean seaIndexLoaded;
    private final Map<String, Integer> seaKeyToRow = new HashMap<>();
    private final Map<Integer, List<Object>> seaRows = new HashMap<>();
    private int nextSeaRow = 2;
    private long seaSheetGid = 0L;
    private long customerSheetGid = 0L;

    public ResumeEntrySheetService(Properties config) {
        this.enabled = bool(config, "resume.entry.sheet.enabled", true);
        this.spreadsheetId = value(config,
                "resume.entry.spreadsheet.id",
                "17yfYvWzZHqeeW8cqohgd6NMZXuVDc4-QhVtbcdCJbfA");
        this.customerSheetName = value(config, "resume.entry.customer.sheet.name", "Customer Profile");
        this.courseSheetName = value(config, "resume.entry.course.sheet.name", "Course Details");
        this.seaSheetName = value(config, "resume.entry.sea.sheet.name", "Sea Service");
        this.serviceAccountFile = value(config, "resume.entry.service.account.file", "service-account.json");
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Creates/repairs the three normalized Membership tabs and their headers.
     * Safe to call at every startup. It does not insert candidate data.
     */
    public synchronized void ensureStructure() throws Exception {
        if (!enabled) return;
        ensureCustomerIndexLoaded();
        ensureCourseIndexLoaded();
        ensureSeaIndexLoaded();
    }

    /** Returns true when Customer Profile currently contains this Customer ID. */
    public synchronized boolean hasCustomer(String customerId) throws Exception {
        if (!enabled || blank(customerId)) return false;
        ensureCustomerIndexLoaded();
        return customerIdToRow.containsKey(customerId.trim());
    }

    public synchronized SheetWriteResult upsert(
            String customerId,
            String createdBy,
            String createdAt,
            String indosPassword,
            ResumeEntryData data
    ) throws Exception {
        if (!enabled) return SheetWriteResult.disabled();
        if (blank(customerId)) throw new IllegalArgumentException("Customer Profile requires Customer ID");
        if (data == null) throw new IllegalArgumentException("Customer Profile data is missing");

        ensureCustomerIndexLoaded();
        ensureCourseIndexLoaded();
        ensureSeaIndexLoaded();

        String customerKey = customerId.trim();
        Integer existingCustomerRow = customerIdToRow.get(customerKey);
        boolean newCustomerRow = existingCustomerRow == null;
        int customerRow = newCustomerRow ? nextCustomerRow++ : existingCustomerRow;

        ResumeEntryData.VesselInfo latest = data.latestVessel();
        String updatedAt = now();
        List<Object> desiredCustomer = customerRowValues(
                customerId, createdBy, createdAt, indosPassword, data, latest, updatedAt);

        int profileCells = upsertRowBlankOnly(
                customerSheetName,
                customerRow,
                newCustomerRow ? Collections.emptyList() : customerRows.getOrDefault(customerRow, Collections.emptyList()),
                desiredCustomer,
                newCustomerRow
        );
        customerRows.put(customerRow, mergeCustomerRowsRepairInvalid(
                customerRows.getOrDefault(customerRow, Collections.emptyList()), desiredCustomer));
        customerIdToRow.put(customerKey, customerRow);

        int courseCells = upsertCourses(customerId, createdBy, data, updatedAt);
        SeaWriteResult seaResult = upsertSeaService(customerId, createdBy, data, updatedAt);

        return new SheetWriteResult(
                true,
                customerRow,
                newCustomerRow,
                profileCells + courseCells + seaResult.cellsWritten,
                sheetRowLink(customerSheetGid, customerRow, CUSTOMER_HEADERS.size()),
                Collections.unmodifiableList(seaResult.rowLinks)
        );
    }

    private List<Object> customerRowValues(
            String customerId,
            String createdBy,
            String createdAt,
            String indosPassword,
            ResumeEntryData d,
            ResumeEntryData.VesselInfo latest,
            String updatedAt
    ) {
        ResumeEntryData.PassportInfo pass = d.passport();
        ResumeEntryData.DocInfo cdc = d.cdc();
        ResumeEntryData.DocInfo sid = d.sid();
        ResumeEntryData.DocInfo cop = d.cop();
        ResumeEntryData.DocInfo coc = d.coc();

        return List.of(
                nvl(customerId), nvl(d.indosNo()), nvl(indosPassword), nvl(d.givenName()), nvl(d.surname()), nvl(d.lastName()),
                nvl(d.fatherName()), nvl(d.dob()), nvl(d.email()), nvl(d.mobile()), nvl(d.altMobile()), nvl(d.address()),
                nvl(d.city()), nvl(d.state()), nvl(d.country()), nvl(d.pincode()), nvl(d.placeOfBirth()), nvl(d.height()),
                nvl(d.weight()), nvl(d.hairColor()), nvl(d.eyeColor()), nvl(d.complexion()), nvl(d.identificationMark()),
                nvl(pass.number()), nvl(pass.issueDate()), nvl(pass.expiryDate()), nvl(pass.placeOfIssue()),
                nvl(cdc.number()), nvl(d.cdcFlag()), nvl(cdc.issueDate()), nvl(cdc.expiryDate()), nvl(cdc.placeOfIssue()),
                nvl(sid.number()), nvl(sid.issueDate()), nvl(sid.expiryDate()), nvl(sid.placeOfIssue()),
                nvl(cop.number()), nvl(cop.issueDate()), nvl(cop.expiryDate()), nvl(cop.placeOfIssue()),
                nvl(d.watchkeepingType()), nvl(d.wkNo()),
                nvl(coc.number()), nvl(coc.issueDate()), nvl(coc.expiryDate()), nvl(coc.placeOfIssue()),
                nvl(latest.rank()), nvl(latest.vesselName()), nvl(latest.companyName()),
                nvl(createdBy), nvl(createdAt), updatedAt
        );
    }

    private int upsertCourses(String customerId, String createdBy, ResumeEntryData data, String updatedAt) throws Exception {
        if (data.courses() == null || data.courses().isEmpty()) return 0;
        int written = 0;
        String customerName = customerName(data);

        for (int i = 0; i < data.courses().size(); i++) {
            ResumeEntryData.CourseInfo c = data.courses().get(i);
            if (c == null || c.isEmpty()) continue;

            String key = courseKey(customerId, c);
            Integer existingRow = courseKeyToRow.get(key);
            boolean newRow = existingRow == null;
            int row = newRow ? nextCourseRow++ : existingRow;

            List<Object> values = List.of(
                    nvl(customerId), nvl(data.indosNo()), customerName, nvl(data.mobile()), nvl(createdBy), i + 1,
                    nvl(c.name()), nvl(c.stcwCode()), nvl(c.trainingInstitute()), nvl(c.attendedFrom()), nvl(c.attendedTo()),
                    nvl(c.certificateNo()), nvl(c.issueDate()), nvl(c.expiryDate()), nvl(c.elearningStartDate()),
                    nvl(c.elearningEndDate()), blank(c.source()) ? "DG Profile" : c.source(), updatedAt,
                    nvl(c.digitalCertificateLink())
            );

            written += upsertRowBlankOnly(
                    courseSheetName, row,
                    newRow ? Collections.emptyList() : courseRows.getOrDefault(row, Collections.emptyList()),
                    values, newRow);
            courseRows.put(row, mergeRows(courseRows.getOrDefault(row, Collections.emptyList()), values));
            courseKeyToRow.put(key, row);
        }
        return written;
    }

    private SeaWriteResult upsertSeaService(
            String customerId,
            String createdBy,
            ResumeEntryData data,
            String updatedAt
    ) throws Exception {
        if (data.vessels() == null || data.vessels().isEmpty()) {
            return new SeaWriteResult(0, Collections.emptyList());
        }

        int written = 0;
        List<String> links = new ArrayList<>();
        String customerName = customerName(data);

        for (int i = 0; i < data.vessels().size(); i++) {
            ResumeEntryData.VesselInfo v = data.vessels().get(i);
            if (v == null || v.isEmpty()) continue;

            String key = seaKey(customerId, v);
            Integer existingRow = seaKeyToRow.get(key);
            if (existingRow == null) {
                existingRow = findUniqueLooseSeaRow(customerId, v);
            }
            boolean newRow = existingRow == null;
            int row = newRow ? nextSeaRow++ : existingRow;

            List<Object> values = List.of(
                    nvl(customerId), nvl(data.indosNo()), customerName, nvl(createdBy), i + 1,
                    nvl(v.companyName()), nvl(v.vesselName()), nvl(v.officialNo()), nvl(v.imoNo()), nvl(v.flag()),
                    nvl(v.portOfRegistry()), nvl(v.vesselType()), nvl(v.grt()), nvl(v.tradeArea()), nvl(v.rank()),
                    nvl(v.natureOfWatch()), nvl(v.serviceFrom()), nvl(v.serviceTo()), nvl(v.articleMonths()),
                    nvl(v.articleDays()), nvl(v.propulsionPowerKw()), nvl(v.propulsionType()), nvl(v.propellingDays()),
                    nvl(v.remarks()), updatedAt
            );

            // Sea Service is official DG/RPSL data. On a re-check, refresh any
            // non-blank DG values so corrected RPSL/vessel/rank/dates are reflected.
            // Blank source values never erase an existing sheet value.
            written += upsertRowRefreshNonBlank(
                    seaSheetName, row,
                    newRow ? Collections.emptyList() : seaRows.getOrDefault(row, Collections.emptyList()),
                    values, newRow);
            seaRows.put(row, mergeRowsPreferDesired(
                    seaRows.getOrDefault(row, Collections.emptyList()), values));
            seaKeyToRow.put(key, row);
            links.add(sheetRowLink(seaSheetGid, row, SEA_HEADERS.size()));
        }

        if (!links.isEmpty()) {
            System.out.println("SEA SERVICE UPDATED | Customer ID: " + customerId
                    + " | Rows: " + links.size());
        }
        return new SeaWriteResult(written, links);
    }

    /** New row = one row write. Existing row = fill blanks only. */
    private int upsertRowBlankOnly(
            String sheet,
            int row,
            List<Object> existing,
            List<Object> desired,
            boolean newRow
    ) throws Exception {
        if (newRow) {
            service().spreadsheets().values()
                    .update(spreadsheetId,
                            a1(sheet, "A" + row + ":" + columnLetter(desired.size()) + row),
                            new ValueRange().setValues(List.of(desired)))
                    .setValueInputOption("USER_ENTERED")
                    .execute();
            int nonBlank = 0;
            for (Object value : desired) if (!blank(value == null ? "" : String.valueOf(value))) nonBlank++;
            return nonBlank;
        }

        List<ValueRange> updates = new ArrayList<>();
        for (int col = 1; col <= desired.size(); col++) {
            Object wanted = desired.get(col - 1);
            String want = wanted == null ? "" : String.valueOf(wanted).trim();
            String current = cell(existing, col);

            // Customer Profile used to have a neighbour-reading bug that could
            // write placeholders such as "Weight Null" into Passport No.
            // Treat those known junk placeholders as blank. If DG has a real
            // value, replace the junk; if DG also has no value, clear the junk.
            if (sheet.equals(customerSheetName) && isInvalidProfilePlaceholder(current)) {
                updates.add(new ValueRange()
                        .setRange(a1(sheet, columnLetter(col) + row))
                        .setValues(List.of(List.of(want))));
                continue;
            }

            if (blank(want)) continue;
            if (!blank(current)) continue;
            updates.add(new ValueRange()
                    .setRange(a1(sheet, columnLetter(col) + row))
                    .setValues(List.of(List.of(wanted))));
        }
        if (!updates.isEmpty()) {
            service().spreadsheets().values().batchUpdate(
                    spreadsheetId,
                    new BatchUpdateValuesRequest().setValueInputOption("USER_ENTERED").setData(updates)
            ).execute();
        }
        return updates.size();
    }

    /**
     * Sea-service refresh mode: official DG values are allowed to replace an
     * existing value when the new source is non-blank. A blank source never
     * clears the sheet. Customer Profile/Course Details keep blank-only behavior.
     */
    private int upsertRowRefreshNonBlank(
            String sheet,
            int row,
            List<Object> existing,
            List<Object> desired,
            boolean newRow
    ) throws Exception {
        if (newRow) {
            service().spreadsheets().values()
                    .update(spreadsheetId,
                            a1(sheet, "A" + row + ":" + columnLetter(desired.size()) + row),
                            new ValueRange().setValues(List.of(desired)))
                    .setValueInputOption("USER_ENTERED")
                    .execute();
            int nonBlank = 0;
            for (Object value : desired) if (!blank(value == null ? "" : String.valueOf(value))) nonBlank++;
            return nonBlank;
        }

        List<ValueRange> updates = new ArrayList<>();
        for (int col = 1; col <= desired.size(); col++) {
            Object wanted = desired.get(col - 1);
            String want = wanted == null ? "" : String.valueOf(wanted).trim();
            if (blank(want)) continue;

            String current = cell(existing, col);
            if (want.equals(current)) continue;

            updates.add(new ValueRange()
                    .setRange(a1(sheet, columnLetter(col) + row))
                    .setValues(List.of(List.of(wanted))));
        }

        if (!updates.isEmpty()) {
            service().spreadsheets().values().batchUpdate(
                    spreadsheetId,
                    new BatchUpdateValuesRequest().setValueInputOption("USER_ENTERED").setData(updates)
            ).execute();
        }
        return updates.size();
    }

    private synchronized void ensureCustomerIndexLoaded() throws Exception {
        if (customerIndexLoaded) return;
        customerSheetGid = ensureSheet(customerSheetName, CUSTOMER_HEADERS);
        ValueRange vr = service().spreadsheets().values()
                .get(spreadsheetId, a1(customerSheetName, "A2:AZ"))
                .execute();
        List<List<Object>> rows = vr.getValues();
        if (rows != null) {
            for (int i = 0; i < rows.size(); i++) {
                int row = i + 2;
                List<Object> values = rows.get(i);
                customerRows.put(row, new ArrayList<>(values));
                String id = cell(values, 1);
                if (!blank(id)) customerIdToRow.putIfAbsent(id.trim(), row);
            }
            nextCustomerRow = rows.size() + 2;
        }
        customerIndexLoaded = true;
        System.out.println("CUSTOMER PROFILE SHEET READY | " + customerSheetName
                + " | Existing customers: " + customerIdToRow.size()
                + " | Next row: " + nextCustomerRow);
    }

    private synchronized void ensureCourseIndexLoaded() throws Exception {
        if (courseIndexLoaded) return;
        ensureSheet(courseSheetName, COURSE_HEADERS);
        ValueRange vr = service().spreadsheets().values()
                .get(spreadsheetId, a1(courseSheetName, "A2:S"))
                .execute();
        List<List<Object>> rows = vr.getValues();
        if (rows != null) {
            for (int i = 0; i < rows.size(); i++) {
                int row = i + 2;
                List<Object> values = rows.get(i);
                courseRows.put(row, new ArrayList<>(values));
                String customerId = cell(values, 1);
                String name = cell(values, 7);
                String cert = cell(values, 12);
                String issue = cell(values, 13);
                String elearningStart = cell(values, 15);
                if (!blank(customerId) && !blank(name)) {
                    courseKeyToRow.putIfAbsent(
                            courseKey(customerId, name, cert, issue, elearningStart), row);
                }
            }
            nextCourseRow = rows.size() + 2;
        }
        courseIndexLoaded = true;
        System.out.println("COURSE DETAILS SHEET READY | " + courseSheetName
                + " | Existing rows: " + courseKeyToRow.size()
                + " | Next row: " + nextCourseRow);
    }

    private synchronized void ensureSeaIndexLoaded() throws Exception {
        if (seaIndexLoaded) return;
        seaSheetGid = ensureSheet(seaSheetName, SEA_HEADERS);
        ValueRange vr = service().spreadsheets().values()
                .get(spreadsheetId, a1(seaSheetName, "A2:Y"))
                .execute();
        List<List<Object>> rows = vr.getValues();
        if (rows != null) {
            for (int i = 0; i < rows.size(); i++) {
                int row = i + 2;
                List<Object> values = rows.get(i);
                seaRows.put(row, new ArrayList<>(values));
                String customerId = cell(values, 1);
                String vessel = cell(values, 7);
                String rank = cell(values, 15);
                String from = cell(values, 17);
                String to = cell(values, 18);
                if (!blank(customerId) && !blank(vessel)) {
                    seaKeyToRow.putIfAbsent(seaKey(customerId, vessel, rank, from, to), row);
                }
            }
            nextSeaRow = rows.size() + 2;
        }
        seaIndexLoaded = true;
        System.out.println("SEA SERVICE SHEET READY | " + seaSheetName
                + " | Existing rows: " + seaKeyToRow.size()
                + " | Next row: " + nextSeaRow);
    }

    private long ensureSheet(String sheetName, List<Object> headers) throws Exception {
        Spreadsheet ss = service().spreadsheets().get(spreadsheetId)
                .setIncludeGridData(false)
                .execute();

        Sheet found = findSheet(ss, sheetName);
        if (found == null) {
            Request add = new Request().setAddSheet(
                    new AddSheetRequest().setProperties(new SheetProperties().setTitle(sheetName)));
            service().spreadsheets().batchUpdate(
                    spreadsheetId,
                    new BatchUpdateSpreadsheetRequest().setRequests(List.of(add))
            ).execute();
            System.out.println("SHEET CREATED | " + sheetName);
            ss = service().spreadsheets().get(spreadsheetId)
                    .setIncludeGridData(false)
                    .execute();
            found = findSheet(ss, sheetName);
        }
        if (found == null || found.getProperties() == null || found.getProperties().getSheetId() == null) {
            throw new IllegalStateException("Could not create/find Google Sheet tab: " + sheetName);
        }

        ValueRange header = service().spreadsheets().values()
                .get(spreadsheetId, a1(sheetName, "A1:" + columnLetter(headers.size()) + "1"))
                .execute();
        boolean needsHeader = !headerMatches(header, headers);
        if (needsHeader) {
            service().spreadsheets().values()
                    .update(
                            spreadsheetId,
                            a1(sheetName, "A1:" + columnLetter(headers.size()) + "1"),
                            new ValueRange().setValues(List.of(headers)))
                    .setValueInputOption("RAW")
                    .execute();
            System.out.println("SHEET HEADERS WRITTEN | " + sheetName);
        }
        return found.getProperties().getSheetId().longValue();
    }

    private boolean headerMatches(ValueRange existingHeader, List<Object> expected) {
        if (existingHeader == null || existingHeader.getValues() == null
                || existingHeader.getValues().isEmpty()) return false;
        List<Object> row = existingHeader.getValues().get(0);
        if (row == null || row.size() < expected.size()) return false;
        for (int i = 0; i < expected.size(); i++) {
            String actual = row.get(i) == null ? "" : String.valueOf(row.get(i)).trim();
            String wanted = expected.get(i) == null ? "" : String.valueOf(expected.get(i)).trim();
            if (!actual.equalsIgnoreCase(wanted)) return false;
        }
        return true;
    }

    private Sheet findSheet(Spreadsheet ss, String title) {
        if (ss == null || ss.getSheets() == null) return null;
        for (Sheet sheet : ss.getSheets()) {
            if (sheet.getProperties() != null && title.equals(sheet.getProperties().getTitle())) return sheet;
        }
        return null;
    }

    private String courseKey(String customerId, ResumeEntryData.CourseInfo c) {
        return courseKey(customerId, c.name(), c.certificateNo(), c.issueDate(), c.elearningStartDate());
    }

    private String courseKey(String customerId, String name, String cert, String issue, String elearningStart) {
        return normalizeKey(customerId, name, cert, issue, elearningStart);
    }

    private String seaKey(String customerId, ResumeEntryData.VesselInfo v) {
        return seaKey(customerId, v.vesselName(), v.rank(), v.serviceFrom(), v.serviceTo());
    }

    private String seaKey(String customerId, String vessel, String rank, String from, String to) {
        return normalizeKey(customerId, vessel, rank, from, to);
    }

    /**
     * Safe fallback for older Sea Service rows whose dates were previously
     * missing/different. Reuse the row only when Customer ID + Vessel + Rank
     * identifies exactly one existing row; otherwise create a new row rather
     * than risk overwriting a different voyage on the same vessel.
     */
    private Integer findUniqueLooseSeaRow(String customerId, ResumeEntryData.VesselInfo v) {
        if (blank(customerId) || v == null || blank(v.vesselName())) return null;
        String wantedCustomer = normalizeKey(customerId);
        String wantedVessel = normalizeKey(v.vesselName());
        String wantedRank = normalizeKey(v.rank());
        Integer found = null;

        for (Map.Entry<Integer, List<Object>> entry : seaRows.entrySet()) {
            List<Object> row = entry.getValue();
            if (!normalizeKey(cell(row, 1)).equals(wantedCustomer)) continue;
            if (!normalizeKey(cell(row, 7)).equals(wantedVessel)) continue;

            String existingRank = normalizeKey(cell(row, 15));
            if (!wantedRank.isBlank() && !existingRank.isBlank() && !existingRank.equals(wantedRank)) continue;

            if (found != null) return null; // ambiguous: more than one voyage
            found = entry.getKey();
        }
        return found;
    }

    private String normalizeKey(String... parts) {
        return String.join("|", Arrays.stream(parts).map(this::nvl).toList())
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9|]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private String customerName(ResumeEntryData d) {
        return (nvl(d.givenName()) + " " + nvl(d.surname())).replaceAll("\\s+", " ").trim();
    }


    private List<Object> mergeCustomerRowsRepairInvalid(List<Object> current, List<Object> desired) {
        List<Object> merged = new ArrayList<>(current == null ? Collections.emptyList() : current);
        while (merged.size() < desired.size()) merged.add("");
        for (int i = 0; i < desired.size(); i++) {
            Object wanted = desired.get(i);
            String cur = merged.get(i) == null ? "" : String.valueOf(merged.get(i)).trim();
            String want = wanted == null ? "" : String.valueOf(wanted).trim();
            if (isInvalidProfilePlaceholder(cur)) {
                merged.set(i, want);
            } else if (cur.isEmpty() && !want.isEmpty()) {
                merged.set(i, wanted);
            }
        }
        return merged;
    }

    private boolean isInvalidProfilePlaceholder(String value) {
        if (value == null) return false;
        String x = value.replace('\u00A0', ' ')
                .replaceAll("\\s+", " ")
                .trim()
                .toLowerCase(Locale.ROOT);
        if (x.isEmpty()) return false;
        if (x.matches("^(?:null|undefined|nil|none|n/?a|not provided|not available|no data|-|--)$")) {
            return true;
        }
        return x.matches("^(?:height|weight|hair colou?r|eye colou?r|complexion|identification marks?|passport(?: number| no\\.?)?|cdc(?: number| no\\.?)?|sid(?: number| no\\.?)?|bsid(?: number| no\\.?)?|cop(?: number| no\\.?)?|coc(?: number| no\\.?)?)\\s+(?:null|undefined|nil|none|n/?a|not provided|not available|no data|-|--)$");
    }

    private List<Object> mergeRows(List<Object> current, List<Object> desired) {
        List<Object> merged = new ArrayList<>(current == null ? Collections.emptyList() : current);
        while (merged.size() < desired.size()) merged.add("");
        for (int i = 0; i < desired.size(); i++) {
            Object wanted = desired.get(i);
            String cur = merged.get(i) == null ? "" : String.valueOf(merged.get(i)).trim();
            String want = wanted == null ? "" : String.valueOf(wanted).trim();
            if (cur.isEmpty() && !want.isEmpty()) merged.set(i, wanted);
        }
        return merged;
    }

    private List<Object> mergeRowsPreferDesired(List<Object> current, List<Object> desired) {
        List<Object> merged = new ArrayList<>(current == null ? Collections.emptyList() : current);
        while (merged.size() < desired.size()) merged.add("");
        for (int i = 0; i < desired.size(); i++) {
            Object wanted = desired.get(i);
            String want = wanted == null ? "" : String.valueOf(wanted).trim();
            if (!want.isEmpty()) merged.set(i, wanted);
        }
        return merged;
    }

    private String sheetRowLink(long gid, int row, int lastCol) {
        return "https://docs.google.com/spreadsheets/d/" + spreadsheetId
                + "/edit#gid=" + gid + "&range=A" + row + ":" + columnLetter(lastCol) + row;
    }

    private String a1(String sheet, String range) {
        return "'" + nvl(sheet).replace("'", "''") + "'!" + range;
    }

    private String cell(List<Object> row, int oneBasedCol) {
        int idx = oneBasedCol - 1;
        if (row == null || idx < 0 || idx >= row.size()) return "";
        Object v = row.get(idx);
        return v == null ? "" : String.valueOf(v).trim();
    }

    private String columnLetter(int n) {
        StringBuilder s = new StringBuilder();
        int x = n;
        while (x > 0) {
            x--;
            s.append((char) ('A' + (x % 26)));
            x /= 26;
        }
        return s.reverse().toString();
    }

    private String now() {
        return LocalDateTime.now(IST).format(DateTimeFormatter.ofPattern("dd/MM/uuuu HH:mm"));
    }

    private Sheets service() throws Exception {
        if (sheets != null) return sheets;
        synchronized (this) {
            if (sheets != null) return sheets;
            try (FileInputStream in = new FileInputStream(serviceAccountFile)) {
                GoogleCredentials credentials = GoogleCredentials.fromStream(in)
                        .createScoped(Collections.singleton(SheetsScopes.SPREADSHEETS));
                sheets = new Sheets.Builder(
                        GoogleNetHttpTransport.newTrustedTransport(),
                        GsonFactory.getDefaultInstance(),
                        new HttpCredentialsAdapter(credentials))
                        .setApplicationName("Mariners Mentor Customer DG Normalized Sync")
                        .build();
            }
            return sheets;
        }
    }

    private boolean bool(Properties p, String key, boolean fallback) {
        String v = p.getProperty(key);
        return blank(v) ? fallback : Boolean.parseBoolean(v.trim());
    }

    private String value(Properties p, String key, String fallback) {
        String v = p.getProperty(key);
        return blank(v) ? fallback : v.trim();
    }

    private boolean blank(String s) { return s == null || s.trim().isEmpty(); }
    private String nvl(String s) { return s == null ? "" : s; }

    private record SeaWriteResult(int cellsWritten, List<String> rowLinks) {}

    public record SheetWriteResult(
            boolean enabled,
            int row,
            boolean newRow,
            int cellsFilled,
            String rowLink,
            List<String> vesselCellLinks
    ) {
        public static SheetWriteResult disabled() {
            return new SheetWriteResult(false, 0, false, 0, "", Collections.emptyList());
        }

        public String commaSeparatedVesselLinks() {
            if (vesselCellLinks == null || vesselCellLinks.isEmpty()) return "";
            return String.join(",", vesselCellLinks);
        }
    }
}
