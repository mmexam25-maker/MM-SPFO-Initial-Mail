package org.example;

import java.util.ArrayList;
import java.util.List;

public final class QuizPlanRow {

    private final int moduleNumber;
    private final int chapterNumber;
    private final String partCode;
    private final List<Integer> answers;

    public QuizPlanRow(
            int moduleNumber,
            int chapterNumber,
            String partCode,
            List<Integer> answers
    ) {
        this.moduleNumber = moduleNumber;
        this.chapterNumber = chapterNumber;
        this.partCode = partCode == null ? "" : partCode.trim();
        this.answers = answers == null
                ? new ArrayList<>()
                : new ArrayList<>(answers);
    }

    public int moduleNumber() {
        return moduleNumber;
    }

    public int chapterNumber() {
        return chapterNumber;
    }

    public String partCode() {
        return partCode;
    }

    public List<Integer> answers() {
        return answers;
    }

    public int partNumber() {
        if (partCode.isBlank()) {
            return 0;
        }

        String value = partCode.trim();

        if (value.contains(".")) {
            String[] pieces = value.split("\\.");
            try {
                return Integer.parseInt(
                        pieces[pieces.length - 1].trim()
                );
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }

        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }
}