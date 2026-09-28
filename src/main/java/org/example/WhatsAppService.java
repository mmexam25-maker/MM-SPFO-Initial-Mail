package org.example;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

public final class WhatsAppService {

    private static final ObjectMapper MAPPER =
            new ObjectMapper();

    private static final HttpClient HTTP_CLIENT =
            HttpClient.newBuilder()
                    .connectTimeout(
                            Duration.ofSeconds(30)
                    )
                    .build();

    /*
     * Interval between WhatsApp messages.
     * 2000 milliseconds = 2 seconds.
     */
    private static final long MESSAGE_INTERVAL_MILLISECONDS =
            3000L;

    private WhatsAppService() {
    }

    public static void sendWelcomeMessage(
            Properties config,
            String phone,
            String customerName,
            String serviceName,
            String assignedTo
    ) throws Exception {

        String accessToken =
                required(
                        config,
                        "whatsapp.access.token"
                );

        String phoneNumberId =
                required(
                        config,
                        "whatsapp.phone.number.id"
                );

        String apiVersion =
                config.getProperty(
                        "whatsapp.api.version",
                        "v23.0"
                ).trim();

        String languageCode =
                config.getProperty(
                        "whatsapp.template.language",
                        "en"
                ).trim();

        String candidatePhone =
                normalizeIndianPhone(
                        phone
                );

        if (candidatePhone.isBlank()) {

            throw new IllegalArgumentException(
                    "Candidate WhatsApp number is missing."
            );
        }

        String candidateName =
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
                        ? "Mariners Mentor"
                        : assignedTo.trim();

        String templateName =
                getTemplateName(
                        config,
                        coordinator
                );

        /*
         * CURRENT META WELCOME TEMPLATE BODY:
         *
         * {{1}} Candidate Name
         * {{2}} Service
         * {{3}} Co - Ordinator Contact
         *
         * All coordinator-specific welcome templates now use
         * exactly these 3 body parameters.
         */
        String coordinatorPhone =
                ZohoMailService.getCoordinatorPhone(
                        config,
                        coordinator
                );

        List<Map<String, Object>> bodyParameters;

        // Meta template welcome_sweety_v1 contains only:
        // {{1}} Candidate Name and {{2}} Service.
        if ("welcome_sweety_v1".equalsIgnoreCase(templateName)) {
            bodyParameters =
                    List.of(
                            textParameter(candidateName),
                            textParameter(service)
                    );
        } else {
            bodyParameters =
                    List.of(
                            textParameter(candidateName),
                            textParameter(service),
                            textParameter("+" + coordinatorPhone)
                    );
        }

        int bodyParameterCount = bodyParameters.size();

        Map<String, Object> bodyComponent =
                new LinkedHashMap<>();

        bodyComponent.put(
                "type",
                "body"
        );

        bodyComponent.put(
                "parameters",
                bodyParameters
        );

        Map<String, Object> language =
                new LinkedHashMap<>();

        language.put(
                "code",
                languageCode
        );

        Map<String, Object> template =
                new LinkedHashMap<>();

        template.put(
                "name",
                templateName
        );

        template.put(
                "language",
                language
        );

        template.put(
                "components",
                List.of(bodyComponent)
        );

        Map<String, Object> payload =
                new LinkedHashMap<>();

        payload.put(
                "messaging_product",
                "whatsapp"
        );

        payload.put(
                "recipient_type",
                "individual"
        );

        payload.put(
                "to",
                candidatePhone
        );

        payload.put(
                "type",
                "template"
        );

        payload.put(
                "template",
                template
        );

        String requestUrl =
                "https://graph.facebook.com/"
                        + apiVersion
                        + "/"
                        + phoneNumberId
                        + "/messages";

        String requestJson =
                MAPPER.writeValueAsString(
                        payload
                );

        System.out.println();
        System.out.println(
                "=============================================="
        );

        System.out.println(
                "SENDING WHATSAPP"
        );

        System.out.println(
                "Candidate Phone : "
                        + candidatePhone
        );

        System.out.println(
                "Candidate Name  : "
                        + candidateName
        );

        System.out.println(
                "Service         : "
                        + service
        );

        System.out.println(
                "Assigned To     : "
                        + coordinator
        );

        System.out.println(
                "Template        : "
                        + templateName
        );

        System.out.println(
                "Body Parameters : " + bodyParameterCount
        );

        System.out.println(
                "=============================================="
        );

        HttpRequest request =
                HttpRequest.newBuilder()
                        .uri(
                                URI.create(
                                        requestUrl
                                )
                        )
                        .timeout(
                                Duration.ofSeconds(60)
                        )
                        .header(
                                "Authorization",
                                "Bearer " + accessToken
                        )
                        .header(
                                "Content-Type",
                                "application/json"
                        )
                        .POST(
                                HttpRequest
                                        .BodyPublishers
                                        .ofString(
                                                requestJson,
                                                StandardCharsets.UTF_8
                                        )
                        )
                        .build();

        HttpResponse<String> response =
                HTTP_CLIENT.send(
                        request,
                        HttpResponse.BodyHandlers.ofString(
                                StandardCharsets.UTF_8
                        )
                );

        if (response.statusCode() < 200
                || response.statusCode() >= 300) {

            throw new IllegalStateException(
                    "WhatsApp HTTP "
                            + response.statusCode()
                            + ": "
                            + response.body()
                            + " | Template: "
                            + templateName
                            + " | Assigned To: "
                            + coordinator
                            + " | Parameters sent: "
                            + bodyParameterCount
            );
        }

        JsonNode responseRoot =
                MAPPER.readTree(
                        response.body()
                );

        JsonNode messages =
                responseRoot.path(
                        "messages"
                );

        if (!messages.isArray()
                || messages.isEmpty()) {

            throw new IllegalStateException(
                    "WhatsApp did not return messages: "
                            + response.body()
            );
        }

        String messageId =
                messages
                        .get(0)
                        .path("id")
                        .asText();

        if (messageId.isBlank()) {

            throw new IllegalStateException(
                    "WhatsApp did not return a message ID: "
                            + response.body()
            );
        }

        System.out.println(
                "WhatsApp request accepted"
                        + " | To: "
                        + candidatePhone
                        + " | Template: "
                        + templateName
                        + " | Message ID: "
                        + messageId
        );

        /*
         * Wait for 2 seconds before the program
         * sends the next WhatsApp message.
         */
        waitBeforeNextMessage();
    }

