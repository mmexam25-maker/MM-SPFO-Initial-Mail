package org.example;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Legacy helper kept for compatibility.
 * Scheduling delays have been removed: tasks are returned for immediate,
 * continuous execution. URGENT rows are still placed first.
 */
public class RoutineSchedulerService {

    public static List<ScheduledTask> generateSchedule(
            List<Map<String, String>> sheetDataRows
    ) {
        List<ScheduledTask> scheduleQueue = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();

        // URGENT first, with no time interval.
        for (Map<String, String> row : sheetDataRows) {
            String priority = row.getOrDefault("column_q", "").trim();
            if ("Urgent".equalsIgnoreCase(priority)) {
                String name = row.getOrDefault("module_name", "unknown")
                        .toLowerCase()
                        .trim();
                scheduleQueue.add(
                        new ScheduledTask(name, "URGENT CONTINUOUS", now)
                );
            }
        }

        // Then normal rows, also immediate with no day/hour scheduling.
        for (Map<String, String> row : sheetDataRows) {
            String priority = row.getOrDefault("column_q", "").trim();
            if (!"Urgent".equalsIgnoreCase(priority)) {
                String name = row.getOrDefault("module_name", "unknown")
                        .toLowerCase()
                        .trim();
                scheduleQueue.add(
                        new ScheduledTask(name, "CONTINUOUS", now)
                );
            }
        }

        return scheduleQueue;
    }

    public static class ScheduledTask {
        public String name;
        public String policy;
        public LocalDateTime targetTime;

        public ScheduledTask(
                String name,
                String policy,
                LocalDateTime targetTime
        ) {
            this.name = name;
            this.policy = policy;
            this.targetTime = targetTime;
        }
    }
}
