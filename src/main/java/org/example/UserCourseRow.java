package org.example;

import java.util.ArrayList;
import java.util.List;

public final class UserCourseRow {

    private final int sheetRowNumber;
    private final String username;
    private final String password;
    private final String studentName;
    private final String mobileNumber;
    private final String status;
    private final List<String> moduleNames;
    private final boolean urgent;
    private final String changedDgPassword;
    private final String aadhaarNumber;

    public UserCourseRow(
            int sheetRowNumber,
            String username,
            String password,
            String studentName,
            String mobileNumber,
            String status,
            List<String> moduleNames,
            boolean urgent,
            String changedDgPassword,
            String aadhaarNumber
    ) {
        this.sheetRowNumber = sheetRowNumber;
        this.username = username == null ? "" : username;
        this.password = password == null ? "" : password;
        this.studentName = studentName == null ? "" : studentName;
        this.mobileNumber = mobileNumber == null ? "" : mobileNumber;
        this.status = status == null ? "" : status;
        this.moduleNames = moduleNames == null
                ? new ArrayList<>()
                : new ArrayList<>(moduleNames);
        this.urgent = urgent;
        this.changedDgPassword = changedDgPassword == null ? "" : changedDgPassword;
        this.aadhaarNumber = aadhaarNumber == null ? "" : aadhaarNumber;
    }

    public int sheetRowNumber() {
        return sheetRowNumber;
    }

    public String username() {
        return username;
    }

    public String password() {
        return password;
    }

    public String studentName() {
        return studentName;
    }

    public String mobileNumber() {
        return mobileNumber;
    }

    public String status() {
        return status;
    }

    public List<String> moduleNames() {
        return moduleNames;
    }

    public boolean urgent() {
        return urgent;
    }

    public String changedDgPassword() {
        return changedDgPassword;
    }

    public String aadhaarNumber() {
        return aadhaarNumber;
    }
}
