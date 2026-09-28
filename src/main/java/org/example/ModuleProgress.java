package org.example;

public record ModuleProgress(
        String moduleName,
        int nextPart,
        int totalParts
) {

    public ModuleProgress {

        moduleName = moduleName == null
                ? ""
                : moduleName.trim();

        if (nextPart < 1) {
            nextPart = 1;
        }

        if (totalParts < 1) {
            totalParts = 1;
        }

        if (nextPart > totalParts) {
            nextPart = totalParts;
        }
    }
}
