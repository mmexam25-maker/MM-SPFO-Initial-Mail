package org.example;

import org.apache.poi.ss.usermodel.*;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;

/** Reads Customer IDs from the dashboard Customers Export file. */
public final class CustomerExportReader {
    private static final Pattern DIGITS = Pattern.compile("^\\d+$");

    private CustomerExportReader() {}

    public static List<String> readCustomerIds(Path file) throws IOException {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".xlsx") || name.endsWith(".xls")) return readExcel(file);
        if (name.endsWith(".csv")) return readCsv(file);
        throw new IOException("Unsupported customer export format: " + file.getFileName());
    }

    private static List<String> readExcel(Path file) throws IOException {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        try (Workbook workbook = WorkbookFactory.create(file.toFile())) {
            Sheet sheet = workbook.getSheetAt(0);
            DataFormatter fmt = new DataFormatter();

            int headerRowIndex = -1;
            int idCol = -1;
            for (int r = sheet.getFirstRowNum(); r <= Math.min(sheet.getLastRowNum(), 12); r++) {
                Row row = sheet.getRow(r);
                if (row == null) continue;
                int indosCol = -1;
                for (int c = row.getFirstCellNum(); c >= 0 && c < row.getLastCellNum(); c++) {
                    String h = normalizeHeader(fmt.formatCellValue(row.getCell(c)));
                    if (isCustomerIdHeader(h)) {
                        headerRowIndex = r;
                        idCol = c;
                        break;
                    }
                    if (h.equals("indos no") || h.equals("indos number")) indosCol = c;
                }
                if (idCol >= 0) break;
                if (indosCol > 0) {
                    headerRowIndex = r;
                    idCol = indosCol - 1;
                    break;
                }
            }

            if (idCol < 0) {
                headerRowIndex = sheet.getFirstRowNum();
                idCol = 0;
            }

            for (int r = headerRowIndex + 1; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                if (row == null) continue;
                String value = fmt.formatCellValue(row.getCell(idCol)).trim().replace(",", "");
                if (DIGITS.matcher(value).matches()) ids.add(value);
            }
        }
        return new ArrayList<>(ids);
    }

    private static List<String> readCsv(Path file) throws IOException {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String header = reader.readLine();
            if (header == null) return new ArrayList<>();
            List<String> headers = splitCsv(header);
            int idCol = -1;
            int indosCol = -1;
            for (int i = 0; i < headers.size(); i++) {
                String h = normalizeHeader(headers.get(i));
                if (isCustomerIdHeader(h)) { idCol = i; break; }
                if (h.equals("indos no") || h.equals("indos number")) indosCol = i;
            }
            if (idCol < 0 && indosCol > 0) idCol = indosCol - 1;
            if (idCol < 0) idCol = 0;

            String line;
            while ((line = reader.readLine()) != null) {
                List<String> cols = splitCsv(line);
                if (idCol >= cols.size()) continue;
                String value = cols.get(idCol).trim().replace(",", "");
                if (DIGITS.matcher(value).matches()) ids.add(value);
            }
        }
        return new ArrayList<>(ids);
    }

    private static List<String> splitCsv(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder b = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == '"') {
                if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    b.append('"');
                    i++;
                } else {
                    quoted = !quoted;
                }
            } else if (ch == ',' && !quoted) {
                out.add(b.toString());
                b.setLength(0);
            } else {
                b.append(ch);
            }
        }
        out.add(b.toString());
        return out;
    }

    private static boolean isCustomerIdHeader(String h) {
        return h.equals("customer id") || h.equals("customerid")
                || h.equals("cust id") || h.equals("customer no")
                || h.equals("customer number") || h.equals("id");
    }

    private static String normalizeHeader(String s) {
        if (s == null) return "";
        return s.replace('\u00A0', ' ')
                .replaceAll("[_\\-]+", " ")
                .replaceAll("\\s+", " ")
                .trim().toLowerCase(Locale.ROOT);
    }
}