    /**
     * WhatsApp-only notification for assigned agents on any service.
     *
     * Expected approved Meta template (default: agent_notification):
     * {{1}} Agent Name
     * {{2}} Candidate Name
     * {{3}} Service
     * {{4}} Case ID
     * {{5}} Candidate Mobile
     *
     * No email is sent by this method.
     */
    public static void sendAgentNotification(
            Properties config,
            String agentPhone,
            String agentName,
            String candidateName,
            String serviceName,
            String caseId,
            String candidatePhone
    ) throws Exception {

        String templateName = config.getProperty(
                "whatsapp.agent.template.name",
                config.getProperty("whatsapp.cdc.agent.template.name", "agent_notification")
        ).trim();

        String languageCode = config.getProperty(
                "whatsapp.agent.template.language",
                config.getProperty(
                        "whatsapp.cdc.agent.template.language",
                        config.getProperty("whatsapp.template.language", "en")
                )
        ).trim();

        if (templateName.isBlank()) {
            throw new IllegalStateException(
                    "whatsapp.agent.template.name is missing in config.properties"
            );
        }

        List<Map<String, Object>> bodyParameters = List.of(
                textParameter(agentName),
                textParameter(candidateName == null || candidateName.isBlank()
                        ? "Candidate" : candidateName.trim()),
                textParameter(serviceName == null || serviceName.isBlank()
                        ? "Service" : serviceName.trim()),
                textParameter(caseId == null || caseId.isBlank()
                        ? "-" : caseId.trim()),
                textParameter(candidatePhone == null || candidatePhone.isBlank()
                        ? "Not available" : candidatePhone.trim())
        );

        Map<String, Object> bodyComponent = new LinkedHashMap<>();
        bodyComponent.put("type", "body");
        bodyComponent.put("parameters", bodyParameters);

        sendTemplateMessage(
                config,
                agentPhone,
                templateName,
                languageCode,
                List.of(bodyComponent),
                "AGENT WHATSAPP"
        );
    }

    /**
     * JSU Balance Query result template.
     *
     * Expected approved Meta template body:
     * {{1}} Candidate Name
     * {{2}} Cumulative amount plus vessel details on ONE LINE, for example:
     *       1,818 US $ • AZUL FORTUNA - 1,325 US $ • JAPAN LINDEN - 493 US $
     *
     * Meta rejects line breaks/tabs inside template variables (error 132018),
     * therefore vessel details are separated with a bullet instead of \n.
     */
    public static void sendJsuBalanceMessage(
            Properties config,
            String phone,
            String customerName,
            String balanceAmount
    ) throws Exception {

        String templateName = config.getProperty(
                "whatsapp.jsu.balance.template.name",
                "jsu_balance_result"
        ).trim();

        String languageCode = config.getProperty(
                "whatsapp.jsu.balance.template.language",
                config.getProperty("whatsapp.template.language", "en")
        ).trim();

        Map<String, Object> bodyComponent = new LinkedHashMap<>();
        bodyComponent.put("type", "body");
        bodyComponent.put(
                "parameters",
                List.of(
                        textParameter(customerName),
                        textParameter(balanceAmount)
                )
        );

        sendTemplateMessage(
                config,
                phone,
                templateName,
                languageCode,
                List.of(bodyComponent),
                "JSU BALANCE WHATSAPP"
        );
    }

    /**
     * SPFO balance result template.
     *
     * Recommended approved Meta template body:
     * Dear {{1}}, your SPFO year-wise balance is {{2}}. Total SPFO balance: {{3}}.
     *
     * {{2}} is intentionally a one-line value separated with | so it works
     * reliably as a template variable.
     */
    public static void sendSpfoBalanceMessage(
            Properties config,
            String phone,
            String customerName,
            String yearlyBalanceText,
            String totalAmount
    ) throws Exception {

        String templateName = config.getProperty(
                "whatsapp.spfo.balance.template.name",
                "spfo_balance_result"
        ).trim();

        String languageCode = config.getProperty(
                "whatsapp.spfo.balance.template.language",
                config.getProperty("whatsapp.template.language", "en")
        ).trim();

        Map<String, Object> bodyComponent = new LinkedHashMap<>();
        bodyComponent.put("type", "body");
        bodyComponent.put(
                "parameters",
                List.of(
                        textParameter(customerName),
                        textParameter(yearlyBalanceText),
                        textParameter(totalAmount)
                )
        );

        sendTemplateMessage(
                config,
                phone,
                templateName,
                languageCode,
                List.of(bodyComponent),
                "SPFO BALANCE WHATSAPP"
        );
    }

