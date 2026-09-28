package org.example;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Normalized DG profile data used for three Google-Sheet tabs:
 * Customer Profile (one row/customer), Course Details (many rows/customer),
 * and Sea Service (many rows/customer).
 */
public record ResumeEntryData(
        String surname,
        String givenName,
        String lastName,
        String fatherName,
        String email,
        String mobile,
        String altMobile,
        String dob,
        String address,
        String city,
        String state,
        String country,
        String pincode,
        String placeOfBirth,
        String height,
        String weight,
        String hairColor,
        String eyeColor,
        String complexion,
        String identificationMark,
        String indosNo,
        PassportInfo passport,
        DocInfo cdc,
        DocInfo sid,
        DocInfo cop,
        DocInfo coc,
        String cdcFlag,
        String watchkeepingType,
        String wkNo,
        List<CourseInfo> courses,
        List<VesselInfo> vessels
) {
    public ResumeEntryData {
        courses = courses == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(courses));
        vessels = vessels == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(vessels));
        passport = passport == null ? PassportInfo.empty() : passport;
        cdc = cdc == null ? DocInfo.empty() : cdc;
        sid = sid == null ? DocInfo.empty() : sid;
        cop = cop == null ? DocInfo.empty() : cop;
        coc = coc == null ? DocInfo.empty() : coc;
    }

    public ResumeEntryData withFallback(CustomerProfileData base) {
        if (base == null) return this;
        return new ResumeEntryData(
                first(surname, base.surname()),
                first(givenName, base.givenName()),
                lastName,
                first(fatherName, base.fatherName()),
                first(email, base.email()),
                first(mobile, base.phone()),
                altMobile,
                first(dob, displayDate(base.dob())),
                address,
                first(city, base.city()),
                first(state, base.state()),
                first(country, base.country()),
                pincode,
                placeOfBirth,
                first(height, base.height()),
                first(weight, base.weight()),
                hairColor,
                eyeColor,
                complexion,
                identificationMark,
                first(indosNo, base.indosNo()),
                passport.isEmpty()
                        ? new PassportInfo(base.passportNo(), "", "", "")
                        : passport,
                merge(cdc, base.cdc()),
                merge(sid, base.sid()),
                merge(cop, base.cop()),
                merge(coc, base.coc()),
                cdcFlag,
                watchkeepingType,
                wkNo,
                courses,
                vessels.isEmpty() ? fallbackVessels(base) : vessels
        );
    }

    public ResumeEntryData withAdditionalCourses(List<CourseInfo> extraCourses) {
        if (extraCourses == null || extraCourses.isEmpty()) return this;

        LinkedHashMap<String, CourseInfo> merged = new LinkedHashMap<>();
        for (CourseInfo c : courses) {
            if (c == null || c.isEmpty()) continue;
            merged.put(courseKey(c), withDefaultSource(c, "DG Profile"));
        }

        for (CourseInfo extra : extraCourses) {
            if (extra == null || extra.isEmpty()) continue;
            String key = courseKey(extra);
            CourseInfo current = merged.get(key);
            if (current == null) {
                merged.put(key, withDefaultSource(extra, "eLearning"));
                continue;
            }
            merged.put(key, mergeCourse(current, extra));
        }

        return new ResumeEntryData(
                surname, givenName, lastName, fatherName, email, mobile, altMobile,
                dob, address, city, state, country, pincode, placeOfBirth,
                height, weight, hairColor, eyeColor, complexion, identificationMark,
                indosNo, passport, cdc, sid, cop, coc, cdcFlag,
                watchkeepingType, wkNo, new ArrayList<>(merged.values()), vessels
        );
    }

    /**
     * Merge official STCW checker rows without collapsing refresher/repeated
     * certificates that have the same course name.  The ordinary
     * withAdditionalCourses() method intentionally merges by course name so
     * eLearning dates can enrich a DG Profile course; STCW history needs the
     * certificate number to remain distinct.
     */
    public ResumeEntryData withStcwCheckerCourses(List<CourseInfo> stcwCourses) {
        if (stcwCourses == null || stcwCourses.isEmpty()) return this;

        List<CourseInfo> merged = new ArrayList<>();
        for (CourseInfo c : courses) {
            if (c != null && !c.isEmpty()) merged.add(c);
        }

        for (CourseInfo incomingRaw : stcwCourses) {
            if (incomingRaw == null || incomingRaw.isEmpty()) continue;
            CourseInfo incoming = withDefaultSource(incomingRaw, "DG STCW Checker");
            int match = findStcwCourseMatch(merged, incoming);
            if (match >= 0) {
                merged.set(match, mergeCourse(merged.get(match), incoming));
            } else {
                merged.add(incoming);
            }
        }

        return new ResumeEntryData(
                surname, givenName, lastName, fatherName, email, mobile, altMobile,
                dob, address, city, state, country, pincode, placeOfBirth,
                height, weight, hairColor, eyeColor, complexion, identificationMark,
                indosNo, passport, cdc, sid, cop, coc, cdcFlag,
                watchkeepingType, wkNo, merged, vessels
        );
    }

    private static int findStcwCourseMatch(List<CourseInfo> existing, CourseInfo incoming) {
        String inCert = keyText(incoming.certificateNo());
        String inName = keyText(incoming.name());
        String inIssue = keyText(incoming.issueDate());

        for (int i = 0; i < existing.size(); i++) {
            CourseInfo current = existing.get(i);
            String curCert = keyText(current.certificateNo());
            if (!inCert.isBlank() && !curCert.isBlank() && inCert.equals(curCert)) {
                return i;
            }
        }

        // Fallback only when certificate number is unavailable.  Name + issue
        // date is sufficiently specific while still preserving repeated courses.
        if (!inName.isBlank() && !inIssue.isBlank()) {
            for (int i = 0; i < existing.size(); i++) {
                CourseInfo current = existing.get(i);
                if (inName.equals(keyText(current.name()))
                        && inIssue.equals(keyText(current.issueDate()))) {
                    return i;
                }
            }
        }
        return -1;
    }

    /**
     * Merge the DG "View Sea Service and Acknowledge" rows into the richer
     * printable-profile vessel rows. The live sea-service page is treated as
     * authoritative for RPSL/company, vessel, flag, rank and service dates;
     * printable-profile data fills technical fields that are not shown on that page.
     */
    public ResumeEntryData withSeaServiceRows(List<VesselInfo> liveSeaServiceRows) {
        if (liveSeaServiceRows == null || liveSeaServiceRows.isEmpty()) return this;

        List<VesselInfo> mergedVessels = mergeSeaServiceRows(liveSeaServiceRows, vessels);
        return new ResumeEntryData(
                surname, givenName, lastName, fatherName, email, mobile, altMobile,
                dob, address, city, state, country, pincode, placeOfBirth,
                height, weight, hairColor, eyeColor, complexion, identificationMark,
                indosNo, passport, cdc, sid, cop, coc, cdcFlag,
                watchkeepingType, wkNo, courses, mergedVessels
        );
    }

    private static List<VesselInfo> mergeSeaServiceRows(
            List<VesselInfo> liveRows,
            List<VesselInfo> printableRows
    ) {
        List<VesselInfo> remaining = new ArrayList<>();
        if (printableRows != null) {
            for (VesselInfo v : printableRows) {
                if (v != null && !v.isEmpty()) remaining.add(v);
            }
        }

        LinkedHashMap<String, VesselInfo> unique = new LinkedHashMap<>();
        for (VesselInfo live : liveRows) {
            if (live == null || live.isEmpty()) continue;
            int match = bestVesselMatch(live, remaining);
            VesselInfo merged = live;
            if (match >= 0) {
                merged = mergeVessel(live, remaining.remove(match));
            }
            addVessel(unique, merged);
        }

        // Do not lose older printable-profile rows if DG does not expose them on
        // the current Sea Service page. They are appended after the live rows.
        for (VesselInfo fallback : remaining) addVessel(unique, fallback);
        return new ArrayList<>(unique.values());
    }

    private static int bestVesselMatch(VesselInfo live, List<VesselInfo> candidates) {
        int bestIndex = -1;
        int bestScore = Integer.MIN_VALUE;
        for (int i = 0; i < candidates.size(); i++) {
            VesselInfo other = candidates.get(i);
            int score = vesselMatchScore(live, other);
            if (score > bestScore) {
                bestScore = score;
                bestIndex = i;
            }
        }
        return bestScore >= 8 ? bestIndex : -1;
    }

    private static int vesselMatchScore(VesselInfo a, VesselInfo b) {
        if (a == null || b == null) return Integer.MIN_VALUE;
        String av = keyText(a.vesselName());
        String bv = keyText(b.vesselName());
        if (!av.isBlank() && !bv.isBlank()) {
            if (av.equals(bv)) {
                // strongest identity signal
            } else if (av.contains(bv) || bv.contains(av)) {
                // tolerate prefixes/suffixes such as M.V. / MT
            } else {
                return Integer.MIN_VALUE;
            }
        }

        int score = (!av.isBlank() && !bv.isBlank()) ? (av.equals(bv) ? 10 : 8) : 0;
        if (sameKey(a.rank(), b.rank())) score += 2;
        if (sameKey(a.companyName(), b.companyName())) score += 2;
        if (sameKey(a.serviceFrom(), b.serviceFrom())) score += 4;
        if (sameKey(a.serviceTo(), b.serviceTo())) score += 4;
        if (sameKey(a.imoNo(), b.imoNo())) score += 8;
        return score;
    }

    private static boolean sameKey(String a, String b) {
        String x = keyText(a);
        String y = keyText(b);
        return !x.isBlank() && !y.isBlank() && x.equals(y);
    }

    private static String keyText(String value) {
        return nvl(value).toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static VesselInfo mergeVessel(VesselInfo primary, VesselInfo fallback) {
        return new VesselInfo(
                first(primary.companyName(), fallback.companyName()),
                first(primary.vesselName(), fallback.vesselName()),
                first(primary.officialNo(), fallback.officialNo()),
                first(primary.imoNo(), fallback.imoNo()),
                first(primary.flag(), fallback.flag()),
                first(primary.portOfRegistry(), fallback.portOfRegistry()),
                first(primary.vesselType(), fallback.vesselType()),
                first(primary.grt(), fallback.grt()),
                first(primary.tradeArea(), fallback.tradeArea()),
                first(primary.rank(), fallback.rank()),
                first(primary.natureOfWatch(), fallback.natureOfWatch()),
                first(primary.serviceFrom(), fallback.serviceFrom()),
                first(primary.serviceTo(), fallback.serviceTo()),
                first(primary.articleMonths(), fallback.articleMonths()),
                first(primary.articleDays(), fallback.articleDays()),
                first(primary.propulsionPowerKw(), fallback.propulsionPowerKw()),
                first(primary.propulsionType(), fallback.propulsionType()),
                first(primary.propellingDays(), fallback.propellingDays()),
                mergeRemarks(primary.remarks(), fallback.remarks())
        );
    }

    private static String mergeRemarks(String primary, String fallback) {
        String a = nvl(primary).trim();
        String b = nvl(fallback).trim();
        if (a.isBlank()) return b;
        if (b.isBlank() || a.equalsIgnoreCase(b)) return a;
        return a + " | " + b;
    }

    /**
     * Latest service = an active/open voyage first (Service From present but no
     * Service To), otherwise the greatest Service To date. This prevents an
     * ongoing DG sea-service row from being replaced by an older completed ship.
     */
    public VesselInfo latestVessel() {
        if (vessels == null || vessels.isEmpty()) return VesselInfo.empty();

        VesselInfo best = null;
        boolean bestOpen = false;
        LocalDate bestFrom = LocalDate.MIN;
        LocalDate bestTo = LocalDate.MIN;

        for (VesselInfo v : vessels) {
            if (v == null || v.isEmpty()) continue;

            LocalDate from = parseDateOrMin(v.serviceFrom());
            LocalDate to = parseDateOrMin(v.serviceTo());
            boolean open = !from.equals(LocalDate.MIN) && blank(v.serviceTo());

            if (best == null
                    || (open && !bestOpen)
                    || (open == bestOpen && open && from.isAfter(bestFrom))
                    || (open == bestOpen && !open && to.isAfter(bestTo))) {
                best = v;
                bestOpen = open;
                bestFrom = from;
                bestTo = to;
            }
        }
        return best == null ? VesselInfo.empty() : best;
    }

    private static CourseInfo mergeCourse(CourseInfo a, CourseInfo b) {
        return new CourseInfo(
                first(a.name(), b.name()),
                first(a.certificateNo(), b.certificateNo()),
                first(a.issueDate(), b.issueDate()),
                first(a.expiryDate(), b.expiryDate()),
                first(a.validity(), b.validity()),
                first(a.placeOfIssue(), b.placeOfIssue()),
                first(a.stcwCode(), b.stcwCode()),
                first(a.trainingInstitute(), b.trainingInstitute()),
                first(a.attendedFrom(), b.attendedFrom()),
                first(a.attendedTo(), b.attendedTo()),
                first(a.elearningStartDate(), b.elearningStartDate()),
                first(a.elearningEndDate(), b.elearningEndDate()),
                mergeSources(a.source(), b.source()),
                first(a.digitalCertificateLink(), b.digitalCertificateLink())
        );
    }

    private static CourseInfo withDefaultSource(CourseInfo c, String defaultSource) {
        return new CourseInfo(
                c.name(), c.certificateNo(), c.issueDate(), c.expiryDate(), c.validity(),
                c.placeOfIssue(), c.stcwCode(), c.trainingInstitute(), c.attendedFrom(),
                c.attendedTo(), c.elearningStartDate(), c.elearningEndDate(),
                blank(c.source()) ? defaultSource : c.source(), c.digitalCertificateLink()
        );
    }

    private static String mergeSources(String a, String b) {
        LinkedHashSet<String> parts = new LinkedHashSet<>();
        for (String source : List.of(nvl(a), nvl(b))) {
            for (String part : source.split("\\s*\\+\\s*|\\s*,\\s*")) {
                if (!blank(part)) parts.add(part.trim());
            }
        }
        return String.join(" + ", parts);
    }

    private static String courseKey(CourseInfo c) {
        String name = nvl(c.name()).toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", " ").trim();
        if (!name.isBlank()) return name;
        return (nvl(c.certificateNo()) + "|" + nvl(c.elearningStartDate()))
                .toLowerCase(Locale.ROOT).trim();
    }

    private static List<VesselInfo> fallbackVessels(CustomerProfileData base) {
        LinkedHashMap<String, VesselInfo> unique = new LinkedHashMap<>();

        VesselInfo latest = new VesselInfo(
                nvl(base.rpsl()).trim(), nvl(base.vesselName()).trim(), "", "", "",
                "", "", "", "", nvl(base.role()).trim(), "", "", "", "", "", "", "", "", "");
        addVessel(unique, latest);

        String history = nvl(base.rpslHistory());
        if (!history.isBlank()) {
            for (String item : history.split("\\s*;\\s*")) {
                String company = token(item, "RPSL", "Role", "Vessel");
                String role = token(item, "Role", "Vessel", "RPSL");
                String vessel = token(item, "Vessel", "Role", "RPSL");
                addVessel(unique, new VesselInfo(
                        company, vessel, "", "", "", "", "", "", "", role,
                        "", "", "", "", "", "", "", "", ""));
                if (unique.size() >= 50) break;
            }
        }
        return new ArrayList<>(unique.values());
    }

    private static void addVessel(LinkedHashMap<String, VesselInfo> unique, VesselInfo v) {
        if (v == null || v.isEmpty()) return;
        String key = (nvl(v.companyName()) + "|" + nvl(v.vesselName()) + "|" + nvl(v.rank())
                + "|" + nvl(v.serviceFrom()) + "|" + nvl(v.serviceTo()))
                .replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
        if (!key.replace("|", "").isBlank()) unique.putIfAbsent(key, v);
    }

    private static String token(String text, String label, String... stopLabels) {
        if (blank(text)) return "";
        StringBuilder stop = new StringBuilder();
        for (String s : stopLabels) {
            if (stop.length() > 0) stop.append("|");
            stop.append(Pattern.quote(s));
        }
        String regex = "(?i)(?:^|\\|)\\s*" + Pattern.quote(label)
                + "\\s+(.+?)(?=\\s*\\|\\s*(?:" + stop + ")\\b|\\s*\\|\\s*\\d{1,2}[/-]\\d{1,2}[/-]\\d{4}|$)";
        Matcher m = Pattern.compile(regex).matcher(text);
        return m.find() ? m.group(1).trim() : "";
    }

    private static DocInfo merge(DocInfo detail, CustomerProfileData.DocumentInfo base) {
        if (detail == null) detail = DocInfo.empty();
        if (base == null) return detail;
        return new DocInfo(
                first(detail.number(), base.number()),
                first(detail.issueDate(), displayDate(base.issuedDate())),
                first(detail.expiryDate(), displayDate(base.expiryDate())),
                detail.placeOfIssue()
        );
    }

    private static String displayDate(String iso) {
        if (blank(iso)) return "";
        try {
            LocalDate d = LocalDate.parse(iso.trim());
            return d.format(DateTimeFormatter.ofPattern("dd/MM/uuuu"));
        } catch (Exception ignored) {
            return iso.trim();
        }
    }

    private static LocalDate parseDateOrMin(String value) {
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

    private static String first(String a, String b) {
        return blank(a) ? nvl(b).trim() : a.trim();
    }

    private static boolean blank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private static String nvl(String s) {
        return s == null ? "" : s;
    }

    public record PassportInfo(String number, String placeOfIssue, String issueDate, String expiryDate) {
        public static PassportInfo empty() { return new PassportInfo("", "", "", ""); }
        public boolean isEmpty() {
            return blank(number) && blank(placeOfIssue) && blank(issueDate) && blank(expiryDate);
        }
    }

    public record DocInfo(String number, String issueDate, String expiryDate, String placeOfIssue) {
        public static DocInfo empty() { return new DocInfo("", "", "", ""); }
        public boolean isEmpty() {
            return blank(number) && blank(issueDate) && blank(expiryDate) && blank(placeOfIssue);
        }
    }

    public record CourseInfo(
            String name,
            String certificateNo,
            String issueDate,
            String expiryDate,
            String validity,
            String placeOfIssue,
            String stcwCode,
            String trainingInstitute,
            String attendedFrom,
            String attendedTo,
            String elearningStartDate,
            String elearningEndDate,
            String source,
            String digitalCertificateLink
    ) {
        public CourseInfo {
            name = nvl(name).trim();
            certificateNo = nvl(certificateNo).trim();
            issueDate = nvl(issueDate).trim();
            expiryDate = nvl(expiryDate).trim();
            validity = nvl(validity).trim();
            placeOfIssue = nvl(placeOfIssue).trim();
            stcwCode = nvl(stcwCode).trim();
            trainingInstitute = nvl(trainingInstitute).trim();
            attendedFrom = nvl(attendedFrom).trim();
            attendedTo = nvl(attendedTo).trim();
            elearningStartDate = nvl(elearningStartDate).trim();
            elearningEndDate = nvl(elearningEndDate).trim();
            source = nvl(source).trim();
            digitalCertificateLink = nvl(digitalCertificateLink).trim();
        }

        public boolean isEmpty() {
            return blank(name) && blank(certificateNo) && blank(stcwCode)
                    && blank(elearningStartDate) && blank(elearningEndDate);
        }
    }

    public record VesselInfo(
            String companyName,
            String vesselName,
            String officialNo,
            String imoNo,
            String flag,
            String portOfRegistry,
            String vesselType,
            String grt,
            String tradeArea,
            String rank,
            String natureOfWatch,
            String serviceFrom,
            String serviceTo,
            String articleMonths,
            String articleDays,
            String propulsionPowerKw,
            String propulsionType,
            String propellingDays,
            String remarks
    ) {
        public VesselInfo {
            companyName = nvl(companyName).trim();
            vesselName = nvl(vesselName).trim();
            officialNo = nvl(officialNo).trim();
            imoNo = nvl(imoNo).trim();
            flag = nvl(flag).trim();
            portOfRegistry = nvl(portOfRegistry).trim();
            vesselType = nvl(vesselType).trim();
            grt = nvl(grt).trim();
            tradeArea = nvl(tradeArea).trim();
            rank = nvl(rank).trim();
            natureOfWatch = nvl(natureOfWatch).trim();
            serviceFrom = nvl(serviceFrom).trim();
            serviceTo = nvl(serviceTo).trim();
            articleMonths = nvl(articleMonths).trim();
            articleDays = nvl(articleDays).trim();
            propulsionPowerKw = nvl(propulsionPowerKw).trim();
            propulsionType = nvl(propulsionType).trim();
            propellingDays = nvl(propellingDays).trim();
            remarks = nvl(remarks).trim();
        }

        public static VesselInfo empty() {
            return new VesselInfo("", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "");
        }

        public boolean isEmpty() {
            return blank(companyName) && blank(vesselName) && blank(officialNo) && blank(imoNo)
                    && blank(flag) && blank(portOfRegistry) && blank(vesselType) && blank(grt)
                    && blank(tradeArea) && blank(rank) && blank(natureOfWatch)
                    && blank(serviceFrom) && blank(serviceTo) && blank(articleMonths)
                    && blank(articleDays) && blank(propulsionPowerKw) && blank(propulsionType)
                    && blank(propellingDays) && blank(remarks);
        }
    }
}
