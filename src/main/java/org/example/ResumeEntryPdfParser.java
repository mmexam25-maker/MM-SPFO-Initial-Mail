package org.example;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses the DG Shipping printable Seafarer Profile into normalized data.
 * Only values explicitly present in DG are used; absent values remain blank.
 */
public final class ResumeEntryPdfParser {

    private static final Pattern DOC_START = Pattern.compile(
            "(?im)^\\s*(?:\\d+\\s*\\.\\s*)?Document\\s+Type\\s*:\\s*(.+?)\\s*$");
    private static final Pattern COURSE_START = Pattern.compile(
            "(?im)^\\s*(\\d+)\\s*\\.\\s*Course\\s+Name\\s*:\\s*(.+?)\\s*$");
    private static final Pattern VESSEL_START = Pattern.compile(
            "(?im)^\\s*\\d+\\s*\\.\\s*(?:Ship|Vessel)\\s+Name\\s*:\\s*(.+?)\\s*$");

    public ResumeEntryData parse(Path pdf) throws IOException {
        String text;
        try (PDDocument document = Loader.loadPDF(pdf.toFile())) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            text = stripper.getText(document);
        }
        return parseText(text);
    }

    ResumeEntryData parseText(String source) {
        String text = normalize(source);

        String surname = firstLabel(text, "Surname / Last Name", "Surname", "Last Name");
        String given = firstLabel(text, "Given Name", "First Name");
        String father = firstLabel(text, "Father Name", "Father's Name");
        String email = firstLabel(text, "Email Id", "Email ID", "Email Address", "Email");
        String mobile = firstLabel(text, "Mobile No\\.?", "Mobile Number", "Phone");
        String altMobile = firstLabel(text, "Alternate Mobile No\\.?", "Alt Mobile No\\.?", "Alternate Phone");
        String dob = displayDate(firstLabel(text, "Date of Birth", "Date Of Birth", "DOB"));
        String city = presentAddressField(text, "City");
        String state = presentAddressField(text, "State");
        String country = presentAddressField(text, "Country");
        String pin = presentAddressField(text, "Pin", "Pincode", "Pin Code");
        String placeOfBirth = firstLabel(text, "Place of Birth", "Place Of Birth");
        String height = physicalLineValue(text, "Height \\(in Cms\\)", "Height \\(cm\\)", "Height");
        String weight = physicalLineValue(text, "Weight \\(in Kgs\\)", "Weight \\(kg\\)", "Weight");
        String hairColor = physicalLineValue(text, "Hair Color", "Hair Colour");
        String eyeColor = physicalLineValue(text, "Eye Color", "Eye Colour");
        String complexion = physicalLineValue(text, "Complexion");
        String identificationMark = firstLabel(text, "Identification Mark", "Identification Marks");
        String indos = firstLabel(text, "INDoS No\\.?", "INDOS No\\.?", "INDoS Number", "INDOS Number");
        String address = readPresentAddress(text);

        List<DocumentRow> documents = parseDocuments(text);
        DocumentRow passport = latestDocument(documents, "PASSPORT");
        DocumentRow cdc = latestDocument(documents, "CDC");
        DocumentRow sid = latestDocument(documents, "SID", "SEAMAN IDENTIFICATION DOCUMENT",
                "SEAFARER IDENTITY DOCUMENT", "SEAFARERS IDENTITY DOCUMENT");
        DocumentRow cop = latestDocument(documents, "COP", "CERTIFICATE OF PROFICIENCY");
        DocumentRow coc = latestDocument(documents, "COC", "CERTIFICATE OF COMPETENCY");

        List<ResumeEntryData.CourseInfo> courses = parseCourses(text);
        List<ResumeEntryData.VesselInfo> vessels = parseVessels(text);

        String cdcFlag = firstLabel(text, "CDC Flag", "CDC Country");
        String watchkeepingType = firstLabel(text,
                "Type of Watchkeeping", "Watchkeeping Type", "Type of Watch Keeping");
        String wkNo = firstLabel(text,
                "WK No\\.?", "Watchkeeping No\\.?", "Watch Keeping No\\.?");

        return new ResumeEntryData(
                clean(surname), clean(given), "", clean(father), clean(email),
                clean(mobile), clean(altMobile), clean(dob), clean(address), clean(city),
                clean(state), clean(country), clean(pin), clean(placeOfBirth), clean(height),
                clean(weight), clean(hairColor), clean(eyeColor), clean(complexion),
                clean(identificationMark), clean(indos), toPassport(passport), toDoc(cdc),
                toDoc(sid), toDoc(cop), toDoc(coc), clean(cdcFlag), clean(watchkeepingType),
                clean(wkNo), courses, vessels
        );
    }

    private List<DocumentRow> parseDocuments(String text) {
        List<DocumentRow> out = new ArrayList<>();
        Matcher m = DOC_START.matcher(text);
        List<Integer> starts = new ArrayList<>();
        List<String> types = new ArrayList<>();
        while (m.find()) {
            starts.add(m.start());
            types.add(clean(m.group(1)));
        }

        for (int i = 0; i < starts.size(); i++) {
            int from = starts.get(i);
            int to = (i + 1 < starts.size()) ? starts.get(i + 1) : text.length();
            String block = text.substring(from, to);

            DocumentRow row = new DocumentRow();
            row.type = types.get(i);
            row.number = firstLabel(block,
                    "Document Number", "Certificate Number", "Certificate No\\.?", "Document No\\.?");
            row.issueDate = displayDate(firstLabel(block,
                    "Issued Date", "Date of Issue", "Date Of Issue", "Issued / Renewed Date"));
            row.expiryDate = displayDate(firstLabel(block,
                    "Valid Upto", "Expiry Date", "Date of Expiry", "Date Of Expiry"));
            row.placeOfIssue = firstLabel(block,
                    "Issued At", "Place of Issue", "Place Of Issue");

            if (blank(row.number) || blank(row.issueDate)) {
                Matcher ni = Pattern.compile(
                        "(?im)^\\s*(?:Document|Certificate)\\s+Number\\s*:\\s*(.*?)\\s+"
                                + "(?:Issued\\s+Date|Date\\s+of\\s+Issue)\\s*:\\s*(.*?)\\s*$")
                        .matcher(block);
                if (ni.find()) {
                    if (blank(row.number)) row.number = clean(ni.group(1));
                    if (blank(row.issueDate)) row.issueDate = displayDate(ni.group(2));
                }
            }
            if (blank(row.expiryDate) || blank(row.placeOfIssue)) {
                Matcher ep = Pattern.compile(
                        "(?im)^\\s*(?:Valid\\s+Upto|Expiry\\s+Date|Date\\s+of\\s+Expiry)\\s*:\\s*(.*?)\\s+"
                                + "(?:Issued\\s+At|Place\\s+of\\s+Issue)\\s*:\\s*(.*?)\\s*$")
                        .matcher(block);
                if (ep.find()) {
                    if (blank(row.expiryDate)) row.expiryDate = displayDate(ep.group(1));
                    if (blank(row.placeOfIssue)) row.placeOfIssue = clean(ep.group(2));
                }
            }
            if (!blank(row.type)) out.add(row);
        }
        return out;
    }

    private List<ResumeEntryData.CourseInfo> parseCourses(String text) {
        List<ResumeEntryData.CourseInfo> out = new ArrayList<>();
        Matcher m = COURSE_START.matcher(text);
        List<Integer> starts = new ArrayList<>();
        List<String> names = new ArrayList<>();
        while (m.find()) {
            starts.add(m.start());
            names.add(clean(m.group(2)));
        }

        for (int i = 0; i < starts.size() && out.size() < 100; i++) {
            int from = starts.get(i);
            int to = (i + 1 < starts.size()) ? starts.get(i + 1) : text.length();
            String block = text.substring(from, to);

            String name = courseLineValue(block, "Course Name");
            if (blank(name)) name = names.get(i);
            String stcwCode = courseLineValue(block, "STCW Code");
            String trainingInstitute = courseLineValue(block, "Training Institute");
            String attendedFrom = displayDate(courseLineValue(block, "Attended From"));
            String attendedTo = displayDate(courseLineValue(block, "Attended To"));
            String certificate = courseLineValue(block, "Certificate No\\.?", "Certificate Number");
            String issueDate = displayDate(courseLineValue(block, "Date of Issue", "Date Of Issue"));
            String expiryDate = displayDate(courseLineValue(block, "Valid Upto", "Date of Expiry", "Date Of Expiry"));
            String validity = courseLineValue(block, "Validity");
            String place = courseLineValue(block, "Place of Issue", "Place Of Issue", "Issued At");

            ResumeEntryData.CourseInfo info = new ResumeEntryData.CourseInfo(
                    clean(name), clean(certificate), clean(issueDate), clean(expiryDate),
                    clean(validity), clean(place), clean(stcwCode), clean(trainingInstitute),
                    clean(attendedFrom), clean(attendedTo), "", "", "DG Profile", "");
            if (!info.isEmpty()) out.add(info);
        }
        return dedupeCourses(out);
    }

    private List<ResumeEntryData.CourseInfo> dedupeCourses(List<ResumeEntryData.CourseInfo> input) {
        LinkedHashMap<String, ResumeEntryData.CourseInfo> unique = new LinkedHashMap<>();
        for (ResumeEntryData.CourseInfo c : input) {
            String key = (nvl(c.name()) + "|" + nvl(c.certificateNo()) + "|" + nvl(c.issueDate())
                    + "|" + nvl(c.elearningStartDate()) + "|" + nvl(c.elearningEndDate()))
                    .replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
            if (key.replace("|", "").isBlank()) continue;
            unique.putIfAbsent(key, c);
        }
        return new ArrayList<>(unique.values());
    }

    private List<ResumeEntryData.VesselInfo> parseVessels(String text) {
        List<ResumeEntryData.VesselInfo> out = new ArrayList<>();
        Matcher m = VESSEL_START.matcher(text);
        List<Integer> starts = new ArrayList<>();
        List<String> vesselNames = new ArrayList<>();
        while (m.find()) {
            starts.add(m.start());
            vesselNames.add(clean(m.group(1)));
        }

        for (int i = 0; i < starts.size() && out.size() < 100; i++) {
            int from = starts.get(i);
            int to = (i + 1 < starts.size()) ? starts.get(i + 1) : Math.min(text.length(), from + 9000);
            String block = text.substring(from, to);

            String company = seaLineValue(block,
                    "RPSL Name", "Company Name", "Company / RPSL", "Shipping Company", "Employer");
            String vessel = vesselNames.get(i);
            if (blank(vessel)) vessel = seaLineValue(block, "Ship Name", "Vessel Name");

            ResumeEntryData.VesselInfo info = new ResumeEntryData.VesselInfo(
                    clean(company), clean(vessel),
                    clean(seaLineValue(block, "Official No\\.?", "Official Number")),
                    clean(seaLineValue(block, "IMO Number", "IMO No\\.?")),
                    clean(seaLineValue(block, "Flag")),
                    clean(seaLineValue(block, "Port of Registry")),
                    clean(seaLineValue(block, "Ship Type", "Vessel Type", "Type of Ship", "Type Of Ship")),
                    clean(seaLineValue(block, "GT", "GRT", "Gross Tonnage", "Gross Registered Tonnage")),
                    clean(seaLineValue(block, "Trade Area")),
                    clean(seaLineValue(block, "Rank", "Role", "Capacity", "Designation")),
                    clean(seaLineValue(block, "Nature of watch keeping", "Nature of watch", "Nature of Watch")),
                    clean(displayDate(seaLineValue(block, "Service From \\(Date\\)", "Service From"))),
                    clean(displayDate(seaLineValue(block, "Service To \\(Date\\)", "Service To"))),
                    clean(seaLineValue(block, "Article of Months", "Article Months")),
                    clean(seaLineValue(block, "Article Days")),
                    clean(seaLineValue(block, "Propulsion power\\(KW\\)", "Propulsion Power \\(KW\\)", "Propulsion Power")),
                    clean(seaLineValue(block, "Propulsion Type")),
                    clean(seaLineValue(block, "Propelling days", "Propelling Days")),
                    clean(seaLineValue(block, "Remarks"))
            );
            if (!info.isEmpty()) out.add(info);
        }
        return dedupeVessels(out);
    }

    private List<ResumeEntryData.VesselInfo> dedupeVessels(List<ResumeEntryData.VesselInfo> input) {
        LinkedHashMap<String, ResumeEntryData.VesselInfo> unique = new LinkedHashMap<>();
        for (ResumeEntryData.VesselInfo v : input) {
            String key = (nvl(v.companyName()) + "|" + nvl(v.vesselName()) + "|" + nvl(v.rank())
                    + "|" + nvl(v.serviceFrom()) + "|" + nvl(v.serviceTo()))
                    .replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
            if (key.replace("|", "").isBlank()) continue;
            unique.putIfAbsent(key, v);
        }
        return new ArrayList<>(unique.values());
    }

    private DocumentRow latestDocument(List<DocumentRow> rows, String... wantedNames) {
        List<String> wanted = Arrays.stream(wantedNames).map(this::normalType).toList();
        return rows.stream()
                .filter(r -> wanted.stream().anyMatch(w -> typeMatches(normalType(r.type), w)))
                .max(Comparator.comparing(r -> parseDateOrMin(r.issueDate)))
                .orElse(new DocumentRow());
    }

    private boolean typeMatches(String actual, String wanted) {
        if (actual.equals(wanted)) return true;
        if (wanted.equals("CDC")) return actual.matches(".*\\bCDC\\b.*");
        if (wanted.equals("SID")) return actual.matches(".*\\bSID\\b.*");
        if (wanted.equals("COP")) return actual.matches(".*\\bCOP\\b.*") && !actual.contains("COC");
        if (wanted.equals("COC")) return actual.matches(".*\\bCOC\\b.*");
        if (wanted.contains("PASSPORT")) return actual.contains("PASSPORT");
        return actual.contains(wanted) || wanted.contains(actual);
    }

    private ResumeEntryData.PassportInfo toPassport(DocumentRow r) {
        if (r == null) return ResumeEntryData.PassportInfo.empty();
        return new ResumeEntryData.PassportInfo(
                clean(r.number), clean(r.placeOfIssue), clean(r.issueDate), clean(r.expiryDate));
    }

    private ResumeEntryData.DocInfo toDoc(DocumentRow r) {
        if (r == null) return ResumeEntryData.DocInfo.empty();
        return new ResumeEntryData.DocInfo(
                clean(r.number), clean(r.issueDate), clean(r.expiryDate), clean(r.placeOfIssue));
    }

    private String readPresentAddress(String text) {
        try {
            Matcher section = Pattern.compile("(?is)Address\\s+Details(.*?)(?:Physical\\s+Details|Education\\s+Details)")
                    .matcher(text);
            if (!section.find()) return "";
            String block = section.group(1);
            List<String> parts = new ArrayList<>();
            for (String raw : block.split("\\R")) {
                String line = raw.replace('\u00A0', ' ').trim();
                if (line.isEmpty()) continue;
                if (line.matches("(?i).*Present\\s+Address.*")) continue;
                if (line.matches("(?i).*Permanent\\s+Address.*")) continue;
                if (line.matches("(?i)^Address\\s*:.*")) {
                    String v = line.replaceFirst("(?i)^Address\\s*:\\s*", "").trim();
                    v = cutAtSecondLabel(v, "Address");
                    if (!blank(v)) parts.add(v);
                    continue;
                }
                if (line.matches("(?i)^\\s*:\\s*.*")) {
                    String v = line.replaceFirst("^\\s*:\\s*", "").trim();
                    v = cutAtSecondLabel(v, "Address");
                    if (!blank(v)) parts.add(v);
                    continue;
                }
                if (line.matches("(?i)^(City|Pin|State|Country|Telephone|Mobile|Email)\\b.*")) break;
            }
            return clean(String.join(", ", parts));
        } catch (Exception ignored) {
            return "";
        }
    }

    private String presentAddressField(String text, String... labels) {
        try {
            Matcher section = Pattern.compile("(?is)Address\\s+Details(.*?)(?:Physical\\s+Details|Education\\s+Details)")
                    .matcher(text);
            if (!section.find()) return "";
            String block = section.group(1);
            for (String label : labels) {
                Matcher m = Pattern.compile("(?im)^\\s*" + label + "\\s*:\\s*([^\\r\\n]*)$").matcher(block);
                if (!m.find()) continue;
                String tail = m.group(1) == null ? "" : m.group(1).trim();
                tail = cutAtSecondLabel(tail, label);
                String v = clean(tail);
                if (!blank(v)) return v;
            }
        } catch (Exception ignored) {}
        return "";
    }

    private String cutAtSecondLabel(String tail, String currentLabel) {
        if (blank(tail)) return "";
        Matcher second = Pattern.compile(
                "(?i)\\s{2,}(?:Address|City|Pin|State|Country|Telephone\\s+No\\.?|Mobile\\s+No\\.?|Email\\s+Id)\\s*:")
                .matcher(tail);
        return second.find() ? tail.substring(0, second.start()).trim() : tail.trim();
    }

    private String physicalLineValue(String text, String... labels) {
        String nextLabel = "(?:Height\\s*\\([^)]*\\)|Weight\\s*\\([^)]*\\)|Hair\\s+Colou?r|Eye\\s+Colou?r|Complexion|Identification\\s+Marks?)";
        for (String label : labels) {
            Matcher m = Pattern.compile(
                    "(?im)^[^\r\n]*?" + label + "[ \t]*:[ \t]*([^\r\n]*)$").matcher(text);
            if (!m.find()) continue;
            String tail = m.group(1) == null ? "" : m.group(1).trim();
            Matcher boundary = Pattern.compile("(?i)[ \t]+" + nextLabel + "[ \t]*:").matcher(tail);
            if (boundary.find()) tail = tail.substring(0, boundary.start()).trim();
            return clean(tail);
        }
        return "";
    }

    private String seaLineValue(String text, String... labels) {
        String nextLabel =
                "(?:Ship\\s+Name|Vessel\\s+Name|Official\\s+(?:No\\.?|Number)|IMO\\s+(?:Number|No\\.?)|"
                        + "GT|GRT|Rank|Role|Nature\\s+of\\s+watch(?:\\s+keeping)?|Flag|Port\\s+of\\s+Registry|"
                        + "Ship\\s+Type|Vessel\\s+Type|Trade\\s+Area|Service\\s+From(?:\\s*\\(Date\\))?|"
                        + "Service\\s+To(?:\\s*\\(Date\\))?|Article\\s+of\\s+Months|Article\\s+Months|"
                        + "Article\\s+Days|Propulsion\\s+power\\s*\\(KW\\)|Propulsion\\s+Power|"
                        + "Propulsion\\s+Type|Propelling\\s+days|Remarks|RPSL\\s+Name|Company\\s+Name|"
                        + "Company\\s*/\\s*RPSL|Shipping\\s+Company|Employer)";
        for (String label : labels) {
            Matcher m = Pattern.compile(
                    "(?im)^[^\\r\\n]*?" + label + "[ \\t]*:[ \\t]*([^\\r\\n]*)$").matcher(text);
            if (!m.find()) continue;
            String tail = m.group(1) == null ? "" : m.group(1).trim();
            Matcher boundary = Pattern.compile("(?i)[ \\t]+" + nextLabel + "[ \\t]*:").matcher(tail);
            if (boundary.find()) tail = tail.substring(0, boundary.start()).trim();
            return clean(tail);
        }
        return "";
    }

    private String courseLineValue(String text, String... labels) {
        String nextLabel =
                "(?:Course\\s+Name|Training\\s+Institute|STCW\\s+Code|Attended\\s+From|Attended\\s+To|"
                        + "Certificate\\s+(?:No\\.?|Number)|Date\\s+of\\s+Issue|Valid\\s+Upto|"
                        + "Date\\s+of\\s+Expiry|Validity|Place\\s+of\\s+Issue|Issued\\s+At)";
        for (String label : labels) {
            Matcher m = Pattern.compile(
                    "(?im)^[^\\r\\n]*?" + label + "[ \\t]*:[ \\t]*([^\\r\\n]*)$").matcher(text);
            if (!m.find()) continue;
            String tail = m.group(1) == null ? "" : m.group(1).trim();
            Matcher boundary = Pattern.compile("(?i)[ \\t]+" + nextLabel + "[ \\t]*:").matcher(tail);
            if (boundary.find()) tail = tail.substring(0, boundary.start()).trim();
            String value = clean(tail);
            if (!blank(value)) return value;
            return "";
        }
        return "";
    }

    private String firstLabel(String text, String... labels) {
        for (String label : labels) {
            Pattern sameLine = Pattern.compile(
                    "(?im)^\\s*" + label + "\\s*:\\s*([^\\r\\n]*?)\\s*(?:(?:[A-Za-z][A-Za-z /().'-]{2,})\\s*:|$)");
            Matcher m = sameLine.matcher(text);
            if (m.find()) {
                String v = clean(m.group(1));
                if (!blank(v)) return v;
            }
            Pattern simple = Pattern.compile("(?im)^\\s*" + label + "\\s*:\\s*([^\\r\\n]+)$");
            m = simple.matcher(text);
            if (m.find()) {
                String v = clean(m.group(1));
                if (!blank(v)) return v;
            }
        }
        return "";
    }

    private String normalize(String s) {
        if (s == null) return "";
        return s.replace('\u00A0', ' ')
                .replace("\r\n", "\n")
                .replace('\r', '\n');
    }

    private String displayDate(String input) {
        String iso = CustomerProfilePdfParser.isoDate(clean(input));
        if (blank(iso)) return "";
        try {
            return LocalDate.parse(iso).format(DateTimeFormatter.ofPattern("dd/MM/uuuu"));
        } catch (Exception ignored) {
            return clean(input);
        }
    }

    private LocalDate parseDateOrMin(String value) {
        if (blank(value)) return LocalDate.MIN;
        List<DateTimeFormatter> fmts = List.of(
                DateTimeFormatter.ofPattern("d/M/uuuu"),
                DateTimeFormatter.ofPattern("d-M-uuuu"),
                DateTimeFormatter.ISO_LOCAL_DATE
        );
        for (DateTimeFormatter f : fmts) {
            try { return LocalDate.parse(value.trim(), f); }
            catch (DateTimeParseException ignored) {}
        }
        return LocalDate.MIN;
    }

    private String normalType(String s) {
        return clean(s).toUpperCase(Locale.ROOT)
                .replaceAll("[^A-Z0-9]+", " ")
                .replaceAll("\\s+", " ").trim();
    }

    private String clean(String s) {
        if (s == null) return "";
        String v = s.replace('\u00A0', ' ')
                .replaceAll("\\s+", " ")
                .replaceAll("^[:;,-]+|[:;,-]+$", "")
                .trim();
        if (v.equalsIgnoreCase("null") || v.equalsIgnoreCase("undefined") || v.equals("-")) return "";
        return v;
    }

    private boolean blank(String s) { return s == null || s.trim().isEmpty(); }
    private String nvl(String s) { return s == null ? "" : s; }

    private static final class DocumentRow {
        String type = "";
        String number = "";
        String issueDate = "";
        String expiryDate = "";
        String placeOfIssue = "";
    }
}
