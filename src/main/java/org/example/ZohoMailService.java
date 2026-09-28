package org.example;


import jakarta.activation.DataHandler;
import jakarta.mail.Authenticator;
import jakarta.mail.Message;
import jakarta.mail.PasswordAuthentication;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.util.ByteArrayDataSource;

import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.List;
import java.util.Properties;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ZohoMailService {

    private static final String LOGO_RESOURCE =
            "mariners-mentor-logo.png";

    private ZohoMailService() {
    }

    public static void sendWelcomeMail(
            Properties config,
            String customerName,
            String candidateEmail,
            String serviceName,
            String assignedTo,
            String extraMessage
    ) throws Exception {

        if (candidateEmail == null
                || candidateEmail.isBlank()) {

            throw new IllegalArgumentException(
                    "Candidate email is missing."
            );
        }

        String host =
                config.getProperty(
                        "mail.smtp.host",
                        "smtp.zoho.in"
                ).trim();

        String port =
                config.getProperty(
                        "mail.smtp.port",
                        "587"
                ).trim();

        String username =
                required(
                        config,
                        "mail.smtp.username"
                );

        String password =
                required(
                        config,
                        "mail.smtp.password"
                );

        String fromName =
                config.getProperty(
                        "mail.from.name",
                        "Mariners Mentor"
                ).trim();

        String name =
                customerName == null
                        || customerName.isBlank()
                        ? "Candidate"
                        : customerName.trim();

        String service =
                serviceName == null
                        || serviceName.isBlank()
                        ? "Requested Service"
                        : serviceName.trim();

        String coordinator =
                assignedTo == null
                        || assignedTo.isBlank()
                        ? "Mariners Mentor Team"
                        : assignedTo.trim();

        String coordinatorPhone =
                getCoordinatorPhone(
                        config,
                        coordinator
                );

        String whatsappMessage =
                "Hello " + coordinator + ",\n\n"
                        + "I am " + name + ".\n"
                        + "My requested service is "
                        + service + ".\n\n"
                        + "Please guide me regarding the next process.";

        String encodedWhatsAppMessage =
                URLEncoder.encode(
                                whatsappMessage,
                                StandardCharsets.UTF_8
                        )
                        .replace(
                                "+",
                                "%20"
                        );

        String whatsappLink =
                "https://wa.me/"
                        + coordinatorPhone
                        + "?text="
                        + encodedWhatsAppMessage;

        String callLink =
                "tel:+" + coordinatorPhone;

        String googleReviewLink =
                config.getProperty(
                        "company.google.review.url",
                        "#"
                ).trim();

        String facebookLink =
                config.getProperty(
                        "company.facebook.url",
                        "#"
                ).trim();

        String websiteLink =
                config.getProperty(
                        "company.website.url",
                        "https://marinersmentor.com"
                ).trim();
        String pfFormUrl =
                config.getProperty(
                        "pf.form.url",
                        ""
                ).trim();

        // Never send localhost/127.0.0.1 links to candidates.
        // On a phone, localhost points to the phone itself, not this server.
        if (pfFormUrl.isBlank()
                || pfFormUrl.contains("localhost")
                || pfFormUrl.contains("127.0.0.1")) {

            String publicAppUrl =
                    config.getProperty(
                            "app.public.url",
                            ""
                    ).trim();

            if (!publicAppUrl.isBlank()) {
                while (publicAppUrl.endsWith("/")) {
                    publicAppUrl = publicAppUrl.substring(0, publicAppUrl.length() - 1);
                }
                pfFormUrl = publicAppUrl + "/pf-form.html";
            }
        }

        boolean jsuBalanceQuery = isJsuBalanceQuery(service);
        boolean spfoBalanceQuery = isSpfoBalanceQuery(service);
        boolean spfoService = isSpfoService(service)
                && !spfoBalanceQuery
                && extraMessage != null
                && !extraMessage.isBlank();
        boolean jsuService = isJsuService(service);

        String buttonSection;


// =====================================================
// JSU BALANCE QUERY
// =====================================================
        if (jsuBalanceQuery) {

            if (extraMessage == null
                    || extraMessage.isBlank()) {

                throw new IllegalStateException(
                        "JSU Balance Query form URL is missing"
                );
            }

            buttonSection =
                    "<table cellpadding=\"0\" "
                            + "cellspacing=\"0\" "
                            + "border=\"0\" "
                            + "align=\"center\" "
                            + "style=\"margin:25px auto 30px auto;\">"
                            + "<tr>"
                            + "<td align=\"center\">"
                            + "<a href=\""
                            + escapeHtml(extraMessage.trim())
                            + "\" "
                            + "target=\"_blank\" "
                            + "style=\""
                            + "display:inline-block;"
                            + "background:#087333;"
                            + "color:#ffffff;"
                            + "text-decoration:none;"
                            + "font-weight:bold;"
                            + "font-size:16px;"
                            + "padding:16px 32px;"
                            + "border-radius:7px;"
                            + "\">"
                            + "Check JSU Balance"
                            + "</a>"
                            + "</td>"
                            + "</tr>"
                            + "</table>";


// =====================================================
// SPFO / SEAMAN PROVIDENT FUND
// =====================================================

        } else if (spfoService) {

            if (extraMessage == null
                    || extraMessage.isBlank()) {

                throw new IllegalStateException(
                        "SPFO secure form URL is missing"
                );
            }

            buttonSection =
                    "<table cellpadding=\"0\" "
                            + "cellspacing=\"0\" "
                            + "border=\"0\" "
                            + "align=\"center\" "
                            + "style=\"margin:25px auto 30px auto;\">"
                            + "<tr>"
                            + "<td align=\"center\">"

                            + "<a href=\""
                            + escapeHtml(extraMessage.trim())
                            + "\" "
                            + "target=\"_blank\" "

                            + "style=\""
                            + "display:inline-block;"
                            + "background:#087333;"
                            + "color:#ffffff;"
                            + "text-decoration:none;"
                            + "font-weight:bold;"
                            + "font-size:16px;"
                            + "padding:16px 32px;"
                            + "border-radius:7px;"
                            + "\">"

                            + "Open SPFO Balance Form"

                            + "</a>"
                            + "</td>"
                            + "</tr>"
                            + "</table>";


// =====================================================
// JAPAN PF / JSU
// =====================================================

        } else if (jsuService) {

            if (pfFormUrl.isBlank()) {

                throw new IllegalStateException(
                        "pf.form.url is missing in config.properties"
                );
            }

            buttonSection =
                    "<table cellpadding=\"0\" "
                            + "cellspacing=\"0\" "
                            + "border=\"0\" "
                            + "align=\"center\" "
                            + "style=\"margin:25px auto 30px auto;\">"
                            + "<tr>"
                            + "<td align=\"center\">"

                            + "<a href=\""
                            + escapeHtml(pfFormUrl)
                            + "\" "
                            + "target=\"_blank\" "

                            + "style=\""
                            + "display:inline-block;"
                            + "background:#087333;"
                            + "color:#ffffff;"
                            + "text-decoration:none;"
                            + "font-weight:bold;"
                            + "font-size:16px;"
                            + "padding:16px 28px;"
                            + "border-radius:7px;"
                            + "\">"

                            + "Prepare Japan PF Withdrawal Mail"

                            + "</a>"
                            + "</td>"
                            + "</tr>"
                            + "</table>";


// =====================================================
// NORMAL SERVICES
// =====================================================

        } else {

            buttonSection =
                    "<table cellpadding=\"0\" "
                            + "cellspacing=\"0\" "
                            + "border=\"0\" "
                            + "align=\"center\" "
                            + "style=\"margin:10px auto 30px auto;\">"

                            + "<tr>"

                            + createButton(
                            whatsappLink,
                            "WhatsApp " + coordinator,
                            "#25D366"
                    )

                            + createButton(
                            callLink,
                            "Call " + coordinator,
                            "#0b5ed7"
                    )

                            + createButton(
                            googleReviewLink,
                            "Google Review",
                            "#f59e0b"
                    )

                            + "</tr>"

                            + "<tr>"

                            + createButton(
                            facebookLink,
                            "Facebook",
                            "#1877F2"
                    )

                            + createButton(
                            websiteLink,
                            "Visit Us",
                            "#082f49"
                    )

                            + "</tr>"

                            + "</table>";
        }
        Properties mailProperties =
                new Properties();

        mailProperties.put(
                "mail.smtp.auth",
                "true"
        );

        mailProperties.put(
                "mail.smtp.host",
                host
        );

        mailProperties.put(
                "mail.smtp.port",
                port
        );

        mailProperties.put(
                "mail.smtp.ssl.enable",
                "false"
        );

        mailProperties.put(
                "mail.smtp.starttls.enable",
                "true"
        );

        mailProperties.put(
                "mail.smtp.starttls.required",
                "true"
        );

        mailProperties.put(
                "mail.smtp.ssl.protocols",
                "TLSv1.2 TLSv1.3"
        );

        mailProperties.put(
                "mail.smtp.connectiontimeout",
                "30000"
        );

        mailProperties.put(
                "mail.smtp.timeout",
                "30000"
        );

        mailProperties.put(
                "mail.smtp.writetimeout",
                "30000"
        );

        Session session =
                Session.getInstance(
                        mailProperties,
                        new Authenticator() {

                            @Override
                            protected PasswordAuthentication
                            getPasswordAuthentication() {

                                return new PasswordAuthentication(
                                        username,
                                        password
                                );
                            }
                        }
                );

        String extraSection = "";

        if (!jsuBalanceQuery
                && !spfoBalanceQuery
                && !spfoService
                && extraMessage != null
                && !extraMessage.isBlank()) {

            extraSection =
                    "<p style=\""
                            + "margin:0 0 16px 0;"
                            + "font-size:15px;"
                            + "line-height:1.6;"
                            + "color:#222222;"
                            + "\">"
                            + escapeHtml(extraMessage)
                            + "</p>";
        }

        String logoSection =
                "<img "
                        + "src=\"cid:marinersMentorLogo\" "
                        + "alt=\"Mariners Mentor Logo\" "
                        + "width=\"105\" "
                        + "style=\""
                        + "display:block;"
                        + "width:105px;"
                        + "height:auto;"
                        + "max-height:105px;"
                        + "object-fit:contain;"
                        + "margin:0 auto 12px auto;"
                        + "\">";

        String htmlBody =
                "<!DOCTYPE html>"
                        + "<html>"
                        + "<head>"
                        + "<meta charset=\"UTF-8\">"
                        + "<meta name=\"viewport\" "
                        + "content=\"width=device-width,initial-scale=1.0\">"
                        + "</head>"

                        + "<body style=\""
                        + "margin:0;"
                        + "padding:0;"
                        + "background:#f1f3f5;"
                        + "font-family:Arial,Helvetica,sans-serif;"
                        + "\">"

                        + "<table width=\"100%\" "
                        + "cellpadding=\"0\" "
                        + "cellspacing=\"0\" "
                        + "border=\"0\" "
                        + "style=\"background:#f1f3f5;"
                        + "padding:30px 10px;\">"

                        + "<tr>"
                        + "<td align=\"center\">"

                        + "<table width=\"700\" "
                        + "cellpadding=\"0\" "
                        + "cellspacing=\"0\" "
                        + "border=\"0\" "
                        + "style=\""
                        + "width:100%;"
                        + "max-width:700px;"
                        + "background:#ffffff;"
                        + "border-radius:10px;"
                        + "overflow:hidden;"
                        + "box-shadow:0 3px 12px rgba(0,0,0,0.08);"
                        + "\">"

                        // Header
                        + "<tr>"
                        + "<td align=\"center\" "
                        + "style=\""
                        + "background:#087333;"
                        + "padding:24px 15px;"
                        + "color:#ffffff;"
                        + "\">"

                        + logoSection

                        + "<div style=\""
                        + "font-size:25px;"
                        + "font-weight:bold;"
                        + "letter-spacing:0.5px;"
                        + "\">"
                        + "MARINERS MENTOR"
                        + "</div>"

                        + "<div style=\""
                        + "font-size:14px;"
                        + "margin-top:5px;"
                        + "\">"
                        + "Welcome Onboard ⚓"
                        + "</div>"

                        + "</td>"
                        + "</tr>"

                        // Content
                        + "<tr>"
                        + "<td style=\""
                        + "padding:38px 32px;"
                        + "color:#111111;"
                        + "\">"

                        + "<h2 style=\""
                        + "margin:0 0 20px 0;"
                        + "color:#087333;"
                        + "font-size:23px;"
                        + "\">"
                        + "Welcome to Mariners Mentor ⚓"
                        + "</h2>"

                        + "<p style=\""
                        + "margin:0 0 16px 0;"
                        + "font-size:16px;"
                        + "line-height:1.5;"
                        + "\">"
                        + "Dear <strong>"
                        + escapeHtml(name)
                        + "</strong>,"
                        + "</p>"

                        + "<p style=\""
                        + "margin:0 0 16px 0;"
                        + "font-size:15px;"
                        + "line-height:1.6;"
                        + "\">"
                        + "Thank you for choosing Mariners Mentor. "
                        + "We are delighted to welcome you to our maritime family."
                        + "</p>"

                        + "<table width=\"100%\" "
                        + "cellpadding=\"0\" "
                        + "cellspacing=\"0\" "
                        + "style=\""
                        + "margin:20px 0;"
                        + "background:#f7faf8;"
                        + "border-left:4px solid #087333;"
                        + "\">"

                        + "<tr>"
                        + "<td style=\"padding:16px;\">"

                        + "<p style=\""
                        + "margin:0 0 10px 0;"
                        + "font-size:15px;"
                        + "\">"
                        + "<strong>Requested Service:</strong> "
                        + escapeHtml(service)
                        + "</p>"

                        + "<p style=\""
                        + "margin:0 0 10px 0;"
                        + "font-size:15px;"
                        + "\">"
                        + "<strong>Assigned Coordinator:</strong> "
                        + escapeHtml(coordinator)
                        + "</p>"

                        + "<p style=\""
                        + "margin:0;"
                        + "font-size:15px;"
                        + "\">"
                        + "<strong>Coordinator Contact:</strong> +"
                        + escapeHtml(coordinatorPhone)
                        + "</p>"

                        + "</td>"
                        + "</tr>"
                        + "</table>"

                        + "<p style=\""
                        + "margin:0 0 16px 0;"
                        + "font-size:15px;"
                        + "line-height:1.6;"
                        + "\">"
                        + "Our team is committed to supporting your maritime "
                        + "career and helping you achieve your professional goals."
                        + "</p>"

                        + extraSection

                        + "<p style=\""
                        + "margin:0 0 16px 0;"
                        + "font-size:15px;"
                        + "line-height:1.6;"
                        + "\">"
                        + "We sincerely appreciate your trust and look forward "
                        + "to being a part of your success story."
                        + "</p>"

                        + "<p style=\""
                        + "margin:0 0 25px 0;"
                        + "font-size:15px;"
                        + "line-height:1.6;"
                        + "\">"
                        + "Fair winds and following seas on your journey ahead."
                        + "</p>"

                        // Buttons
                        + buttonSection

                        + "<p style=\""
                        + "margin:0;"
                        + "font-size:15px;"
                        + "line-height:1.5;"
                        + "\">"
                        + "<strong>Warm Regards,</strong><br>"
                        + "<strong>Mariners Mentor Team</strong><br>"
                        + "Guiding Future Mariners ⚓"
                        + "</p>"

                        + "</td>"
                        + "</tr>"

                        // Footer
                        + "<tr>"
                        + "<td align=\"center\" "
                        + "style=\""
                        + "background:#f8f9fa;"
                        + "padding:14px;"
                        + "font-size:12px;"
                        + "color:#777777;"
                        + "\">"
                        + "This is an automated welcome email from Mariners Mentor."
                        + "</td>"
                        + "</tr>"

                        + "</table>"
                        + "</td>"
                        + "</tr>"
                        + "</table>"

                        + "</body>"
                        + "</html>";

        MimeMessage message =
                new MimeMessage(session);

        message.setFrom(
                new InternetAddress(
                        username,
                        fromName,
                        StandardCharsets.UTF_8.name()
                )
        );

        message.setRecipients(
                Message.RecipientType.TO,
                InternetAddress.parse(
                        candidateEmail.trim()
                )
        );

        message.setSubject(
                "Welcome to Mariners Mentor - "
                        + service,
                StandardCharsets.UTF_8.name()
        );

        MimeBodyPart htmlPart =
                new MimeBodyPart();

        htmlPart.setContent(
                htmlBody,
                "text/html; charset=UTF-8"
        );

        MimeBodyPart logoPart =
                createLogoPart();

        MimeMultipart relatedMultipart =
                new MimeMultipart("related");

        relatedMultipart.addBodyPart(htmlPart);
        relatedMultipart.addBodyPart(logoPart);

        message.setContent(relatedMultipart);
        message.saveChanges();

        sendMessage(config, message);

        System.out.println(
                "Zoho HTML email sent"
                        + " | To: "
                        + candidateEmail
                        + " | Service: "
                        + service
                        + " | Coordinator: "
                        + coordinator
                        + " | Phone: "
                        + coordinatorPhone
        );
    }

    public static void sendJsuBalanceMail(
            Properties config,
            String customerName,
            String candidateEmail,
            String balanceAmount,
            java.util.List<String[]> contributionRows
    ) throws Exception {

        if (candidateEmail == null || candidateEmail.isBlank()) {
            throw new IllegalArgumentException("Candidate email is missing.");
        }

        String host = config.getProperty("mail.smtp.host", "smtp.zoho.in").trim();
        String port = config.getProperty("mail.smtp.port", "587").trim();
        String username = required(config, "mail.smtp.username");
        String password = required(config, "mail.smtp.password");
        String fromName = config.getProperty("mail.from.name", "Mariners Mentor").trim();

        String name = customerName == null || customerName.isBlank()
                ? "Candidate"
                : customerName.trim();

        String amount = balanceAmount == null || balanceAmount.isBlank()
                ? "Not available"
                : balanceAmount.trim();

        Properties mailProperties = new Properties();
        mailProperties.put("mail.smtp.auth", "true");
        mailProperties.put("mail.smtp.host", host);
        mailProperties.put("mail.smtp.port", port);
        mailProperties.put("mail.smtp.ssl.enable", "false");
        mailProperties.put("mail.smtp.starttls.enable", "true");
        mailProperties.put("mail.smtp.starttls.required", "true");
        mailProperties.put("mail.smtp.ssl.protocols", "TLSv1.2 TLSv1.3");
        mailProperties.put("mail.smtp.connectiontimeout", "30000");
        mailProperties.put("mail.smtp.timeout", "30000");
        mailProperties.put("mail.smtp.writetimeout", "30000");

        Session session = Session.getInstance(
                mailProperties,
                new Authenticator() {
                    @Override
                    protected PasswordAuthentication getPasswordAuthentication() {
                        return new PasswordAuthentication(username, password);
                    }
                }
        );

        StringBuilder tableRows = new StringBuilder();
        if (contributionRows != null) {
            for (String[] row : contributionRows) {
                if (row == null || row.length < 4) {
                    continue;
                }

                String vessel = safeMailValue(row[0], "Vessel");
                String doo = safeMailValue(row[1], "-");
                String dod = safeMailValue(row[2], "-");
                String contribution = safeMailValue(row[3], "-");

                tableRows.append("<tr>")
                        .append("<td colspan='3' style='padding:10px 8px;background:#eef0f2;font-weight:700;color:#111;border:1px solid #cbd5dc;'>")
                        .append(escapeHtml(vessel))
                        .append("</td></tr>")
                        .append("<tr>")
                        .append("<td style='padding:10px 8px;border:1px solid #cbd5dc;'>")
                        .append(escapeHtml(doo))
                        .append("</td>")
                        .append("<td style='padding:10px 8px;border:1px solid #cbd5dc;'>")
                        .append(escapeHtml(dod))
                        .append("</td>")
                        .append("<td style='padding:10px 8px;border:1px solid #cbd5dc;text-align:right;'>")
                        .append(escapeHtml(contribution))
                        .append("</td>")
                        .append("</tr>");
            }
        }

        if (tableRows.length() == 0) {
            tableRows.append("<tr><td colspan='3' style='padding:14px;text-align:center;color:#666;border:1px solid #cbd5dc;'>")
                    .append("No vessel contribution details available.")
                    .append("</td></tr>");
        }

        // Email clients block JavaScript, so details remain visible like the
        // opened state in the requested reference image.
        String htmlBody =
                "<!doctype html><html><body style='margin:0;background:#f2f6f9;font-family:Arial,Helvetica,sans-serif;color:#173c59;padding:18px;'>"
                        + "<div style='max-width:620px;margin:0 auto;background:#ffffff;border-radius:16px;padding:30px;box-shadow:0 6px 18px rgba(0,0,0,.08);'>"
                        + "<div style='text-align:center;margin-bottom:12px;'>"
                        + "<img src='cid:marinersMentorLogo' alt='Mariners Mentor' style='max-width:78px;max-height:78px;margin-bottom:10px;'>"
                        + "</div>"
                        + "<p style='font-size:15px;margin:0 0 18px;'>Dear <strong>" + escapeHtml(name) + "</strong>,</p>"
                        + "<h2 style='text-align:center;color:#173c59;margin:0 0 22px;font-size:25px;'>Cumulative Contribution Amount</h2>"
                        + "<div style='margin:0 0 20px;padding:28px 15px;text-align:center;background:#eaf7ef;border:1px solid #9fd5b1;border-radius:10px;color:#087333;font-size:38px;font-weight:800;letter-spacing:.4px;'>"
                        + escapeHtml(amount)
                        + "</div>"
                        + "<div style='text-align:center;margin:0 0 18px;'>"
                        + "<span style='display:inline-block;background:#0875bd;color:#ffffff;border:2px solid #111;padding:11px 24px;border-radius:8px;font-size:16px;font-weight:700;'>More Details</span>"
                        + "</div>"
                        + "<table width='100%' cellpadding='0' cellspacing='0' style='border-collapse:collapse;border:1px solid #147db9;font-size:14px;'>"
                        + "<tr><th colspan='3' style='background:#147db9;color:#fff;padding:9px;border:1px solid #ffffff;'>Vessel Name</th></tr>"
                        + "<tr>"
                        + "<th style='background:#147db9;color:#fff;padding:9px;border:1px solid #ffffff;'>DOO (D/M/Y)</th>"
                        + "<th style='background:#147db9;color:#fff;padding:9px;border:1px solid #ffffff;'>DOD (D/M/Y)</th>"
                        + "<th style='background:#147db9;color:#fff;padding:9px;border:1px solid #ffffff;'>Contribution Amount (US $)</th>"
                        + "</tr>"
                        + tableRows
                        + "</table>"
                        + "<p style='margin:24px 0 0;color:#333;'>With Regards,<br><strong style='color:#087333;'>Mariners Mentor</strong></p>"
                        + "</div></body></html>";

        MimeMessage message = new MimeMessage(session);
        message.setFrom(new InternetAddress(
                username,
                fromName,
                StandardCharsets.UTF_8.name()
        ));
        message.setRecipients(
                Message.RecipientType.TO,
                InternetAddress.parse(candidateEmail.trim())
        );
        message.setSubject(
                "JSU Balance - Mariners Mentor",
                StandardCharsets.UTF_8.name()
        );

        MimeBodyPart htmlPart = new MimeBodyPart();
        htmlPart.setContent(htmlBody, "text/html; charset=UTF-8");

        MimeMultipart relatedMultipart = new MimeMultipart("related");
        relatedMultipart.addBodyPart(htmlPart);
        relatedMultipart.addBodyPart(createLogoPart());

        message.setContent(relatedMultipart);
        message.saveChanges();
        sendMessage(config, message);

        System.out.println(
                "JSU BALANCE ZOHO MAIL SENT"
                        + " | To: " + candidateEmail
                        + " | Amount: " + amount
                        + " | Vessels: " + (contributionRows == null ? 0 : contributionRows.size())
        );
    }

    public static void sendSpfoBalanceMail(
            Properties config,
            String customerName,
            String candidateEmail,
            String availablePfBalance,
            String availablePfYear,
            java.util.List<String[]> ledgerRows
    ) throws Exception {
        sendSpfoBalanceMail(
                config,
                customerName,
                candidateEmail,
                availablePfBalance,
                availablePfYear,
                ledgerRows,
                null,
                ""
        );
    }

    public static void sendSpfoBalanceMail(
            Properties config,
            String customerName,
            String candidateEmail,
            String availablePfBalance,
            String availablePfYear,
            java.util.List<String[]> ledgerRows,
            byte[] officialLedgerPdf,
            String officialLedgerPdfFileName
    ) throws Exception {

        if (candidateEmail == null || candidateEmail.isBlank()) {
            throw new IllegalArgumentException("Candidate email is missing.");
        }

        String host = config.getProperty("mail.smtp.host", "smtp.zoho.in").trim();
        String port = config.getProperty("mail.smtp.port", "587").trim();
        String username = required(config, "mail.smtp.username");
        String password = required(config, "mail.smtp.password");
        String fromName = config.getProperty("mail.from.name", "Mariners Mentor").trim();

        String name = customerName == null || customerName.isBlank()
                ? "Candidate"
                : customerName.trim();

        String available = availablePfBalance == null || availablePfBalance.isBlank()
                ? "Not available"
                : availablePfBalance.trim();

        String asPerYear = availablePfYear == null ? "" : availablePfYear.trim();

        Properties mailProperties = new Properties();
        mailProperties.put("mail.smtp.auth", "true");
        mailProperties.put("mail.smtp.host", host);
        mailProperties.put("mail.smtp.port", port);
        mailProperties.put("mail.smtp.ssl.enable", "false");
        mailProperties.put("mail.smtp.starttls.enable", "true");
        mailProperties.put("mail.smtp.starttls.required", "true");
        mailProperties.put("mail.smtp.ssl.protocols", "TLSv1.2 TLSv1.3");
        mailProperties.put("mail.smtp.connectiontimeout", "30000");
        mailProperties.put("mail.smtp.timeout", "30000");
        mailProperties.put("mail.smtp.writetimeout", "30000");

        Session session = Session.getInstance(
                mailProperties,
                new Authenticator() {
                    @Override
                    protected PasswordAuthentication getPasswordAuthentication() {
                        return new PasswordAuthentication(username, password);
                    }
                }
        );

        StringBuilder rows = new StringBuilder();
        int index = 0;

        if (ledgerRows != null) {
            for (String[] row : ledgerRows) {
                if (row == null || row.length < 11) {
                    continue;
                }

                String first = safeMailValue(row[0], "");

                // Financial-year separator row, matching the SPFO ledger layout.
                if (first.matches("(?:19|20)\\d{2}-\\d{2}")) {
                    rows.append("<tr><td colspan='11' style='padding:8px 10px;background:#dce9f7;"
                                    + "border:1px solid #b9cde2;font-weight:900;color:#173c59;text-align:left;'>")
                            .append(escapeHtml(first))
                            .append("</td></tr>");
                    continue;
                }

                String type = first.toUpperCase(Locale.ROOT);
                boolean balanceRow = type.equals("BALANCE");
                boolean openingRow = type.equals("O.B") || type.equals("OB");

                String bg;
                if (balanceRow) {
                    bg = "#e8f7ed";
                } else if (openingRow) {
                    bg = "#eef4fb";
                } else {
                    bg = (index++ % 2 == 0) ? "#ffffff" : "#f7faff";
                }

                rows.append("<tr>")
                        .append(spfoLedgerTextCell(row[0], bg, true, false))
                        .append(spfoLedgerTextCell(row[1], bg, false, true))
                        .append(spfoLedgerTextCell(row[2], bg, false, true))
                        .append(spfoLedgerTextCell(row[3], bg, false, true))
                        .append(spfoLedgerMoneyCell(row[4], bg, balanceRow))
                        .append(spfoLedgerMoneyCell(row[5], bg, balanceRow))
                        .append(spfoLedgerMoneyCell(row[6], bg, balanceRow))
                        .append(spfoLedgerMoneyCell(row[7], bg, balanceRow))
                        .append(spfoLedgerMoneyCell(row[8], bg, balanceRow))
                        .append(spfoLedgerMoneyCell(row[9], bg, balanceRow))
                        .append(spfoLedgerTextCell(row[10], bg, false, false))
                        .append("</tr>");
            }
        }

        if (rows.length() == 0) {
            rows.append("<tr><td colspan='11' style='padding:14px;text-align:center;color:#666;border:1px solid #d6e2ea;'>")
                    .append("No SPFO ledger details available.")
                    .append("</td></tr>");
        }

        String yearLine = asPerYear.isBlank()
                ? ""
                : "<div style='margin-top:6px;font-size:13px;color:#4b6b58;'>As per "
                  + escapeHtml(asPerYear) + " financial year</div>";

        String htmlBody =
                "<!doctype html><html><body style='margin:0;background:#edf5f8;font-family:Arial,Helvetica,sans-serif;color:#173c59;padding:18px;'>"
                        + "<div style='max-width:1500px;margin:0 auto;background:#ffffff;border-radius:16px;overflow:hidden;box-shadow:0 6px 18px rgba(0,0,0,.08);'>"
                        + "<div style='background:#0875bd;padding:24px;text-align:center;color:#fff;'>"
                        + "<img src='cid:marinersMentorLogo' alt='Mariners Mentor' style='max-width:72px;max-height:72px;margin-bottom:8px;'>"
                        + "<div style='font-size:26px;font-weight:800;'>SPFO Provident Fund Balance</div>"
                        + "</div>"
                        + "<div style='padding:24px;'>"
                        + "<p style='font-size:15px;margin:0 0 20px;'>Dear <strong>" + escapeHtml(name) + "</strong>,</p>"
                        + "<div style='margin:0 0 22px;padding:20px;text-align:center;background:#eaf7ef;border:2px solid #7dc99a;border-radius:12px;'>"
                        + "<div style='font-size:14px;font-weight:700;color:#456b55;margin-bottom:7px;'>AVAILABLE PF BALANCE</div>"
                        + "<div style='font-size:36px;font-weight:900;color:#087333;'>" + escapeHtml(available) + "</div>"
                        + yearLine
                        + "</div>"
                        + "<div style='margin:0 0 16px;padding:12px 14px;background:#fff7dd;border:1px solid #ead184;border-radius:8px;color:#5d4b14;font-size:13px;font-weight:700;'>"
                        + "SPFO Seafarer Ledger Document PDF with Mariners Mentor watermark is attached to this email."
                        + "</div>"
                        + "<div style='font-size:17px;font-weight:800;margin:0 0 10px;color:#173c59;'>Full PF Ledger Statement</div>"
                        + "<div style='overflow-x:auto;'>"
                        + "<table width='100%' cellpadding='0' cellspacing='0' style='border-collapse:collapse;min-width:1480px;border:1px solid #b9cde2;font-size:11px;'>"
                        + "<tr>"
                        + mailHeader("Type / Financial Year")
                        + mailHeader("Year / Inward No")
                        + mailHeader("Sign on date")
                        + mailHeader("Sign off date")
                        + mailHeader("Seafarer's Contribution")
                        + mailHeader("Employer's Contribution")
                        + mailHeader("Voluntary Contribution")
                        + mailHeader("Exgratia Contribution")
                        + mailHeader("Pension Annuity Contribution")
                        + mailHeader("Total")
                        + mailHeader("Remarks")
                        + "</tr>"
                        + rows
                        + "</table></div>"
                        + "<p style='margin:25px 0 0;color:#333;'>With Regards,<br><strong style='color:#087333;'>Mariners Mentor</strong></p>"
                        + "</div></div></body></html>";

        MimeMessage message = new MimeMessage(session);
        message.setFrom(new InternetAddress(
                username,
                fromName,
                StandardCharsets.UTF_8.name()
        ));
        message.setRecipients(
                Message.RecipientType.TO,
                InternetAddress.parse(candidateEmail.trim())
        );
        message.setSubject(
                "SPFO Balance - Mariners Mentor",
                StandardCharsets.UTF_8.name()
        );

        MimeBodyPart htmlPart = new MimeBodyPart();
        htmlPart.setContent(htmlBody, "text/html; charset=UTF-8");

        MimeMultipart relatedMultipart = new MimeMultipart("related");
        relatedMultipart.addBodyPart(htmlPart);
        relatedMultipart.addBodyPart(createLogoPart());

        MimeMultipart mixedMultipart = new MimeMultipart("mixed");

        MimeBodyPart relatedContainer = new MimeBodyPart();
        relatedContainer.setContent(relatedMultipart);
        mixedMultipart.addBodyPart(relatedContainer);

        if (officialLedgerPdf != null && officialLedgerPdf.length > 0) {
            String attachmentName =
                    officialLedgerPdfFileName == null || officialLedgerPdfFileName.isBlank()
                            ? "Seafarer_Ledger_Document.pdf"
                            : officialLedgerPdfFileName.trim();

            MimeBodyPart pdfPart = new MimeBodyPart();
            ByteArrayDataSource pdfData = new ByteArrayDataSource(
                    officialLedgerPdf,
                    "application/pdf"
            );
            pdfPart.setDataHandler(new DataHandler(pdfData));
            pdfPart.setFileName(attachmentName);
            pdfPart.setDisposition("attachment");
            mixedMultipart.addBodyPart(pdfPart);
        }

        message.setContent(mixedMultipart);
        message.saveChanges();
        sendMessage(config, message);

        System.out.println(
                "SPFO BALANCE ZOHO MAIL SENT"
                        + " | To: " + candidateEmail
                        + " | Available PF: " + available
                        + " | Ledger rows: " + (ledgerRows == null ? 0 : ledgerRows.size())
                        + " | Watermarked PDF attached: "
                        + (officialLedgerPdf != null && officialLedgerPdf.length > 0)
        );
    }

    // 7410 COMPILE FIX: sendSpfoInterestUpdateMail 6-argument overload is present below.
    public static void sendSpfoInterestUpdateMail(
            Properties config,
            String customerName,
            String candidateEmail,
            String balanceAmount,
            byte[] updatedStatementPdf,
            String updatedStatementFileName
    ) throws Exception {

        if (candidateEmail == null || candidateEmail.isBlank()) {
            throw new IllegalArgumentException("Candidate email is missing.");
        }
        if (updatedStatementPdf == null || updatedStatementPdf.length < 5) {
            throw new IllegalArgumentException("SPFO Interest updated PDF is missing.");
        }

        String host = config.getProperty("mail.smtp.host", "smtp.zoho.in").trim();
        String port = config.getProperty("mail.smtp.port", "587").trim();
        String username = required(config, "mail.smtp.username");
        String password = required(config, "mail.smtp.password");
        String fromName = config.getProperty("mail.from.name", "Mariners Mentor").trim();

        String name = customerName == null || customerName.isBlank()
                ? "Candidate"
                : customerName.trim();
        String amount = balanceAmount == null || balanceAmount.isBlank()
                ? "Not available"
                : balanceAmount.trim();

        Properties mailProperties = new Properties();
        mailProperties.put("mail.smtp.auth", "true");
        mailProperties.put("mail.smtp.host", host);
        mailProperties.put("mail.smtp.port", port);
        mailProperties.put("mail.smtp.ssl.enable", "false");
        mailProperties.put("mail.smtp.starttls.enable", "true");
        mailProperties.put("mail.smtp.starttls.required", "true");
        mailProperties.put("mail.smtp.ssl.protocols", "TLSv1.2 TLSv1.3");
        mailProperties.put("mail.smtp.connectiontimeout", "30000");
        mailProperties.put("mail.smtp.timeout", "30000");
        mailProperties.put("mail.smtp.writetimeout", "30000");

        Session session = Session.getInstance(
                mailProperties,
                new Authenticator() {
                    @Override
                    protected PasswordAuthentication getPasswordAuthentication() {
                        return new PasswordAuthentication(username, password);
                    }
                }
        );

        String htmlBody =
                "<!doctype html><html><body style='margin:0;background:#f4f7f5;font-family:Arial,Helvetica,sans-serif;color:#24352c;padding:20px;'>"
                        + "<div style='max-width:720px;margin:0 auto;background:#fff;border-radius:14px;overflow:hidden;box-shadow:0 5px 18px rgba(0,0,0,.08);'>"
                        + "<div style='background:#0b6b3a;padding:20px;text-align:center;color:#fff;'>"
                        + "<img src='cid:marinersMentorLogo' alt='Mariners Mentor' style='max-width:70px;max-height:70px;margin-bottom:8px;'>"
                        + "<div style='font-size:24px;font-weight:800;'>SPFO Interest Updated</div>"
                        + "</div>"
                        + "<div style='padding:24px;'>"
                        + "<p>Dear <strong>" + escapeHtml(name) + "</strong>,</p>"
                        + "<p>Your SPFO interest has been updated. The updated statement is attached to this email.</p>"
                        + "<div style='margin:20px 0;padding:18px;text-align:center;background:#edf8f1;border:1px solid #a8d4b8;border-radius:10px;'>"
                        + "<div style='font-size:13px;font-weight:700;color:#456b55;'>UPDATED INTEREST AMOUNT</div>"
                        + "<div style='font-size:34px;font-weight:900;color:#0b6b3a;margin-top:6px;'>" + escapeHtml(amount) + "</div>"
                        + "</div>"
                        + "<p style='margin-top:24px;'>With Regards,<br><strong style='color:#0b6b3a;'>Mariners Mentor</strong></p>"
                        + "</div></div></body></html>";

        MimeMessage message = new MimeMessage(session);
        message.setFrom(new InternetAddress(
                username,
                fromName,
                StandardCharsets.UTF_8.name()
        ));
        message.setRecipients(
                Message.RecipientType.TO,
                InternetAddress.parse(candidateEmail.trim())
        );
        message.setSubject(
                "SPFO Interest Updated - Mariners Mentor",
                StandardCharsets.UTF_8.name()
        );

        MimeBodyPart htmlPart = new MimeBodyPart();
        htmlPart.setContent(htmlBody, "text/html; charset=UTF-8");

        MimeMultipart relatedMultipart = new MimeMultipart("related");
        relatedMultipart.addBodyPart(htmlPart);
        relatedMultipart.addBodyPart(createLogoPart());

        MimeMultipart mixedMultipart = new MimeMultipart("mixed");
        MimeBodyPart relatedContainer = new MimeBodyPart();
        relatedContainer.setContent(relatedMultipart);
        mixedMultipart.addBodyPart(relatedContainer);

        String attachmentName = updatedStatementFileName == null || updatedStatementFileName.isBlank()
                ? "Mariners_Mentor_SPFO_Interest_Update.pdf"
                : updatedStatementFileName.trim();

        MimeBodyPart pdfPart = new MimeBodyPart();
        ByteArrayDataSource pdfData = new ByteArrayDataSource(
                updatedStatementPdf,
                "application/pdf"
        );
        pdfPart.setDataHandler(new DataHandler(pdfData));
        pdfPart.setFileName(attachmentName);
        pdfPart.setDisposition("attachment");
        mixedMultipart.addBodyPart(pdfPart);

        message.setContent(mixedMultipart);
        message.saveChanges();
        sendMessage(config, message);

        System.out.println(
                "SPFO INTEREST UPDATE ZOHO MAIL SENT"
                        + " | To: " + candidateEmail
                        + " | Balance: " + amount
                        + " | PDF: " + attachmentName
        );
    }

    private static String spfoLedgerMoneyCell(String value, String bg, boolean balanceRow) {
        String shown = safeMailValue(value, "₹0");
        return "<td style='padding:8px 7px;border:1px solid #cbd9e6;background:"
                + bg
                + ";text-align:right;white-space:nowrap;font-weight:"
                + (balanceRow ? "900" : "700")
                + ";color:#087333;'>"
                + escapeHtml(shown)
                + "</td>";
    }

    private static String spfoLedgerTextCell(
            String value,
            String bg,
            boolean bold,
            boolean nowrap
    ) {
        String shown = safeMailValue(value, "-");
        return "<td style='padding:8px 7px;border:1px solid #cbd9e6;background:"
                + bg
                + ";vertical-align:top;text-align:left;"
                + (nowrap ? "white-space:nowrap;" : "white-space:normal;line-height:1.35;")
                + (bold ? "font-weight:900;color:#173c59;" : "font-weight:600;color:#334f63;")
                + "'>"
                + escapeHtml(shown)
                + "</td>";
    }


    private static String mailHeader(String value) {
        return "<th style='background:#173c59;color:#fff;padding:10px 8px;border:1px solid #d6e2ea;text-align:center;font-size:12px;'>"
                + escapeHtml(value)
                + "</th>";
    }

    private static String mailCell(String value, String bg, boolean bold) {
        return "<td style='padding:11px 8px;border:1px solid #d6e2ea;background:"
                + bg
                + ";text-align:center;white-space:nowrap;"
                + (bold ? "font-weight:800;color:#173c59;" : "font-weight:700;color:#087333;")
                + "'>"
                + escapeHtml(safeMailValue(value, "₹0"))
                + "</td>";
    }

    private static String safeMailValue(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim();
    }


    public static void sendReminderMail(
            Properties config,
            String customerName,
            String candidateEmail,
            String serviceName,
            String reminderAction,
            String eventDate,
            String location,
            String assignedTo
    ) throws Exception {

        if (candidateEmail == null
                || candidateEmail.isBlank()) {

            throw new IllegalArgumentException(
                    "Candidate email is missing."
            );
        }

        String host =
                config.getProperty(
                        "mail.smtp.host",
                        "smtp.zoho.in"
                ).trim();

        String port =
                config.getProperty(
                        "mail.smtp.port",
                        "587"
                ).trim();

        String username =
                required(
                        config,
                        "mail.smtp.username"
                );

        String password =
                required(
                        config,
                        "mail.smtp.password"
                );

        String fromName =
                config.getProperty(
                        "mail.from.name",
                        "Mariners Mentor"
                ).trim();

        Properties mailProperties =
                new Properties();

        mailProperties.put(
                "mail.smtp.auth",
                "true"
        );

        mailProperties.put(
                "mail.smtp.host",
                host
        );

        mailProperties.put(
                "mail.smtp.port",
                port
        );

        mailProperties.put(
                "mail.smtp.ssl.enable",
                "false"
        );

        mailProperties.put(
                "mail.smtp.starttls.enable",
                "true"
        );

        mailProperties.put(
                "mail.smtp.starttls.required",
                "true"
        );

        mailProperties.put(
                "mail.smtp.ssl.protocols",
                "TLSv1.2 TLSv1.3"
        );

        Session session =
                Session.getInstance(
                        mailProperties,
                        new Authenticator() {

                            @Override
                            protected PasswordAuthentication
                            getPasswordAuthentication() {

                                return new PasswordAuthentication(
                                        username,
                                        password
                                );
                            }
                        }
                );

        String name =
                customerName == null
                        || customerName.isBlank()
                        ? "Candidate"
                        : customerName.trim();

        String service =
                serviceName == null
                        || serviceName.isBlank()
                        ? "Requested Service"
                        : serviceName.trim();

        String coordinator =
                assignedTo == null
                        || assignedTo.isBlank()
                        ? "Mariners Mentor Team"
                        : assignedTo.trim();

        String coordinatorPhone =
                getCoordinatorPhone(
                        config,
                        coordinator
                );

        String googleReviewLink =
                config.getProperty(
                        "company.google.review.url",
                        "https://share.google/wXknZM3g0mhWMzKD8"
                ).trim();

        String htmlBody =
                "<!DOCTYPE html>"
                        + "<html><body style=\""
                        + "margin:0;"
                        + "background:#f3f6f8;"
                        + "font-family:Arial,sans-serif;"
                        + "color:#23313d;"
                        + "\">"
                        + "<table width=\"100%\" "
                        + "cellpadding=\"0\" cellspacing=\"0\">"
                        + "<tr><td align=\"center\" "
                        + "style=\"padding:25px 10px;\">"
                        + "<table width=\"620\" "
                        + "cellpadding=\"0\" cellspacing=\"0\" "
                        + "style=\"max-width:620px;"
                        + "background:#ffffff;"
                        + "border-radius:10px;"
                        + "overflow:hidden;\">"
                        + "<tr><td align=\"center\" "
                        + "style=\"background:#087333;padding:20px;\">"
                        + "<img src=\"cid:marinersMentorLogo\" "
                        + "alt=\"Mariners Mentor\" "
                        + "style=\"max-height:65px;max-width:220px;\">"
                        + "</td></tr>"
                        + "<tr><td style=\"padding:30px;\">"
                        + "<h2 style=\"margin:0 0 20px;"
                        + "color:#087333;\">Service Reminder</h2>"
                        + "<p>Dear "
                        + escapeHtml(name)
                        + ",</p>"
                        + "<p>Greetings from Mariners Mentor!</p>"
                        + "<p style=\"line-height:1.7;\">"
                        + "This is to remind you that you are scheduled to "
                        + "<strong>"
                        + escapeHtml(reminderAction)
                        + "</strong> on "
                        + "<strong>"
                        + escapeHtml(eventDate)
                        + "</strong> at "
                        + "<strong>"
                        + escapeHtml(location)
                        + "</strong>."
                        + "</p>"
                        + "<p><strong>Service:</strong> "
                        + escapeHtml(service)
                        + "</p>"
                        + "<p>Your assigned coordinator is "
                        + "<strong>"
                        + escapeHtml(coordinator)
                        + "</strong> ("
                        + escapeHtml(
                        "+" + coordinatorPhone
                )
                        + ").</p>"
                        + "<table align=\"center\" "
                        + "cellpadding=\"0\" cellspacing=\"0\" "
                        + "style=\"margin:25px auto;\">"
                        + "<tr>"
                        + createButton(
                        googleReviewLink,
                        "Google Review",
                        "#f59e0b"
                )
                        + "</tr></table>"
                        + "<p>Thank you.</p>"
                        + "<p><strong>Mariners Mentor</strong></p>"
                        + "</td></tr>"
                        + "<tr><td align=\"center\" style=\""
                        + "background:#f8f9fa;"
                        + "padding:14px;"
                        + "font-size:12px;"
                        + "color:#777;\">"
                        + "This is an automated service reminder."
                        + "</td></tr>"
                        + "</table></td></tr></table>"
                        + "</body></html>";

        MimeMessage message =
                new MimeMessage(
                        session
                );

        message.setFrom(
                new InternetAddress(
                        username,
                        fromName,
                        StandardCharsets.UTF_8.name()
                )
        );

        message.setRecipients(
                Message.RecipientType.TO,
                InternetAddress.parse(
                        candidateEmail.trim()
                )
        );


        message.setSubject(
                "Reminder - "
                        + service
                        + " on "
                        + eventDate,
                StandardCharsets.UTF_8.name()
        );

        MimeBodyPart htmlPart =
                new MimeBodyPart();

        htmlPart.setContent(
                htmlBody,
                "text/html; charset=UTF-8"
        );

        MimeMultipart relatedMultipart =
                new MimeMultipart("related");

        relatedMultipart.addBodyPart(
                htmlPart
        );

        relatedMultipart.addBodyPart(
                createLogoPart()
        );

        message.setContent(
                relatedMultipart
        );

        message.saveChanges();

        sendMessage(config, message);

        System.out.println(
                "REMINDER EMAIL SENT"
                        + " | To: "
                        + candidateEmail
                        + " | Service: "
                        + service
        );
    }

    public static void sendReminderDeliveryStatusMail(
            Properties config,
            long caseId,
            String customerName,
            String serviceName,
            String eventDate,
            String location,
            String whatsappStatus,
            String mailStatus
    ) throws Exception {

        String reportEmail = config.getProperty(
                "report.mail.account",
                ""
        ).trim();

        if (reportEmail.isBlank()) {
            return;
        }

        String host = config.getProperty("mail.smtp.host", "smtp.zoho.in").trim();
        String port = config.getProperty("mail.smtp.port", "587").trim();
        String username = required(config, "mail.smtp.username");
        String password = required(config, "mail.smtp.password");
        String fromName = config.getProperty(
                "report.mail.from.name",
                "Mariners Mentor"
        ).trim();

        Properties mailProperties = new Properties();
        mailProperties.put("mail.smtp.auth", "true");
        mailProperties.put("mail.smtp.host", host);
        mailProperties.put("mail.smtp.port", port);
        mailProperties.put("mail.smtp.ssl.enable", "false");
        mailProperties.put("mail.smtp.starttls.enable", "true");
        mailProperties.put("mail.smtp.starttls.required", "true");
        mailProperties.put("mail.smtp.ssl.protocols", "TLSv1.2 TLSv1.3");
        mailProperties.put("mail.smtp.connectiontimeout", "30000");
        mailProperties.put("mail.smtp.timeout", "30000");
        mailProperties.put("mail.smtp.writetimeout", "30000");

        Session session = Session.getInstance(
                mailProperties,
                new Authenticator() {
                    @Override
                    protected PasswordAuthentication getPasswordAuthentication() {
                        return new PasswordAuthentication(username, password);
                    }
                }
        );

        String name = customerName == null || customerName.isBlank()
                ? "Candidate"
                : customerName.trim();
        String service = serviceName == null || serviceName.isBlank()
                ? "Requested Service"
                : serviceName.trim();

        String subject =
                "Reminder Delivery Status - Case " + caseId
                        + " - " + service;

        String htmlBody =
                "<!doctype html><html><body style='font-family:Arial,sans-serif;background:#f3f6f8;padding:20px;color:#222;'>"
                        + "<div style='max-width:680px;margin:auto;background:#fff;padding:24px;border-radius:12px;'>"
                        + "<h2 style='color:#087333;margin-top:0;'>Reminder Delivery Status</h2>"
                        + "<p><strong>Case ID:</strong> " + caseId + "</p>"
                        + "<p><strong>Candidate:</strong> " + escapeHtml(name) + "</p>"
                        + "<p><strong>Service:</strong> " + escapeHtml(service) + "</p>"
                        + "<p><strong>Reminder Date:</strong> " + escapeHtml(eventDate) + "</p>"
                        + "<p><strong>Location:</strong> " + escapeHtml(location) + "</p>"
                        + "<p><strong>WhatsApp:</strong> " + escapeHtml(whatsappStatus) + "</p>"
                        + "<p><strong>Email:</strong> " + escapeHtml(mailStatus) + "</p>"
                        + "<p style='font-size:12px;color:#666;margin-top:22px;'>Automatically generated by MMcasesBot after a reminder delivery attempt.</p>"
                        + "</div></body></html>";

        MimeMessage message = new MimeMessage(session);
        message.setFrom(new InternetAddress(
                username,
                fromName,
                StandardCharsets.UTF_8.name()
        ));
        message.setRecipients(
                Message.RecipientType.TO,
                InternetAddress.parse(reportEmail)
        );
        message.setSubject(subject, StandardCharsets.UTF_8.name());
        message.setContent(htmlBody, "text/html; charset=UTF-8");
        message.saveChanges();

        Transport.send(message);

        System.out.println(
                "REMINDER ADMIN STATUS MAIL SENT"
                        + " | Case: " + caseId
                        + " | To: " + reportEmail
        );
    }


    private static MimeBodyPart createLogoPart()
            throws Exception {

        byte[] logoBytes;

        try (InputStream logoStream =
                     ZohoMailService.class
                             .getClassLoader()
                             .getResourceAsStream(
                                     LOGO_RESOURCE
                             )) {

            if (logoStream == null) {

                throw new IllegalStateException(
                        "Logo file not found. Put the logo here: "
                                + "src/main/resources/"
                                + LOGO_RESOURCE
                );
            }

            logoBytes =
                    logoStream.readAllBytes();
        }

        ByteArrayDataSource dataSource =
                new ByteArrayDataSource(
                        logoBytes,
                        "image/png"
                );

        MimeBodyPart logoPart =
                new MimeBodyPart();

        logoPart.setDataHandler(
                new DataHandler(dataSource)
        );

        logoPart.setHeader(
                "Content-ID",
                "<marinersMentorLogo>"
        );

        logoPart.setHeader(
                "Content-Location",
                LOGO_RESOURCE
        );

        logoPart.setDisposition(
                MimeBodyPart.INLINE
        );

        logoPart.setFileName(
                LOGO_RESOURCE
        );

        return logoPart;
    }

    public static String getCoordinatorPhone(
            Properties config,
            String assignedTo
    ) {

        String coordinatorKey =
                normalizeCoordinatorKey(
                        assignedTo
                );

        String propertyKey =
                "coordinator."
                        + coordinatorKey
                        + ".phone";

        String phone =
                config.getProperty(
                        propertyKey,
                        ""
                ).trim();

        if (phone.isBlank()) {

            phone =
                    config.getProperty(
                            "coordinator.default.phone",
                            ""
                    ).trim();

            System.out.println(
                    "Coordinator phone not configured for: "
                            + assignedTo
                            + ". Using default phone."
            );
        }

        phone =
                normalizeIndianPhone(
                        phone
                );

        if (phone.isBlank()) {

            throw new IllegalStateException(
                    "Coordinator phone not found. Add: "
                            + propertyKey
                            + "=91XXXXXXXXXX"
            );
        }

        return phone;
    }

    private static String createButton(
            String link,
            String text,
            String background
    ) {

        return "<td style=\"padding:5px;\">"
                + "<a href=\""
                + escapeHtml(link)
                + "\" "
                + "target=\"_blank\" "
                + "style=\""
                + "display:inline-block;"
                + "background:"
                + background
                + ";"
                + "color:#ffffff;"
                + "text-decoration:none;"
                + "font-weight:bold;"
                + "font-size:14px;"
                + "padding:13px 20px;"
                + "border-radius:6px;"
                + "\">"
                + escapeHtml(text)
                + "</a>"
                + "</td>";
    }

    private static String normalizeCoordinatorKey(
            String assignedTo
    ) {

        if (assignedTo == null
                || assignedTo.isBlank()) {

            return "default";
        }

        String normalizedName =
                assignedTo
                        .trim()
                        .toLowerCase(Locale.ROOT)
                        .replaceAll(
                                "[^a-z0-9]+",
                                " "
                        )
                        .replaceAll(
                                "\\s+",
                                " "
                        )
                        .trim();

        if (normalizedName.contains("jaya")) {
            return "jaya";
        }

        if (normalizedName.contains("priya")) {
            return "priya";
        }

        if (normalizedName.contains("subha")) {
            return "subha";
        }

        if (normalizedName.contains("amal")) {
            return "amal";
        }

        return normalizedName.replace(
                " ",
                "."
        );
    }

    private static String normalizeIndianPhone(
            String phone
    ) {

        if (phone == null) {
            return "";
        }

        String normalized =
                phone.replaceAll(
                        "\\D",
                        ""
                );

        if (normalized.startsWith("0")
                && normalized.length() == 11) {

            normalized =
                    normalized.substring(1);
        }

        if (normalized.length() == 10) {
            normalized = "91" + normalized;
        }

        return normalized;
    }
// =====================================================
// JSU BALANCE QUERY
// =====================================================

    private static boolean isJsuBalanceQuery(
            String serviceName
    ) {

        if (serviceName == null
                || serviceName.isBlank()) {

            return false;
        }

        String service =
                serviceName
                        .trim()
                        .toLowerCase(Locale.ROOT)
                        .replaceAll(
                                "[^a-z0-9]+",
                                " "
                        )
                        .replaceAll(
                                "\\s+",
                                " "
                        )
                        .trim();

        return service.equals("jsu balance query");
    }


// =====================================================
// SPFO BALANCE QUERY - RESERVED FOR UPCOMING CHECKER
// =====================================================

    private static boolean isSpfoBalanceQuery(
            String serviceName
    ) {

        if (serviceName == null || serviceName.isBlank()) {
            return false;
        }

        String service =
                serviceName
                        .trim()
                        .toLowerCase(Locale.ROOT)
                        .replaceAll("[^a-z0-9]+", " ")
                        .replaceAll("\\s+", " ")
                        .trim();

        return service.equals("spfo balance")
                || service.equals("spfo balance query")
                || service.equals("seaman provident fund balance")
                || service.equals("seaman provident fund balance query")
                || service.equals("seamen provident fund balance")
                || service.equals("seamen provident fund balance query");
    }


// =====================================================
// SPFO / SEAMAN PROVIDENT FUND
// =====================================================

    private static boolean isSpfoService(
            String serviceName
    ) {

        if (serviceName == null
                || serviceName.isBlank()) {

            return false;
        }

        String service =
                serviceName
                        .trim()
                        .toLowerCase(Locale.ROOT)
                        .replaceAll(
                                "[^a-z0-9]+",
                                " "
                        )
                        .replaceAll(
                                "\\s+",
                                " "
                        )
                        .trim();

        return service.contains("seaman provident fund")
                || service.contains("seamen provident fund")
                || service.contains("seaman s provident fund")
                || service.contains("spfo");
    }

    // =====================================================
    // JSU / JAPAN PF / RETIREMENT PAY PLAN
    // =====================================================

    private static boolean isJsuService(
            String serviceName
    ) {

        if (serviceName == null
                || serviceName.isBlank()) {

            return false;
        }

        String service =
                serviceName
                        .trim()
                        .toLowerCase(Locale.ROOT)
                        .replaceAll(
                                "[^a-z0-9]+",
                                " "
                        )
                        .replaceAll(
                                "\\s+",
                                " "
                        )
                        .trim();

        return service.equals("jsu")
                || service.equals("jsu retired")
                || service.equals("jsu retire")
                || service.equals("jsu retirement")
                || service.equals("jsu retirement claim")
                || service.equals("jsu retired claim")
                || service.equals("jsu claim")
                || service.equals("jsu claim all")
                || service.equals("claim all")
                || service.contains("japan pf")
                || service.contains("japan pension")
                || service.contains("retirement pay plan");
    }

    private static String escapeHtml(
            String value
    ) {

        if (value == null) {
            return "";
        }

        return value
                .replace(
                        "&",
                        "&amp;"
                )
                .replace(
                        "<",
                        "&lt;"
                )
                .replace(
                        ">",
                        "&gt;"
                )
                .replace(
                        "\"",
                        "&quot;"
                )
                .replace(
                        "'",
                        "&#39;"
                );
    }
    public static void sendAdminWelcomeReport(
            Properties config,
            String htmlRows
    ) throws Exception {

        String reportEmail = config.getProperty(
                "report.mail.account",
                ""
        ).trim();

        if (reportEmail.isBlank()) {
            System.out.println("ADMIN REPORT DISABLED | report.mail.account is blank");
            return;
        }

        String host = config.getProperty("mail.smtp.host", "smtp.zoho.in").trim();
        String port = config.getProperty("mail.smtp.port", "587").trim();
        String username = required(config, "mail.smtp.username");
        String password = required(config, "mail.smtp.password");
        String fromName = config.getProperty("report.mail.from.name", "Mariners Mentor").trim();

        Properties mailProperties = new Properties();
        mailProperties.put("mail.smtp.auth", "true");
        mailProperties.put("mail.smtp.host", host);
        mailProperties.put("mail.smtp.port", port);
        mailProperties.put("mail.smtp.ssl.enable", "false");
        mailProperties.put("mail.smtp.starttls.enable", "true");
        mailProperties.put("mail.smtp.starttls.required", "true");
        mailProperties.put("mail.smtp.ssl.protocols", "TLSv1.2 TLSv1.3");
        mailProperties.put("mail.smtp.connectiontimeout", "30000");
        mailProperties.put("mail.smtp.timeout", "30000");
        mailProperties.put("mail.smtp.writetimeout", "30000");

        Session session = Session.getInstance(
                mailProperties,
                new Authenticator() {
                    @Override
                    protected PasswordAuthentication getPasswordAuthentication() {
                        return new PasswordAuthentication(username, password);
                    }
                }
        );

        String today = java.time.LocalDate.now(
                java.time.ZoneId.of("Asia/Kolkata")
        ).format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy"));

        String subject = "Mariners Mentor - MMcasesBot Report - " + today;
        String rows = htmlRows == null || htmlRows.isBlank()
                ? "<tr><td colspan='5' style='padding:14px;text-align:center;'>No cases processed.</td></tr>"
                : htmlRows;

        String htmlBody =
                "<!doctype html><html><body style='font-family:Arial,sans-serif;background:#f3f6f8;padding:20px;color:#222;'>"
                        + "<div style='max-width:1100px;margin:auto;background:#fff;padding:24px;border-radius:12px;'>"
                        + "<h2 style='color:#087333;margin:0 0 8px;'>Mariners Mentor - MMcasesBot Report</h2>"
                        + "<p style='margin:0 0 20px;'><strong>Date:</strong> " + escapeHtml(today) + "</p>"
                        + "<div style='overflow-x:auto;'>"
                        + "<table width='100%' cellpadding='0' cellspacing='0' style='border-collapse:collapse;font-size:13px;'>"
                        + "<thead><tr style='background:#087333;color:#fff;'>"
                        + "<th style='padding:9px;border:1px solid #d7e0e6;'>Case ID</th>"
                        + "<th style='padding:9px;border:1px solid #d7e0e6;'>CASE CREATED BY</th>"
                        + "<th style='padding:9px;border:1px solid #d7e0e6;'>WA</th>"
                        + "<th style='padding:9px;border:1px solid #d7e0e6;'>REM</th>"
                        + "<th style='padding:9px;border:1px solid #d7e0e6;'>MAIL</th>"
                        + "</tr></thead><tbody>"
                        + rows
                        + "</tbody></table></div>"
                        + "<p style='margin-top:18px;font-size:12px;color:#666;'>Scheduled Mariners Mentor status report.</p>"
                        + "</div></body></html>";

        MimeMessage message = new MimeMessage(session);
        message.setFrom(new InternetAddress(
                username,
                fromName,
                StandardCharsets.UTF_8.name()
        ));
        message.setRecipients(
                Message.RecipientType.TO,
                InternetAddress.parse(reportEmail)
        );
        message.setSubject(subject, StandardCharsets.UTF_8.name());
        message.setContent(htmlBody, "text/html; charset=UTF-8");
        message.saveChanges();

        Transport.send(message);

        System.out.println(
                "FINAL ADMIN REPORT SENT ON PROGRAM STOP"
                        + " | From: " + username
                        + " | To: " + reportEmail
        );
    }

    public static void sendMmMembershipStatusReport(
            Properties config,
            long caseId,
            String customerId,
            String customerName,
            String serviceName,
            String assignedTo,
            CustomerProfileBackfillService.SyncReport report,
            String mailStatus,
            String whatsappStatus
    ) throws Exception {

        String reportEmail = config.getProperty(
                "report.mail.account",
                ""
        ).trim();

        if (reportEmail.isBlank()) {
            throw new IllegalStateException("report.mail.account is missing.");
        }

        String host = config.getProperty("mail.smtp.host", "smtp.zoho.in").trim();
        String port = config.getProperty("mail.smtp.port", "587").trim();
        String username = required(config, "mail.smtp.username");
        String password = required(config, "mail.smtp.password");
        String fromName = config.getProperty(
                "report.mail.from.name",
                "Mariners Mentor"
        ).trim();

        Properties mailProperties = new Properties();
        mailProperties.put("mail.smtp.auth", "true");
        mailProperties.put("mail.smtp.host", host);
        mailProperties.put("mail.smtp.port", port);
        mailProperties.put("mail.smtp.ssl.enable", "false");
        mailProperties.put("mail.smtp.starttls.enable", "true");
        mailProperties.put("mail.smtp.starttls.required", "true");
        mailProperties.put("mail.smtp.ssl.protocols", "TLSv1.2 TLSv1.3");
        mailProperties.put("mail.smtp.connectiontimeout", "30000");
        mailProperties.put("mail.smtp.timeout", "30000");
        mailProperties.put("mail.smtp.writetimeout", "30000");

        Session session = Session.getInstance(
                mailProperties,
                new Authenticator() {
                    @Override
                    protected PasswordAuthentication getPasswordAuthentication() {
                        return new PasswordAuthentication(username, password);
                    }
                }
        );

        int scanned = report == null ? 0 : report.scanned();
        int dgRead = report == null ? 0 : report.dgProfilesRead();
        int updated = report == null ? 0 : report.updated();
        int noChange = report == null ? 0 : report.noChange();
        int skipped = report == null ? 0 : report.skipped();

        StringBuilder missing = new StringBuilder();
        if (report != null && report.missingCredentials() != null) {
            for (CustomerProfileBackfillService.MissingCredential row : report.missingCredentials()) {
                if (row == null) continue;
                if (missing.length() > 0) missing.append("<br>");
                missing.append("<strong>Credential:</strong> ")
                        .append(escapeHtml(
                                row.passwordStatus() == null || row.passwordStatus().isBlank()
                                        ? "Missing INDoS / password"
                                        : row.passwordStatus()
                        ));
            }
        }

        if (report != null && report.missingFields() != null) {
            for (CustomerProfileBackfillService.MissingField row : report.missingFields()) {
                if (row == null) continue;
                if (missing.length() > 0) missing.append("<br>");
                missing.append("<strong>Missing:</strong> ")
                        .append(escapeHtml(row.fields()))
                        .append(
                                row.detail() == null || row.detail().isBlank()
                                        ? ""
                                        : " - " + escapeHtml(row.detail())
                        );
            }
        }

        if (missing.length() == 0) {
            missing.append("None");
        }

        StringBuilder notes = new StringBuilder();
        if (report != null && report.notes() != null) {
            for (String note : report.notes()) {
                if (note == null || note.isBlank()) continue;
                if (notes.length() > 0) notes.append("<br>");
                notes.append("• ").append(escapeHtml(note));
            }
        }
        if (notes.length() == 0) {
            notes.append("-");
        }

        boolean dataComplete =
                report != null
                        && scanned > 0
                        && skipped == 0
                        && (updated + noChange) > 0;

        boolean communicationsComplete =
                mailStatus != null
                        && whatsappStatus != null
                        && !mailStatus.toUpperCase(Locale.ROOT).startsWith("FAILED")
                        && !whatsappStatus.toUpperCase(Locale.ROOT).startsWith("FAILED");

        String overall = dataComplete && communicationsComplete
                ? "COMPLETED"
                : "ATTENTION REQUIRED";

        String today = java.time.LocalDateTime.now(
                java.time.ZoneId.of("Asia/Kolkata")
        ).format(java.time.format.DateTimeFormatter.ofPattern(
                "dd/MM/yyyy hh:mm a"
        ));

        String subject =
                "MM Membership Report - Case " + caseId
                        + " - " + overall;

        String htmlBody =
                "<!doctype html><html><body style='font-family:Arial,sans-serif;background:#f4f6f8;padding:20px;color:#222;'>"
                        + "<div style='max-width:850px;margin:auto;background:#fff;padding:22px;border-radius:12px;'>"
                        + "<h2 style='color:#087333;margin:0 0 8px;'>Mariners Mentor Membership - Processing Report</h2>"
                        + "<p style='margin:0 0 18px;'><strong>Status:</strong> "
                        + escapeHtml(overall)
                        + " &nbsp; | &nbsp; <strong>Time:</strong> "
                        + escapeHtml(today)
                        + "</p>"
                        + "<table width='100%' cellpadding='0' cellspacing='0' style='border-collapse:collapse;font-size:13px;'>"
                        + reportInfoRow("Case ID", String.valueOf(caseId))
                        + reportInfoRow("Customer ID", customerId)
                        + reportInfoRow("Candidate", customerName)
                        + reportInfoRow("Service", serviceName)
                        + reportInfoRow("Assigned To", assignedTo)
                        + reportInfoRow("DG Profile Read", dgRead > 0 ? "YES" : "NO")
                        + reportInfoRow("MM Customer Update",
                                updated > 0 ? "UPDATED" : (noChange > 0 ? "NO CHANGE NEEDED" : "NOT COMPLETED"))
                        + reportInfoRow("Skipped / Failed", String.valueOf(skipped))
                        + reportInfoRow("Candidate Email", mailStatus)
                        + reportInfoRow("Candidate WhatsApp", whatsappStatus)
                        + "</table>"
                        + "<h3 style='margin:20px 0 8px;color:#b42318;'>Missing / Attention</h3>"
                        + "<div style='padding:12px;border:1px solid #ead1d1;background:#fff7f7;border-radius:8px;'>"
                        + missing
                        + "</div>"
                        + "<h3 style='margin:20px 0 8px;color:#173c59;'>DG / Sheet / RPSL Notes</h3>"
                        + "<div style='padding:12px;border:1px solid #d6e2ea;background:#f7fbff;border-radius:8px;'>"
                        + notes
                        + "</div>"
                        + "</div></body></html>";

        MimeMessage message = new MimeMessage(session);
        message.setFrom(new InternetAddress(
                username,
                fromName,
                StandardCharsets.UTF_8.name()
        ));
        message.setRecipients(
                Message.RecipientType.TO,
                InternetAddress.parse(reportEmail)
        );
        message.setSubject(
                subject,
                StandardCharsets.UTF_8.name()
        );
        message.setContent(
                htmlBody,
                "text/html; charset=UTF-8"
        );
        message.saveChanges();

        Transport.send(message);

        System.out.println(
                "MM MEMBERSHIP STATUS REPORT SENT"
                        + " | Case: " + caseId
                        + " | Customer ID: " + customerId
                        + " | Status: " + overall
                        + " | To: " + reportEmail
        );
    }

    private static String reportInfoRow(
            String label,
            String value
    ) {
        return "<tr>"
                + "<td style='width:34%;padding:9px;border:1px solid #d7e0e6;background:#f6f8fa;font-weight:700;'>"
                + escapeHtml(label)
                + "</td>"
                + "<td style='padding:9px;border:1px solid #d7e0e6;'>"
                + escapeHtml(value == null || value.isBlank() ? "-" : value)
                + "</td>"
                + "</tr>";
    }


    public static void sendCustomerProfileSyncReport(
            Properties config,
            String reason,
            CustomerProfileBackfillService.SyncReport report
    ) throws Exception {

        if (report == null) {
            return;
        }

        String reportEmail = config.getProperty(
                "report.mail.account",
                ""
        ).trim();

        if (reportEmail.isBlank()) {
            throw new IllegalStateException("report.mail.account is missing.");
        }

        String host = config.getProperty("mail.smtp.host", "smtp.zoho.in").trim();
        String port = config.getProperty("mail.smtp.port", "587").trim();
        String username = required(config, "mail.smtp.username");
        String password = required(config, "mail.smtp.password");
        String fromName = config.getProperty("report.mail.from.name", "Mariners Mentor").trim();

        Properties mailProperties = new Properties();
        mailProperties.put("mail.smtp.auth", "true");
        mailProperties.put("mail.smtp.host", host);
        mailProperties.put("mail.smtp.port", port);
        mailProperties.put("mail.smtp.ssl.enable", "false");
        mailProperties.put("mail.smtp.starttls.enable", "true");
        mailProperties.put("mail.smtp.starttls.required", "true");
        mailProperties.put("mail.smtp.ssl.protocols", "TLSv1.2 TLSv1.3");
        mailProperties.put("mail.smtp.connectiontimeout", "30000");
        mailProperties.put("mail.smtp.timeout", "30000");
        mailProperties.put("mail.smtp.writetimeout", "30000");

        Session session = Session.getInstance(
                mailProperties,
                new Authenticator() {
                    @Override
                    protected PasswordAuthentication getPasswordAuthentication() {
                        return new PasswordAuthentication(username, password);
                    }
                }
        );

        String today = java.time.LocalDate.now(
                java.time.ZoneId.of("Asia/Kolkata")
        ).format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy"));

        StringBuilder attentionRows = new StringBuilder();
        int serial = 0;
        int noIndosNo = 0;
        int noIndosPassword = 0;
        int wrongPassword = 0;

        if (report.missingCredentials() != null) {
            for (CustomerProfileBackfillService.MissingCredential row : report.missingCredentials()) {
                if (row == null) continue;

                String raw = row.passwordStatus() == null ? "" : row.passwordStatus().trim();
                String lower = raw.toLowerCase(Locale.ROOT);
                String status;
                if (lower.contains("wrong")) {
                    status = "Wrong INDoS Password";
                    wrongPassword++;
                } else if (lower.contains("password")) {
                    status = "No INDoS Password";
                    noIndosPassword++;
                } else {
                    status = "No INDoS No";
                    noIndosNo++;
                }

                serial++;
                String createdBy = row.createdBy() == null || row.createdBy().isBlank()
                        ? "-" : row.createdBy().trim();
                String createdAt = row.createdAt() == null || row.createdAt().isBlank()
                        ? "-" : row.createdAt().trim();

                attentionRows.append("<tr>")
                        .append("<td style='padding:8px;border:1px solid #d7dce1;text-align:center;'>")
                        .append(serial)
                        .append("</td>")
                        .append("<td style='padding:8px;border:1px solid #d7dce1;'>")
                        .append(escapeHtml(row.customerId()))
                        .append("</td>")
                        .append("<td style='padding:8px;border:1px solid #d7dce1;'>")
                        .append(escapeHtml(createdBy))
                        .append("</td>")
                        .append("<td style='padding:8px;border:1px solid #d7dce1;'>")
                        .append(escapeHtml(createdAt))
                        .append("</td>")
                        .append("<td style='padding:8px;border:1px solid #d7dce1;font-weight:bold;'>")
                        .append(escapeHtml(status))
                        .append("</td>")
                        .append("</tr>");
            }
        }

        if (attentionRows.length() == 0) {
            attentionRows.append(
                    "<tr><td colspan='5' style='padding:12px;border:1px solid #d7dce1;text-align:center;'>"
                            + "No INDoS credential issues found in this run."
                            + "</td></tr>"
            );
        }

        StringBuilder missingRows = new StringBuilder();
        int missingFieldCount = 0;
        if (report.missingFields() != null) {
            for (CustomerProfileBackfillService.MissingField row : report.missingFields()) {
                if (row == null) continue;
                missingFieldCount++;
                missingRows.append("<tr>")
                        .append("<td style='padding:8px;border:1px solid #d7dce1;text-align:center;'>")
                        .append(missingFieldCount)
                        .append("</td>")
                        .append("<td style='padding:8px;border:1px solid #d7dce1;'>")
                        .append(escapeHtml(row.customerId()))
                        .append("</td>")
                        .append("<td style='padding:8px;border:1px solid #d7dce1;'>")
                        .append(escapeHtml(row.customerName()))
                        .append("</td>")
                        .append("<td style='padding:8px;border:1px solid #d7dce1;'>")
                        .append(escapeHtml(maskIndosForReport(row.indosNo())))
                        .append("</td>")
                        .append("<td style='padding:8px;border:1px solid #d7dce1;font-weight:bold;color:#b42318;'>")
                        .append(escapeHtml(row.fields()))
                        .append("</td>")
                        .append("<td style='padding:8px;border:1px solid #d7dce1;'>")
                        .append(escapeHtml(row.detail()))
                        .append("</td>")
                        .append("</tr>");
            }
        }

        if (missingRows.length() == 0) {
            missingRows.append(
                    "<tr><td colspan='6' style='padding:12px;border:1px solid #d7dce1;text-align:center;'>"
                            + "No missing customer-profile fields found in this run."
                            + "</td></tr>"
            );
        }

        int successCases = report.updated() + report.noChange();
        int failedCases = report.skipped();

        String runReason = reason == null || reason.isBlank()
                ? "RUN"
                : reason.trim().toUpperCase(Locale.ROOT);

        String subject = "Mariners Mentor - Customer Profile Missing Report - " + today
                + " | Success " + successCases
                + " | Failed " + failedCases
                + " | Missing " + missingFieldCount;

        String htmlBody =
                "<!doctype html><html><body style='font-family:Arial,sans-serif;background:#f4f6f8;padding:20px;color:#222;'>"
                        + "<div style='max-width:900px;margin:auto;background:#fff;padding:22px;border-radius:12px;'>"
                        + "<h2 style='color:#087333;margin:0 0 6px;'>Mariners Mentor - Customer Profile Missing Report</h2>"
                        + "<p style='margin:4px 0 14px;'><strong>Date:</strong> " + escapeHtml(today)
                        + " &nbsp; | &nbsp; <strong>Run:</strong> " + escapeHtml(runReason)
                        + " &nbsp; | &nbsp; <strong>All Time Customers:</strong> " + report.customersFound()
                        + "</p>"
                        + "<table width='100%' cellpadding='0' cellspacing='0' style='border-collapse:collapse;font-size:13px;'>"
                        + "<thead><tr style='background:#087333;color:#fff;'>"
                        + "<th style='padding:9px;border:1px solid #d7dce1;'>Si.No</th>"
                        + "<th style='padding:9px;border:1px solid #d7dce1;'>Customer ID</th>"
                        + "<th style='padding:9px;border:1px solid #d7dce1;'>Created By</th>"
                        + "<th style='padding:9px;border:1px solid #d7dce1;'>Created At</th>"
                        + "<th style='padding:9px;border:1px solid #d7dce1;'>Status</th>"
                        + "</tr></thead><tbody>"
                        + attentionRows
                        + "</tbody></table>"
                        + "<h3 style='margin:22px 0 8px;color:#b42318;'>Missing Profile Data</h3>"
                        + "<table width='100%' cellpadding='0' cellspacing='0' style='border-collapse:collapse;font-size:13px;'>"
                        + "<thead><tr style='background:#b42318;color:#fff;'>"
                        + "<th style='padding:9px;border:1px solid #d7dce1;'>Si.No</th>"
                        + "<th style='padding:9px;border:1px solid #d7dce1;'>Customer ID</th>"
                        + "<th style='padding:9px;border:1px solid #d7dce1;'>Name</th>"
                        + "<th style='padding:9px;border:1px solid #d7dce1;'>INDoS</th>"
                        + "<th style='padding:9px;border:1px solid #d7dce1;'>Missing</th>"
                        + "<th style='padding:9px;border:1px solid #d7dce1;'>Detail</th>"
                        + "</tr></thead><tbody>"
                        + missingRows
                        + "</tbody></table>"
                        + "<div style='margin-top:18px;padding:14px;background:#eef8f0;border:1px solid #cfe9d4;border-radius:8px;'>"
                        + "<strong>Success Cases:</strong> " + successCases
                        + " &nbsp;&nbsp;&nbsp; <strong>Failed Cases:</strong> " + failedCases
                        + "<br><span style='font-size:12px;color:#555;'>No INDoS No: " + noIndosNo
                        + " | No INDoS Password: " + noIndosPassword
                        + " | Wrong INDoS Password: " + wrongPassword + "</span>"
                        + "</div>"
                        + "</div></body></html>";

        MimeMessage message = new MimeMessage(session);
        message.setFrom(new InternetAddress(
                username,
                fromName,
                StandardCharsets.UTF_8.name()
        ));
        message.setRecipients(
                Message.RecipientType.TO,
                InternetAddress.parse(reportEmail)
        );
        message.setSubject(subject, StandardCharsets.UTF_8.name());
        message.setContent(htmlBody, "text/html; charset=UTF-8");
        message.saveChanges();

        Transport.send(message);

        System.out.println(
                "CUSTOMER ALL TIME PROFILE REPORT SENT"
                        + " | To: " + reportEmail
                        + " | All Time customers: " + report.customersFound()
                        + " | Success: " + successCases
                        + " | Failed: " + failedCases
                        + " | No INDoS No: " + noIndosNo
                        + " | No INDoS Password: " + noIndosPassword
                        + " | Wrong Password: " + wrongPassword
                        + " | Missing Profile Rows: " + missingFieldCount
        );
    }


    private static String maskIndosForReport(String indos) {
        String s = indos == null ? "" : indos.trim();
        if (s.isBlank()) return "-";
        if (s.length() <= 4) return "****";
        return s.substring(0, 2) + "***" + s.substring(s.length() - 2);
    }

    public static void sendCustomerProfileProgressReport(
            Properties config,
            String reason,
            String status,
            CustomerProfileBackfillService.SyncProgress progress
    ) throws Exception {

        if (progress == null) {
            return;
        }

        String reportEmail = config.getProperty(
                "report.mail.account",
                ""
        ).trim();

        if (reportEmail.isBlank()) {
            throw new IllegalStateException("report.mail.account is missing.");
        }

        String host = config.getProperty("mail.smtp.host", "smtp.zoho.in").trim();
        String port = config.getProperty("mail.smtp.port", "587").trim();
        String username = required(config, "mail.smtp.username");
        String password = required(config, "mail.smtp.password");
        String fromName = config.getProperty("report.mail.from.name", "Mariners Mentor").trim();

        Properties mailProperties = new Properties();
        mailProperties.put("mail.smtp.auth", "true");
        mailProperties.put("mail.smtp.host", host);
        mailProperties.put("mail.smtp.port", port);
        mailProperties.put("mail.smtp.ssl.enable", "false");
        mailProperties.put("mail.smtp.starttls.enable", "true");
        mailProperties.put("mail.smtp.starttls.required", "true");
        mailProperties.put("mail.smtp.ssl.protocols", "TLSv1.2 TLSv1.3");
        mailProperties.put("mail.smtp.connectiontimeout", "30000");
        mailProperties.put("mail.smtp.timeout", "30000");
        mailProperties.put("mail.smtp.writetimeout", "30000");

        Session session = Session.getInstance(
                mailProperties,
                new Authenticator() {
                    @Override
                    protected PasswordAuthentication getPasswordAuthentication() {
                        return new PasswordAuthentication(username, password);
                    }
                }
        );

        String today = java.time.LocalDate.now(
                java.time.ZoneId.of("Asia/Kolkata")
        ).format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy"));

        String runReason = reason == null || reason.isBlank()
                ? "RUN"
                : reason.trim().toUpperCase(Locale.ROOT);

        String runStatus = status == null || status.isBlank()
                ? "PROGRESS"
                : status.trim().toUpperCase(Locale.ROOT);

        String subject = "Mariners Mentor - DG Customer Profile "
                + runStatus + " - " + today
                + " | " + progress.scanned() + "/" + progress.queuedForThisRun()
                + " processed";

        String htmlBody =
                "<!doctype html><html><body style='font-family:Arial,sans-serif;background:#f3f6f8;padding:20px;color:#222;'>"
                        + "<div style='max-width:850px;margin:auto;background:#fff;padding:24px;border-radius:12px;'>"
                        + "<h2 style='color:#087333;margin:0 0 8px;'>Mariners Mentor - DG Customer Profile " + escapeHtml(runStatus) + "</h2>"
                        + "<p><strong>Date:</strong> " + escapeHtml(today) + "</p>"
                        + "<p><strong>Run:</strong> " + escapeHtml(runReason) + "</p>"
                        + "<div style='background:#eef8f0;border:1px solid #cfe9d4;padding:14px;border-radius:8px;'>"
                        + "<strong>All Time customers:</strong> " + progress.customersFound()
                        + "<br><br>"
                        + "<strong>Already handled:</strong> " + progress.alreadyHandled()
                        + " &nbsp; | &nbsp; <strong>Queued this run:</strong> " + progress.queuedForThisRun()
                        + "<br><br>"
                        + "<strong>Processed:</strong> " + progress.scanned()
                        + " &nbsp; | &nbsp; <strong>Remaining:</strong> " + progress.remaining()
                        + "<br><br>"
                        + "<strong>DG profiles read:</strong> " + progress.dgProfilesRead()
                        + " &nbsp; | &nbsp; <strong style='color:#087333;'>Updated:</strong> " + progress.updated()
                        + " &nbsp; | &nbsp; <strong>No change:</strong> " + progress.noChange()
                        + " &nbsp; | &nbsp; <strong style='color:#b42318;'>Failed/Skipped:</strong> " + progress.skipped()
                        + "</div>"
                        + "<p style='font-size:12px;color:#666;margin-top:14px;'>"
                        + "The detailed final table is sent when the customer phase finishes. "
                        + "If MMcasesBot is stopped before completion, a PARTIAL progress report is sent during shutdown."
                        + "</p>"
                        + "</div></body></html>";

        MimeMessage message = new MimeMessage(session);
        message.setFrom(new InternetAddress(
                username,
                fromName,
                StandardCharsets.UTF_8.name()
        ));
        message.setRecipients(
                Message.RecipientType.TO,
                InternetAddress.parse(reportEmail)
        );
        message.setSubject(subject, StandardCharsets.UTF_8.name());
        message.setContent(htmlBody, "text/html; charset=UTF-8");
        message.saveChanges();

        Transport.send(message);

        System.out.println(
                "CUSTOMER PROFILE " + runStatus + " REPORT SENT"
                        + " | To: " + reportEmail
                        + " | Processed: " + progress.scanned()
                        + "/" + progress.queuedForThisRun()
                        + " | Updated: " + progress.updated()
                        + " | Failed/Skipped: " + progress.skipped()
        );
    }

    private static void sendMessage(
            Properties config,
            MimeMessage message
    ) throws Exception {

        // Candidate/service mail is sent only to the explicit TO recipient.
        // No CC/BCC recipient is added here.

        // All candidate/welcome/JSU emails are sent through the configured
        // Zoho SMTP account. No browser compose window is involved.
        Transport.send(message);
    }

    private static String required(
            Properties config,
            String key
    ) {

        String value =
                config.getProperty(
                        key
                );

        if (value == null
                || value.isBlank()) {

            throw new IllegalStateException(
                    key
                            + " is missing in config.properties"
            );
        }

        return value.trim();
    }
}
