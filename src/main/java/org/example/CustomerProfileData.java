package org.example;

/**
 * Values read from the DG Shipping "View / Print Seafarer Profile" PDF.
 * Dates are stored as yyyy-MM-dd for Mariners Mentor HTML date inputs.
 */
public record CustomerProfileData(
        String indosNo,
        String dob,
        String fatherName,
        String passportNo,
        String height,
        String weight,
        DocumentInfo cdc,
        DocumentInfo sid,
        DocumentInfo cop,
        DocumentInfo coc,
        String vesselName,
        String rpsl,
        String role,
        String rpslHistory,
        String givenName,
        String surname,
        String email,
        String phone,
        String city,
        String state,
        String country
) {
    public record DocumentInfo(String number, String issuedDate, String expiryDate) {
        public static DocumentInfo empty() { return new DocumentInfo("", "", ""); }
        public boolean isEmpty() {
            return blank(number) && blank(issuedDate) && blank(expiryDate);
        }
        private static boolean blank(String s) { return s == null || s.trim().isEmpty(); }
    }
}