    /**
     * SPFO balance WhatsApp template used by production flow.
     *
     * Approved Meta template body has ONLY TWO variables:
     * {{1}} Candidate name
     * {{2}} Available PF Balance
     *
     * Year-wise balances are intentionally NOT sent on WhatsApp.
     * They remain in the detailed Zoho email / official ledger PDF.
     */
    public static void sendSpfoBalanceMessage(
            Properties config,
            String phone,
            String customerName,
            List<String> yearLines,
            String totalAmount
    ) throws Exception {

        String templateName = config.getProperty(
                "whatsapp.spfo.balance.template.name",
                "spfo_balance_details"
        ).trim();

        String languageCode = config.getProperty(
                "whatsapp.spfo.balance.template.language",
                config.getProperty("whatsapp.template.language", "en")
        ).trim();

        Map<String, Object> bodyComponent = new LinkedHashMap<>();
        bodyComponent.put("type", "body");
        bodyComponent.put(
                "parameters",
                List.of(
                        textParameter(customerName),
                        textParameter(totalAmount)
                )
        );

        System.out.println(
                "SPFO WHATSAPP BODY | Candidate=" + customerName
                        + " | Available PF Balance=" + totalAmount
                        + " | Params=2"
        );

        sendTemplateMessage(
                config,
                phone,
                templateName,
                languageCode,
                List.of(bodyComponent),
                "SPFO BALANCE WHATSAPP"
        );
    }

    /**
     * SPFO detailed result WITH the official SPFO ledger PDF attached as a
     * DOCUMENT header in the approved Meta template.
     *
     * Required Meta template:
     * Header: DOCUMENT
     * Body: {{1}} candidate, {{2}}..{{7}} financial-year lines,
     *       {{8}} available PF balance.
     */
    public static void sendSpfoBalanceMessage(
            Properties config,
            String phone,
            String customerName,
            List<String> yearLines,
            String totalAmount,
            byte[] officialLedgerPdf,
            String officialLedgerPdfFileName
    ) throws Exception {

        if (officialLedgerPdf == null || officialLedgerPdf.length < 5) {
            throw new IllegalArgumentException(
                    "Official SPFO ledger PDF is missing for WhatsApp."
            );
        }

        String templateName = config.getProperty(
                "whatsapp.spfo.balance.pdf.template.name",
                "spfo_balance_details_pdf"
        ).trim();

        String languageCode = config.getProperty(
                "whatsapp.spfo.balance.pdf.template.language",
                config.getProperty(
                        "whatsapp.spfo.balance.details.template.language",
                        config.getProperty("whatsapp.template.language", "en")
                )
        ).trim();

        java.util.ArrayList<String> normalizedLines = new java.util.ArrayList<>();
        if (yearLines != null) {
            for (String line : yearLines) {
                if (line != null && !line.isBlank()) {
                    normalizedLines.add(line.trim());
                }
            }
        }

        if (normalizedLines.size() > 6) {
            java.util.List<String> overflow =
                    new java.util.ArrayList<>(normalizedLines.subList(5, normalizedLines.size()));
            normalizedLines =
                    new java.util.ArrayList<>(normalizedLines.subList(0, 5));
            normalizedLines.add(String.join(" ; ", overflow));
        }

        while (normalizedLines.size() < 6) {
            normalizedLines.add("No data");
        }

        String pdfFileName =
                officialLedgerPdfFileName == null || officialLedgerPdfFileName.isBlank()
                        ? "Seafarer_Ledger_Document.pdf"
                        : officialLedgerPdfFileName.trim();

        String mediaId = uploadWhatsAppPdf(
                config,
                officialLedgerPdf,
                pdfFileName
        );

        Map<String, Object> document = new LinkedHashMap<>();
        document.put("id", mediaId);
        document.put("filename", pdfFileName);

        Map<String, Object> documentParameter = new LinkedHashMap<>();
        documentParameter.put("type", "document");
        documentParameter.put("document", document);

        Map<String, Object> headerComponent = new LinkedHashMap<>();
        headerComponent.put("type", "header");
        headerComponent.put("parameters", List.of(documentParameter));

        Map<String, Object> bodyComponent = new LinkedHashMap<>();
        bodyComponent.put("type", "body");
        bodyComponent.put(
                "parameters",
                List.of(
                        textParameter(customerName),
                        textParameter(normalizedLines.get(0)),
                        textParameter(normalizedLines.get(1)),
                        textParameter(normalizedLines.get(2)),
                        textParameter(normalizedLines.get(3)),
                        textParameter(normalizedLines.get(4)),
                        textParameter(normalizedLines.get(5)),
                        textParameter(totalAmount)
                )
        );

        sendTemplateMessage(
                config,
                phone,
                templateName,
                languageCode,
                List.of(headerComponent, bodyComponent),
                "SPFO BALANCE + PDF WHATSAPP"
        );
    }

