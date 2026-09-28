package org.example;

import java.io.PrintStream;
import java.util.Locale;

/**
 * Operator-friendly local console.
 *
 * The schedulers may inspect the same cases every few seconds. This filter keeps
 * those implementation details out of the terminal and leaves only compact case
 * rows, Membership/SID/SPFO prompts, birthday result, and failures.
 */
public final class CompactConsole {

    private CompactConsole() {
    }

    public static void install() {
        PrintStream originalOut = System.out;
        System.setOut(new FilteredPrintStream(originalOut));
    }

    private static final class FilteredPrintStream extends PrintStream {

        private FilteredPrintStream(PrintStream original) {
            super(original, true);
        }

        @Override
        public void println() {
            // Suppress decorative blank lines.
        }

        @Override
        public void println(String line) {
            String display = displayLine(line);
            if (display != null) {
                super.println(display);
            }
        }

        @Override
        public void println(Object value) {
            String display = displayLine(String.valueOf(value));
            if (display != null) {
                super.println(display);
            }
        }

        private String displayLine(String line) {
            if (line == null) return null;

            // Selenium timeout messages often contain a full capabilities dump on
            // following lines. The first line is sufficient for the operator.
            String first = line.split("\\R", 2)[0].trim();
            if (first.isEmpty()) return null;

            String upper = first.toUpperCase(Locale.ENGLISH);

            if (upper.startsWith("CASE |")
                    || upper.startsWith("MEMBERSHIP |")
                    || upper.startsWith("SID |")
                    || upper.startsWith("SID VERIFICATION CAPTCHA READY")
                    || upper.startsWith("SID VERIFICATION RESULT FOUND")
                    || upper.startsWith("SID VERIFICATION PARTIAL RESULT")
                    || upper.startsWith("SID VERIFICATION | CAPTCHA")
                    || upper.startsWith("SID VERIFICATION AUTO FLOW ERROR")
                    || upper.startsWith("TYPE CAPTCHA")
                    || upper.startsWith("SPFO |")
                    || upper.startsWith("CAPTCHA ENLARGED")
                    || upper.startsWith("BIRTHDAY CHECK COMPLETED")) {
                return trimLength(first);
            }

            // Keep concise failures visible. Routine framework warnings/startup
            // chatter is intentionally hidden from stdout.
            if (upper.contains("FAILED")
                    || upper.contains("ERROR")
                    || upper.contains("PASSWORD MISSING")
                    || upper.contains("WRONG INDOS PASSWORD")) {
                return trimLength(first);
            }

            return null;
        }

        private String trimLength(String line) {
            return line.length() <= 320 ? line : line.substring(0, 320);
        }
    }
}
