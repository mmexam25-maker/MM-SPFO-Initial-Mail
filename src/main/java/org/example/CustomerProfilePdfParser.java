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

/** Reads the DG Shipping Seafarer Profile PDF without OCR. */
public class CustomerProfilePdfParser {

    private static final Pattern NUMBERED_DOCUMENT = Pattern.compile(
            "(?i)^\\s*\\d+\\s*\\.\\s*(?:Document|Certificate)\\s*Type\\s*:\\s*(.+?)\\s*$");
    private static final Pattern DOCUMENT_TYPE = Pattern.compile(
            "(?i)^\\s*(?:Document|Certificate)\\s*Type\\s*:\\s*(.+?)\\s*$");
    private static final Pattern NUMBER_AND_ISSUE = Pattern.compile(
            "(?i)^\\s*(?:Document|Certificate)\\s*Number\\s*:\\s*(.*?)\\s+(?:Issued\\s*Date|Date\\s*of\\s*Issue)\\s*:\\s*(.*?)\\s*$");
    private static final Pattern VALID_AND_ISSUED_AT = Pattern.compile(
            "(?i)^\\s*(?:Valid\\s*Upto|Expiry\\s*Date|Date\\s*of\\s*Expiry)\\s*:\\s*(.*?)\\s+(?:Issued\\s*At|Place\\s*of\\s*Issue)\\s*:\\s*(.*?)\\s*$");