    /**
     * SPFO Interest Update result template.
     *
     * Expected body:
     * Dear {{1}}, your updated interest amount: {{2}}
     *
     * {{1}} Candidate Name
     * {{2}} Updated interest / BALANCE amount extracted from the resolved PDF
     */
    public static void sendSpfoInterestUpdateMessage(
            Properties config,
            String phone,
            String customerName,
            String balanceAmount
    ) throws Exception {

        String templateName = config.getProperty(
                "whatsapp.spfo.interest.template.name",
                "spfo_interest_update"
        ).trim();

        String languageCode = config.getProperty(
                "whatsapp.spfo.interest.template.language",
                config.getProperty("whatsapp.template.language", "en")
        ).trim();

        String candidateName = customerName == null || customerName.isBlank()
                ? "Candidate"
                : customerName.trim();

        String amount = balanceAmount == null || balanceAmount.isBlank()
                ? "Not available"
                : balanceAmount.trim();

        Map<String, Object> bodyComponent = new LinkedHashMap<>();
        bodyComponent.put("type", "body");
        bodyComponent.put(
                "parameters",
                List.of(
                        textParameter(candidateName),
                        textParameter(amount)
                )
        );

        sendTemplateMessage(
                config,
                phone,
                templateName,
                languageCode,
                List.of(bodyComponent),
                "SPFO INTEREST UPDATE WHATSAPP"
        );
    }

    /**
     * JSU Retirement template with an OPEN MAIL URL button.
     *
     * Current approved Meta template:
     * Body {{1}} Candidate Name
     * URL button 0: signed OPEN MAIL token suffix
     * RPP No is taken from the case remarks by the server when the button opens.
     *
     * The button parameter supplied here is the signed case token suffix.
     */
    public static void sendJsuRetirementMailMessage(
            Properties config,
            String phone,
            String customerName,
            String rppNumber,
            String mailLinkSuffix
    ) throws Exception {

        String templateName = config.getProperty(
                "whatsapp.jsu.retirement.template.name",
                "jsu_retirement_mail_1"
        ).trim();

        String languageCode = config.getProperty(
                "whatsapp.jsu.retirement.template.language",
                config.getProperty("whatsapp.template.language", "en")
        ).trim();

        Map<String, Object> bodyComponent = new LinkedHashMap<>();
        bodyComponent.put("type", "body");
        bodyComponent.put(
                "parameters",
                List.of(textParameter(customerName))
        );

        Map<String, Object> buttonParameter = new LinkedHashMap<>();
        buttonParameter.put("type", "text");
        buttonParameter.put("text", mailLinkSuffix);

        Map<String, Object> buttonComponent = new LinkedHashMap<>();
        buttonComponent.put("type", "button");
        buttonComponent.put("sub_type", "url");
        buttonComponent.put("index", "0");
        buttonComponent.put("parameters", List.of(buttonParameter));

        try {
            sendTemplateMessage(
                    config,
                    phone,
                    templateName,
                    languageCode,
                    List.of(bodyComponent, buttonComponent),
                    "JSU RETIREMENT OPEN MAIL WHATSAPP"
            );
        } catch (Exception e) {
            String errorText = String.valueOf(e.getMessage());
            if (errorText.contains("132018")
                    || errorText.toLowerCase(Locale.ROOT).contains("parameters in your template")) {
                throw new IllegalStateException(
                        "Meta template '" + templateName + "' Send Mail button must be a DYNAMIC website URL ending with {{1}}. "
                                + "Set it to https://jsu-production-180b.up.railway.app/jsu-retirement-mail/{{1}} and submit the template, then retry.",
                        e
                );
            }
            throw e;
        }
    }

    public static void sendSpfoInitialMailMessage(
            Properties config,
            String phone,
            String customerName,
            String mailLinkSuffix
    ) throws Exception {
        String templateName = config.getProperty(
                "whatsapp.spfo.initial.template.name",
                "spfo_initial_mail_1"
        ).trim();
        String languageCode = config.getProperty(
                "whatsapp.spfo.initial.template.language",
                config.getProperty("whatsapp.template.language", "en")
        ).trim();
        Map<String,Object> body = new LinkedHashMap<>();
        body.put("type","body");
        body.put("parameters",List.of(textParameter(customerName)));
        Map<String,Object> bp = new LinkedHashMap<>(); bp.put("type","text"); bp.put("text",mailLinkSuffix);
        Map<String,Object> button = new LinkedHashMap<>(); button.put("type","button"); button.put("sub_type","url"); button.put("index","0"); button.put("parameters",List.of(bp));
        try {
            sendTemplateMessage(config,phone,templateName,languageCode,List.of(body,button),"SPFO INITIAL OPEN MAIL WHATSAPP");
        } catch(Exception e) {
            String t=String.valueOf(e.getMessage());
            if(t.contains("132018") || t.toLowerCase(Locale.ROOT).contains("parameters in your template")) {
                throw new IllegalStateException("Meta template '"+templateName+"' button must be a DYNAMIC URL ending with {{1}}. Set it to your Railway URL /spfo-initial-mail/{{1}}.",e);
            }
            throw e;
        }
    }

    public static void sendBirthdayWish(
            Properties config,
            String phone,
            String candidateName,
            java.time.LocalDate birthdayDate
    ) throws Exception {

        String templateName =
                config.getProperty(
                        "birthday.whatsapp.template.name",
                        "birthday_wish_with_poster"
                ).trim();

        String languageCode =
                config.getProperty(
                        "birthday.whatsapp.template.language",
                        "en"
                ).trim();

        String name =
                candidateName == null || candidateName.isBlank()
                        ? "Seafarer"
                        : candidateName.trim();

        byte[] posterBytes =
                createBirthdayPoster(
                        name,
                        birthdayDate
                );

        String mediaId =
                uploadWhatsAppImage(
                        config,
                        posterBytes,
                        safeBirthdayFileName(name) + "_birthday.png"
                );

        Map<String, Object> image =
                new LinkedHashMap<>();
        image.put("id", mediaId);

        Map<String, Object> headerImageParameter =
                new LinkedHashMap<>();
        headerImageParameter.put("type", "image");
        headerImageParameter.put("image", image);

        Map<String, Object> headerComponent =
                new LinkedHashMap<>();
        headerComponent.put("type", "header");
        headerComponent.put(
                "parameters",
                List.of(headerImageParameter)
        );

        Map<String, Object> bodyComponent =
                new LinkedHashMap<>();
        bodyComponent.put("type", "body");
        bodyComponent.put(
                "parameters",
                List.of(textParameter(name))
        );

        sendTemplateMessage(
                config,
                phone,
                templateName,
                languageCode,
                List.of(
                        headerComponent,
                        bodyComponent
                ),
                "BIRTHDAY WHATSAPP"
        );

        System.out.println(
                "BIRTHDAY WHATSAPP SENT"
                        + " | Name: " + name
                        + " | Phone: " + normalizeIndianPhone(phone)
        );
    }

    private static byte[] createBirthdayPoster(
            String candidateName,
            java.time.LocalDate birthdayDate
    ) throws Exception {

        int width = 1080;
        int height = 1080;

        BufferedImage image =
                new BufferedImage(
                        width,
                        height,
                        BufferedImage.TYPE_INT_RGB
                );

        Graphics2D g =
                image.createGraphics();

        try {

            g.setRenderingHint(
                    RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON
            );

            g.setRenderingHint(
                    RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON
            );

            Color brandGreen =
                    new Color(
                            30,
                            127,
                            45
                    );

            g.setColor(Color.WHITE);
            g.fillRect(0, 0, width, height);

            g.setColor(brandGreen);
            g.fillRect(0, 0, width, 210);
            g.fillRect(0, height - 125, width, 125);

            g.setColor(Color.WHITE);
            g.setFont(
                    new Font(
                            "SansSerif",
                            Font.BOLD,
                            72
                    )
            );

            drawCenteredText(
                    g,
                    "HAPPY BIRTHDAY",
                    width,
                    135
            );

            g.setColor(
                    new Color(
                            42,
                            42,
                            42
                    )
            );

            Font nameFont =
                    fitFont(
                            g,
                            candidateName.toUpperCase(Locale.ROOT),
                            width - 120,
                            72,
                            42
                    );

            g.setFont(nameFont);

            drawCenteredText(
                    g,
                    candidateName.toUpperCase(Locale.ROOT),
                    width,
                    490
            );

            g.setColor(brandGreen);
            g.setFont(
                    new Font(
                            "SansSerif",
                            Font.BOLD,
                            42
                    )
            );

            String dateText =
                    birthdayDate == null
                            ? ""
                            : birthdayDate.format(
                                    java.time.format.DateTimeFormatter.ofPattern(
                                            "dd MMMM",
                                            Locale.ENGLISH
                                    )
                            ).toUpperCase(Locale.ROOT);

            if (!dateText.isBlank()) {
                drawCenteredText(
                        g,
                        dateText,
                        width,
                        585
                );
            }

            g.setColor(
                    new Color(
                            75,
                            75,
                            75
                    )
            );

            g.setFont(
                    new Font(
                            "SansSerif",
                            Font.PLAIN,
                            36
                    )
            );

            drawCenteredText(
                    g,
                    "Wishing you happiness, success",
                    width,
                    720
            );

            drawCenteredText(
                    g,
                    "and smooth sailing always.",
                    width,
                    775
            );

            g.setColor(Color.WHITE);
            g.setFont(
                    new Font(
                            "SansSerif",
                            Font.BOLD,
                            38
                    )
            );

            drawCenteredText(
                    g,
                    "MARINERS MENTOR",
                    width,
                    height - 50
            );

        } finally {
            g.dispose();
        }

        ByteArrayOutputStream out =
                new ByteArrayOutputStream();

        ImageIO.write(
                image,
                "png",
                out
        );

        return out.toByteArray();
    }

    private static Font fitFont(
            Graphics2D g,
            String text,
            int maxWidth,
            int startSize,
            int minSize
    ) {

        int size = startSize;

        while (size > minSize) {

            Font font =
                    new Font(
                            "SansSerif",
                            Font.BOLD,
                            size
                    );

            FontMetrics metrics =
                    g.getFontMetrics(font);

            if (metrics.stringWidth(text) <= maxWidth) {
                return font;
            }

            size -= 2;
        }

        return new Font(
                "SansSerif",
                Font.BOLD,
                minSize
        );
    }

    private static void drawCenteredText(
            Graphics2D g,
            String text,
            int canvasWidth,
            int baselineY
    ) {

        FontMetrics metrics =
                g.getFontMetrics();

        int x =
                Math.max(
                        20,
                        (canvasWidth - metrics.stringWidth(text)) / 2
                );

        g.drawString(
                text,
                x,
                baselineY
        );
    }