    public CustomerProfileData parse(Path pdf) throws IOException {
        String text;
        try (PDDocument document = Loader.loadPDF(pdf.toFile())) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            text = stripper.getText(document);
        }
        return parseText(text);
    }

    CustomerProfileData parseText(String text) {
        String normal = text == null ? "" : text.replace('\u00A0', ' ');
        normal = joinSplitLabelValues(normal);

        String indos = firstLabel(normal,
                "INDoS No\\.?", "INDOS No\\.?", "INDoS Number", "INDOS Number");
        String surname = firstLabel(normal,
                "Surname / Last Name", "Surname", "Last Name");
        String givenName = firstLabel(normal, "Given Name", "First Name");
        String email = firstLabel(normal, "Email Id", "Email ID", "Email Address", "Email");
        String phone = firstLabel(normal, "Mobile No\\.?", "Mobile Number", "Phone", "Telephone No\\.?");
        String city = firstLabel(normal, "City");
        String state = firstLabel(normal, "State");
        String country = firstLabel(normal, "Country");
        String dob = isoDate(firstLabel(normal,
                "Date of Birth", "Date Of Birth", "DOB", "Birth Date"));
        String father = firstLabel(normal,
                "Father(?:'s)? Name", "Father Name", "Name of Father",
                "Father / Guardian Name", "Father/Guardian Name", "Parent Name");
        // Mariners Mentor requirement: when DG does not expose a father name,
        // use DG Surname / Last Name as the Father Name fallback. Existing MM
        // values are still never overwritten by the updater.
        if (blank(father)) father = surname;
        String passport = sanitizeDocumentNumber(firstLabel(normal,
                "Passport No\\.?", "Passport Number"));
        String height = firstLabel(normal,
                "Height \\(in Cms\\)", "Height \\(cm\\)", "Height");
        String weight = firstLabel(normal,
                "Weight \\(in Kgs\\)", "Weight \\(kg\\)", "Weight");

        List<DocRow> rows = parseDocumentRows(normal);

        CustomerProfileData.DocumentInfo cdc = latest(rows, "CDC");
        CustomerProfileData.DocumentInfo sid = latest(rows, "SID", "SEAFARER IDENTITY DOCUMENT", "SEAFARERS IDENTITY DOCUMENT");
        CustomerProfileData.DocumentInfo cop = latest(rows, "COP", "CERTIFICATE OF PROFICIENCY");
        CustomerProfileData.DocumentInfo coc = latest(rows, "COC", "CERTIFICATE OF COMPETENCY");

        // Some DG profile versions print these outside the generic Document table.
        if (cop.isEmpty()) cop = findStandaloneCertificate(normal, "COP", "Certificate of Proficiency");
        if (coc.isEmpty()) coc = findStandaloneCertificate(normal, "COC", "Certificate of Competency");

        // Passport is often inside the Authorised Documents table instead of Personal Details.
        if (blank(passport)) {
            CustomerProfileData.DocumentInfo pass = latest(rows, "PASSPORT", "INDIAN PASSPORT");
            passport = sanitizeDocumentNumber(pass.number());
        }

        SeaSummary sea = parseSeaServiceSummary(normal);

        return new CustomerProfileData(
                clean(indos), clean(dob), clean(father), clean(passport), numeric(clean(height)), numeric(clean(weight)),
                cdc, sid, cop, coc,
                clean(sea.vesselName), clean(sea.rpsl), clean(sea.role), clean(sea.history),
                clean(givenName), clean(surname), clean(email), clean(phone),
                clean(city), clean(state), clean(country)
        );
    }

    /**
     * DG's printable PDF frequently renders a label and its colon/value on
     * separate lines, for example:
     *   Date of Birth
     *   : 09/01/1999
     * Merge those pairs so the normal label/document parsers work reliably.
     */
    private String joinSplitLabelValues(String text) {
        String[] lines = text.split("\\R", -1);
        StringBuilder out = new StringBuilder(text.length());

        for (int i = 0; i < lines.length; i++) {
            String current = lines[i].replace('\u00A0', ' ').trim();
            if (current.isEmpty()) continue;

            int j = i + 1;
            while (j < lines.length && lines[j].trim().isEmpty()) j++;

            if (!current.startsWith(":") && j < lines.length) {
                String next = lines[j].replace('\u00A0', ' ').trim();
                if (next.startsWith(":")) {
                    out.append(current).append(' ').append(next).append('\n');
                    i = j;
                    continue;
                }
            }

            out.append(current).append('\n');
        }
        return out.toString();
    }

    private List<DocRow> parseDocumentRows(String text) {
        String[] lines = text.split("\\R");
        List<DocRow> out = new ArrayList<>();
        DocRow current = null;

        for (String raw : lines) {
            String line = raw.replace('\u00A0', ' ').trim();
            if (line.isEmpty()) continue;

            Matcher numbered = NUMBERED_DOCUMENT.matcher(line);
            if (numbered.matches()) {
                if (current != null && !blank(current.type)) out.add(current);
                current = new DocRow();
                current.type = clean(numbered.group(1));
                continue;
            }

            Matcher type = DOCUMENT_TYPE.matcher(line);
            if (type.matches()) {
                String value = clean(type.group(1));
                if (current == null) current = new DocRow();
                if (blank(current.type)) current.type = value;
                continue;
            }

            if (current == null) continue;

            Matcher numIssue = NUMBER_AND_ISSUE.matcher(line);
            if (numIssue.matches()) {
                current.number = clean(numIssue.group(1));
                current.issued = isoDate(clean(numIssue.group(2)));
                continue;
            }

            Matcher validAt = VALID_AND_ISSUED_AT.matcher(line);
            if (validAt.matches()) {
                current.expiry = isoDate(clean(validAt.group(1)));
                continue;
            }

            // One-label-per-line layouts.
            String v;
            v = labelFromLine(line, "Document Number", "Certificate Number", "COC Number", "COP Number");
            if (!blank(v)) { current.number = clean(v); continue; }
            v = labelFromLine(line, "Issued Date", "Date of Issue", "Issued / Renewed Date");
            if (!blank(v)) { current.issued = isoDate(clean(v)); continue; }
            v = labelFromLine(line, "Valid Upto", "Expiry Date", "Date of Expiry");
            if (!blank(v)) current.expiry = isoDate(clean(v));
        }

        if (current != null && !blank(current.type)) out.add(current);
        return out;
    }

    private CustomerProfileData.DocumentInfo latest(List<DocRow> rows, String... names) {
        List<String> wanted = Arrays.stream(names).map(this::normalType).toList();
        return rows.stream()
                .filter(r -> wanted.stream().anyMatch(w -> typeMatches(normalType(r.type), w)))
                .max(Comparator.comparing(r -> parseIsoOrMin(r.issued)))
                .map(r -> new CustomerProfileData.DocumentInfo(clean(r.number), clean(r.issued), clean(r.expiry)))
                .orElse(CustomerProfileData.DocumentInfo.empty());
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

    private CustomerProfileData.DocumentInfo findStandaloneCertificate(String text, String shortName, String longName) {
        String escapedLong = Pattern.quote(longName);
        String escapedShort = Pattern.quote(shortName);
        Pattern block = Pattern.compile(
                "(?is)(?:" + escapedLong + "|\\b" + escapedShort + "\\b)(.{0,1000}?)(?=\\n\\s*[A-Z][A-Za-z ]{3,}:|$)");
        Matcher m = block.matcher(text);
        CustomerProfileData.DocumentInfo best = CustomerProfileData.DocumentInfo.empty();
        LocalDate bestDate = LocalDate.MIN;
        while (m.find()) {
            String b = m.group(1);
            String no = firstLabel(b, "Certificate Number", shortName + " Number", "Certificate No\\.?");
            String issued = isoDate(firstLabel(b, "Issued Date", "Date of Issue", "Issued / Renewed Date"));
            String expiry = isoDate(firstLabel(b, "Valid Upto", "Expiry Date", "Date of Expiry"));
            if (!blank(no)) {
                LocalDate d = parseIsoOrMin(issued);
                if (best.isEmpty() || d.isAfter(bestDate)) {
                    best = new CustomerProfileData.DocumentInfo(clean(no), clean(issued), clean(expiry));
                    bestDate = d;
                }
            }
        }
        return best;
    }


    /**
     * Some DG printable profiles include Sea Going Service rows.  The exact
     * layout varies, so this parser is intentionally label-driven.  If the
     * current printable profile has no sea-service section, all values remain
     * blank and the MM form updater simply leaves Vessel/RPSL/Role untouched.
     */
    private SeaSummary parseSeaServiceSummary(String text) {
        if (blank(text)) return new SeaSummary();

        Pattern start = Pattern.compile("(?im)^\\s*(?:Ship|Vessel)\\s*Name\\s*:\\s*([^\\r\\n]+)");
        Matcher m = start.matcher(text);
        List<SeaRow> rows = new ArrayList<>();
        List<Integer> starts = new ArrayList<>();
        List<String> ships = new ArrayList<>();
        while (m.find()) {
            starts.add(m.start());
            ships.add(clean(m.group(1)));
        }

        for (int i = 0; i < starts.size(); i++) {
            int from = starts.get(i);
            int to = (i + 1 < starts.size()) ? starts.get(i + 1) : Math.min(text.length(), from + 5000);
            String block = text.substring(from, to);

            SeaRow row = new SeaRow();
            row.vessel = ships.get(i);
            row.rpsl = firstLabel(block,
                    "RPSL Name", "RPSL No\\.?", "RPSL Number", "RPSL", "Company / RPSL", "Company Name");
            row.role = firstLabel(block, "Rank", "Role", "Capacity", "Designation");
            row.from = isoDate(firstLabel(block,
                    "Service From \\(Date\\)", "Service From", "From Date", "Sign On Date"));
            row.to = isoDate(firstLabel(block,
                    "Service To \\(Date\\)", "Service To", "To Date", "Sign Off Date"));

            if (!blank(row.vessel) || !blank(row.rpsl) || !blank(row.role)) rows.add(row);
        }

        if (rows.isEmpty()) return new SeaSummary();

        SeaRow latest = rows.stream().max(Comparator
                .comparing((SeaRow r) -> parseIsoOrMin(r.to))
                .thenComparing(r -> parseIsoOrMin(r.from)))
                .orElse(rows.get(rows.size() - 1));

        StringBuilder history = new StringBuilder();
        for (SeaRow row : rows) {
            if (row == latest) continue;
            String item = historyLine(row);
            if (blank(item)) continue;
            if (history.length() > 0) history.append(" ; ");
            history.append(item);
            // Avoid putting an enormous history into one MM text field.
            if (history.length() > 1200) break;
        }

        SeaSummary out = new SeaSummary();
        out.vesselName = latest.vessel;
        out.rpsl = latest.rpsl;
        out.role = latest.role;
        out.history = history.toString();
        return out;
    }

    private String historyLine(SeaRow r) {
        List<String> bits = new ArrayList<>();
        if (!blank(r.rpsl)) bits.add("RPSL " + clean(r.rpsl));
        if (!blank(r.role)) bits.add("Role " + clean(r.role));
        if (!blank(r.vessel)) bits.add("Vessel " + clean(r.vessel));
        String dates = "";
        if (!blank(r.from)) dates = displayDate(r.from);
        if (!blank(r.to)) dates = dates + (dates.isBlank() ? "" : " - ") + displayDate(r.to);
        if (!dates.isBlank()) bits.add(dates);
        return String.join(" | ", bits);
    }

    private String displayDate(String iso) {
        try {
            return LocalDate.parse(iso).format(DateTimeFormatter.ofPattern("dd/MM/uuuu"));
        } catch (Exception e) {
            return clean(iso);
        }
    }

    private static final class SeaRow {
        String vessel = "";
        String rpsl = "";
        String role = "";
        String from = "";
        String to = "";
    }

    private static final class SeaSummary {
        String vesselName = "";
        String rpsl = "";
        String role = "";
        String history = "";
    }

    private String firstLabel(String text, String... labels) {
        for (String label : labels) {
            Pattern p = Pattern.compile("(?im)^\\s*" + label + "\\s*:\\s*([^\\r\\n]*?)\\s*(?:(?:[A-Za-z][A-Za-z /().'-]{2,})\\s*:|$)");
            Matcher m = p.matcher(text);
            if (m.find()) {
                String value = clean(m.group(1));
                if (!blank(value)) return value;
            }
            // More permissive fallback for a simple single field line.
            p = Pattern.compile("(?im)^\\s*" + label + "\\s*:\\s*([^\\r\\n]+)$");
            m = p.matcher(text);
            if (m.find()) {
                String value = clean(m.group(1));
                if (!blank(value)) return value;
            }
        }
        return "";
    }

    private String labelFromLine(String line, String... labels) {
        for (String label : labels) {
            Matcher m = Pattern.compile("(?i)^\\s*" + Pattern.quote(label) + "\\s*:\\s*(.*?)\\s*$").matcher(line);
            if (m.matches()) return m.group(1);
        }
        return "";
    }

    static String isoDate(String input) {
        if (blank(input)) return "";
        String s = clean(input).replace('.', '/');
        List<DateTimeFormatter> fmts = List.of(
                DateTimeFormatter.ofPattern("d/M/uuuu"),
                DateTimeFormatter.ofPattern("d-M-uuuu"),
                DateTimeFormatter.ofPattern("dd-MMM-uuuu", Locale.ENGLISH),
                DateTimeFormatter.ofPattern("d-MMM-uuuu", Locale.ENGLISH),
                DateTimeFormatter.ISO_LOCAL_DATE
        );
        for (DateTimeFormatter f : fmts) {
            try { return LocalDate.parse(s, f).toString(); }
            catch (DateTimeParseException ignored) {}
        }
        Matcher m = Pattern.compile("(\\d{1,2})[/-](\\d{1,2})[/-](\\d{4})").matcher(s);
        if (m.find()) {
            try {
                return LocalDate.of(Integer.parseInt(m.group(3)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(1))).toString();
            } catch (Exception ignored) {}
        }
        return "";
    }

    private LocalDate parseIsoOrMin(String iso) {
        try { return LocalDate.parse(iso); }
        catch (Exception e) { return LocalDate.MIN; }
    }

    private String normalType(String s) {
        return clean(s).toUpperCase(Locale.ROOT)
                .replaceAll("[^A-Z0-9]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }


    private String sanitizeDocumentNumber(String raw) {
        String value = clean(raw);
        if (blank(value)) return "";
        String x = value.replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
        if (x.matches("^(?:null|undefined|nil|none|n/?a|not provided|not available|no data|-|--)$")) return "";
        if (x.matches("^(?:height|weight|hair colou?r|eye colou?r|complexion|identification marks?)\\b.*")) return "";
        return value;
    }

    private String numeric(String s) {
        if (blank(s)) return "";
        Matcher m = Pattern.compile("\\d+(?:\\.\\d+)?").matcher(s);
        return m.find() ? m.group() : "";
    }

    private static String clean(String s) {
        if (s == null) return "";
        return s.replace('\u00A0', ' ').replaceAll("\\s+", " ").trim();
    }

    private static boolean blank(String s) { return s == null || s.trim().isEmpty(); }

    private static final class DocRow {
        String type = "";
        String number = "";
        String issued = "";
        String expiry = "";
    }
}