    private static String uploadWhatsAppImage(
            Properties config,
            byte[] imageBytes,
            String fileName
    ) throws Exception {

        String accessToken =
                required(
                        config,
                        "whatsapp.access.token"
                );

        String phoneNumberId =
                required(
                        config,
                        "whatsapp.phone.number.id"
                );

        String apiVersion =
                config.getProperty(
                        "whatsapp.api.version",
                        config.getProperty(
                                "whatsapp.graph.version",
                                "v23.0"
                        )
                ).trim();

        String boundary =
                "----MMBirthdayBoundary"
                        + System.nanoTime();

        ByteArrayOutputStream body =
                new ByteArrayOutputStream();

        writeMultipartText(
                body,
                boundary,
                "messaging_product",
                "whatsapp"
        );

        writeMultipartText(
                body,
                boundary,
                "type",
                "image/png"
        );

        body.write(
                ("--" + boundary + "\r\n")
                        .getBytes(
                                StandardCharsets.UTF_8
                        )
        );

        body.write(
                ("Content-Disposition: form-data; name=\"file\"; filename=\""
                        + safeFileName(fileName)
                        + "\"\r\n")
                        .getBytes(
                                StandardCharsets.UTF_8
                        )
        );

        body.write(
                "Content-Type: image/png\r\n\r\n"
                        .getBytes(
                                StandardCharsets.UTF_8
                        )
        );

        body.write(imageBytes);

        body.write(
                "\r\n"
                        .getBytes(
                                StandardCharsets.UTF_8
                        )
        );

        body.write(
                ("--" + boundary + "--\r\n")
                        .getBytes(
                                StandardCharsets.UTF_8
                        )
        );

        String requestUrl =
                "https://graph.facebook.com/"
                        + apiVersion
                        + "/"
                        + phoneNumberId
                        + "/media";

        HttpRequest request =
                HttpRequest.newBuilder()
                        .uri(
                                URI.create(
                                        requestUrl
                                )
                        )
                        .timeout(
                                Duration.ofSeconds(90)
                        )
                        .header(
                                "Authorization",
                                "Bearer " + accessToken
                        )
                        .header(
                                "Content-Type",
                                "multipart/form-data; boundary="
                                        + boundary
                        )
                        .POST(
                                HttpRequest.BodyPublishers.ofByteArray(
                                        body.toByteArray()
                                )
                        )
                        .build();

        HttpResponse<String> response =
                HTTP_CLIENT.send(
                        request,
                        HttpResponse.BodyHandlers.ofString(
                                StandardCharsets.UTF_8
                        )
                );

        if (response.statusCode() < 200
                || response.statusCode() >= 300) {

            throw new IllegalStateException(
                    "Birthday image upload failed. HTTP "
                            + response.statusCode()
                            + ": "
                            + response.body()
            );
        }

        String mediaId =
                MAPPER.readTree(
                        response.body()
                )
                        .path("id")
                        .asText("");

        if (mediaId.isBlank()) {
            throw new IllegalStateException(
                    "Birthday image upload did not return a media ID: "
                            + response.body()
            );
        }

        return mediaId;
    }

    private static String safeBirthdayFileName(
            String value
    ) {

        String result =
                value == null
                        ? "Birthday"
                        : value.trim();

        result =
                result.replaceAll(
                        "[^A-Za-z0-9_-]+",
                        "_"
                );

        if (result.isBlank()) {
            result = "Birthday";
        }

        return result;
    }


    private static String uploadWhatsAppPdf(
            Properties config,
            byte[] pdfBytes,
            String fileName
    ) throws Exception {

        String accessToken = required(config, "whatsapp.access.token");
        String phoneNumberId = required(config, "whatsapp.phone.number.id");
        String apiVersion = config.getProperty(
                "whatsapp.api.version",
                config.getProperty("whatsapp.graph.version", "v23.0")
        ).trim();

        String boundary = "----MMCaseBotBoundary" + System.nanoTime();
        ByteArrayOutputStream body = new ByteArrayOutputStream();

        writeMultipartText(body, boundary, "messaging_product", "whatsapp");
        writeMultipartText(body, boundary, "type", "application/pdf");

        body.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(("Content-Disposition: form-data; name=\"file\"; filename=\""
                + safeFileName(fileName) + "\"\r\n").getBytes(StandardCharsets.UTF_8));
        body.write("Content-Type: application/pdf\r\n\r\n".getBytes(StandardCharsets.UTF_8));
        body.write(pdfBytes);
        body.write("\r\n".getBytes(StandardCharsets.UTF_8));
        body.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));

        String requestUrl = "https://graph.facebook.com/"
                + apiVersion + "/" + phoneNumberId + "/media";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(requestUrl))
                .timeout(Duration.ofSeconds(90))
                .header("Authorization", "Bearer " + accessToken)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
                .build();

        HttpResponse<String> response = HTTP_CLIENT.send(
                request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
        );

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException(
                    "SPFO WhatsApp PDF upload failed. HTTP "
                            + response.statusCode() + ": " + response.body()
            );
        }

        String mediaId = MAPPER.readTree(response.body()).path("id").asText("");
        if (mediaId.isBlank()) {
            throw new IllegalStateException(
                    "SPFO WhatsApp PDF upload did not return a media ID: "
                            + response.body()
            );
        }

        System.out.println(
                "SPFO WHATSAPP PDF UPLOADED"
                        + " | File: " + fileName
                        + " | Bytes: " + pdfBytes.length
                        + " | Media ID: " + mediaId
        );

        return mediaId;
    }

    private static void writeMultipartText(
            ByteArrayOutputStream out,
            String boundary,
            String name,
            String value
    ) throws Exception {
        out.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(("Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n")
                .getBytes(StandardCharsets.UTF_8));
        out.write((value + "\r\n").getBytes(StandardCharsets.UTF_8));
    }

    private static String safeFileName(String value) {
        String fileName = value == null ? "" : value.trim();
        if (fileName.isBlank()) {
            fileName = "Seafarer_Ledger_Document.pdf";
        }
        return fileName.replace("\\", "_").replace("\"", "_").replace("\r", "_").replace("\n", "_");
    }

    private static void sendTemplateMessage(
            Properties config,
            String phone,
            String templateName,
            String languageCode,
            List<Map<String, Object>> components,
            String logLabel
    ) throws Exception {

        String accessToken = required(config, "whatsapp.access.token");
        String phoneNumberId = required(config, "whatsapp.phone.number.id");

        String apiVersion = config.getProperty(
                "whatsapp.api.version",
                config.getProperty("whatsapp.graph.version", "v23.0")
        ).trim();

        String candidatePhone = normalizeIndianPhone(phone);

        Map<String, Object> language = new LinkedHashMap<>();
        language.put("code", languageCode);

        Map<String, Object> template = new LinkedHashMap<>();
        template.put("name", templateName);
        template.put("language", language);
        if (components != null && !components.isEmpty()) {
            template.put("components", components);
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("messaging_product", "whatsapp");
        payload.put("recipient_type", "individual");
        payload.put("to", candidatePhone);
        payload.put("type", "template");
        payload.put("template", template);

        String requestUrl = "https://graph.facebook.com/"
                + apiVersion + "/" + phoneNumberId + "/messages";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(requestUrl))
                .timeout(Duration.ofSeconds(60))
                .header("Authorization", "Bearer " + accessToken)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        MAPPER.writeValueAsString(payload),
                        StandardCharsets.UTF_8
                ))
                .build();

        HttpResponse<String> response = HTTP_CLIENT.send(
                request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
        );

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException(
                    logLabel + " failed. HTTP "
                            + response.statusCode() + ": " + response.body()
                            + " | Template: " + templateName
            );
        }

        JsonNode messages = MAPPER.readTree(response.body()).path("messages");
        if (!messages.isArray()
                || messages.isEmpty()
                || messages.get(0).path("id").asText().isBlank()) {
            throw new IllegalStateException(
                    logLabel + " did not return a message ID: " + response.body()
            );
        }

        System.out.println(
                logLabel + " ACCEPTED"
                        + " | To: " + candidatePhone
                        + " | Template: " + templateName
        );

        waitBeforeNextMessage();
    }

    /*
     * Approved reminder template body parameters:
     *
     * {{1}} Candidate Name
     * {{2}} Reminder Action
     * {{3}} Event Date (dd/MM/yyyy)
     * {{4}} Full Location
     * {{5}} Assigned Coordinator
     * {{6}} Coordinator Phone
     *
     * Add the Google Review URL as a static button while
     * creating the approved Meta template.
     */
    public static void sendReminderMessage(
            Properties config,
            String phone,
            String customerName,
            String reminderAction,
            String eventDate,
            String location,
            String assignedTo
    ) throws Exception {

        String accessToken =
                required(
                        config,
                        "whatsapp.access.token"
                );

        String phoneNumberId =
                required(
                        config,
                        "whatsapp.phone.number.id"
                );

        String apiVersion =
                config.getProperty(
                        "whatsapp.api.version",
                        "v23.0"
                ).trim();

        String languageCode =
                config.getProperty(
                        "whatsapp.reminder.template.language",
                        config.getProperty(
                                "whatsapp.template.language",
                                "en"
                        )
                ).trim();

        String templateName =
                required(
                        config,
                        "whatsapp.reminder.template.name"
                );

        String candidatePhone =
                normalizeIndianPhone(
                        phone
                );

        if (candidatePhone.isBlank()) {
            throw new IllegalArgumentException(
                    "Candidate WhatsApp number is missing."
            );
        }

        String coordinator =
                assignedTo == null
                        || assignedTo.isBlank()
                        ? "Mariners Mentor"
                        : assignedTo.trim();

        String coordinatorPhone =
                ZohoMailService.getCoordinatorPhone(
                        config,
                        coordinator
                );

        List<Map<String, Object>> bodyParameters =
                List.of(
                        textParameter(customerName),
                        textParameter(reminderAction),
                        textParameter(eventDate),
                        textParameter(location),
                        textParameter(coordinator),
                        textParameter(
                                "+" + coordinatorPhone
                        )
                );

        Map<String, Object> bodyComponent =
                new LinkedHashMap<>();

        bodyComponent.put(
                "type",
                "body"
        );

        bodyComponent.put(
                "parameters",
                bodyParameters
        );

        Map<String, Object> language =
                new LinkedHashMap<>();

        language.put(
                "code",
                languageCode
        );

        Map<String, Object> template =
                new LinkedHashMap<>();

        template.put(
                "name",
                templateName
        );

        template.put(
                "language",
                language
        );

        template.put(
                "components",
                List.of(bodyComponent)
        );

        Map<String, Object> payload =
                new LinkedHashMap<>();

        payload.put(
                "messaging_product",
                "whatsapp"
        );

        payload.put(
                "recipient_type",
                "individual"
        );

        payload.put(
                "to",
                candidatePhone
        );

        payload.put(
                "type",
                "template"
        );

        payload.put(
                "template",
                template
        );

        String requestUrl =
                "https://graph.facebook.com/"
                        + apiVersion
                        + "/"
                        + phoneNumberId
                        + "/messages";

        HttpRequest request =
                HttpRequest.newBuilder()
                        .uri(
                                URI.create(
                                        requestUrl
                                )
                        )
                        .timeout(
                                Duration.ofSeconds(60)
                        )
                        .header(
                                "Authorization",
                                "Bearer " + accessToken
                        )
                        .header(
                                "Content-Type",
                                "application/json"
                        )
                        .POST(
                                HttpRequest.BodyPublishers.ofString(
                                        MAPPER.writeValueAsString(
                                                payload
                                        ),
                                        StandardCharsets.UTF_8
                                )
                        )
                        .build();

        HttpResponse<String> response =
                HTTP_CLIENT.send(
                        request,
                        HttpResponse.BodyHandlers.ofString(
                                StandardCharsets.UTF_8
                        )
                );

        if (response.statusCode() < 200
                || response.statusCode() >= 300) {

            throw new IllegalStateException(
                    "Reminder WhatsApp failed. HTTP "
                            + response.statusCode()
                            + ": "
                            + response.body()
            );
        }

        JsonNode messages =
                MAPPER.readTree(
                        response.body()
                ).path("messages");

        if (!messages.isArray()
                || messages.isEmpty()
                || messages.get(0)
                .path("id")
                .asText()
                .isBlank()) {

            throw new IllegalStateException(
                    "Reminder WhatsApp did not return "
                            + "a message ID: "
                            + response.body()
            );
        }

        System.out.println(
                "REMINDER WHATSAPP ACCEPTED"
                        + " | To: "
                        + candidatePhone
                        + " | Coordinator: "
                        + coordinator
                        + " | Template: "
                        + templateName
        );

        waitBeforeNextMessage();
    }

    private static void waitBeforeNextMessage() {

        try {

            System.out.println(
                    "Waiting 2 seconds before next WhatsApp message..."
            );

            Thread.sleep(
                    MESSAGE_INTERVAL_MILLISECONDS
            );

        } catch (InterruptedException e) {

            Thread.currentThread().interrupt();

            throw new IllegalStateException(
                    "WhatsApp message interval was interrupted.",
                    e
            );
        }
    }

    private static String getTemplateName(
            Properties config,
            String assignedTo
    ) {

        String coordinatorKey =
                normalizeCoordinatorKey(
                        assignedTo
                );

        String propertyKey =
                "whatsapp.template."
                        + coordinatorKey;

        String templateName =
                config.getProperty(
                        propertyKey,
                        ""
                ).trim();

        if (!templateName.isBlank()) {

            return templateName;
        }

        String defaultTemplate =
                config.getProperty(
                        "whatsapp.template.default",
                        ""
                ).trim();

        if (!defaultTemplate.isBlank()) {

            System.out.println(
                    "Template not configured for "
                            + assignedTo
                            + ". Using default template: "
                            + defaultTemplate
            );

            return defaultTemplate;
        }

        String oldTemplate =
                config.getProperty(
                        "whatsapp.template.name",
                        ""
                ).trim();

        if (!oldTemplate.isBlank()) {

            System.out.println(
                    "Using old whatsapp.template.name setting: "
                            + oldTemplate
            );

            return oldTemplate;
        }

        throw new IllegalStateException(
                "WhatsApp template not configured for "
                        + assignedTo
                        + ". Add this line in config.properties: "
                        + propertyKey
                        + "=service_confirmation"
        );
    }

    private static String normalizeCoordinatorKey(String assignedTo) {

        if (assignedTo == null || assignedTo.isBlank()) {
            return "default";
        }

        String name = assignedTo
                .trim()
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", " ")
                .trim();

        if (name.contains("jaya")) {
            return "jaya";
        }

        if (name.contains("priya")) {
            return "priya";
        }

        if (name.contains("subha")) {
            return "subha";
        }

        if (name.contains("amal")) {
            return "amal";
        }

        if (name.contains("sweety")) {
            return "sweety";
        }

        return name.replaceAll("\\s+", ".");
    }
    private static String normalizeIndianPhone(String phone) {

        if (phone == null || phone.isBlank()) {
            throw new IllegalArgumentException(
                    "Candidate WhatsApp number is missing."
            );
        }

        String normalized = phone.replaceAll("\\D", "");

        // Remove leading zero: 08292361407 → 8292361407
        if (normalized.length() == 11 && normalized.startsWith("0")) {
            normalized = normalized.substring(1);
        }

        // Add Indian country code only when the sheet has 10 digits
        if (normalized.length() == 10) {
            normalized = "91" + normalized;
        }

        // Accept 91 followed by a 10-digit mobile number
        if (!normalized.matches("^91[6-9]\\d{9}$")) {
            throw new IllegalArgumentException(
                    "Invalid Indian WhatsApp number: " + phone
            );
        }

        return normalized;
    }
    private static Map<String, Object> textParameter(
            String value
    ) {

        Map<String, Object> parameter =
                new LinkedHashMap<>();

        parameter.put(
                "type",
                "text"
        );

        String safeValue =
                value == null
                        || value.isBlank()
                        ? "-"
                        : value.trim();

        // Meta WhatsApp template text variables reject new-line/tab characters
        // and long runs of spaces. Normalize them before sending so templates
        // do not fail with error 132018.
        safeValue = safeValue
                .replace("\r", " ")
                .replace("\n", " ")
                .replace("\t", " ")
                .replaceAll(" {5,}", " ")
                .trim();

        parameter.put(
                "text",
                safeValue.isBlank() ? "-" : safeValue
        );

        return parameter;
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
