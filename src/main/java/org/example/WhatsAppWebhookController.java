package org.example;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/webhook/whatsapp")
public class WhatsAppWebhookController {

    private static final String VERIFY_TOKEN =
            "mariners_mentor_verify_2026";

    private static final ObjectMapper MAPPER =
            new ObjectMapper();

    /*
     * Meta calls this GET method when verifying the webhook.
     */
    @GetMapping
    public ResponseEntity<String> verifyWebhook(
            @RequestParam(name = "hub.mode", required = false)
            String mode,

            @RequestParam(name = "hub.verify_token", required = false)
            String verifyToken,

            @RequestParam(name = "hub.challenge", required = false)
            String challenge
    ) {

        if ("subscribe".equals(mode)
                && VERIFY_TOKEN.equals(verifyToken)) {

            System.out.println(
                    "WhatsApp webhook verified successfully."
            );

            return ResponseEntity.ok(challenge);
        }

        System.out.println(
                "WhatsApp webhook verification failed."
        );

        return ResponseEntity.status(403)
                .body("Verification failed");
    }

    /*
     * Meta sends message status updates to this POST method.
     */
    @PostMapping
    public ResponseEntity<String> receiveWebhook(
            @RequestBody String requestBody
    ) {

        try {

            System.out.println();
            System.out.println(
                    "========== WHATSAPP WEBHOOK =========="
            );

            System.out.println(requestBody);

            JsonNode root =
                    MAPPER.readTree(requestBody);

            JsonNode entries =
                    root.path("entry");

            if (!entries.isArray()) {
                return ResponseEntity.ok("EVENT_RECEIVED");
            }

            for (JsonNode entry : entries) {

                JsonNode changes =
                        entry.path("changes");

                if (!changes.isArray()) {
                    continue;
                }

                for (JsonNode change : changes) {

                    JsonNode value =
                            change.path("value");

                    JsonNode statuses =
                            value.path("statuses");

                    if (!statuses.isArray()) {
                        continue;
                    }

                    for (JsonNode statusNode : statuses) {

                        String messageId =
                                statusNode.path("id").asText();

                        String status =
                                statusNode.path("status").asText();

                        String recipientPhone =
                                statusNode
                                        .path("recipient_id")
                                        .asText();

                        String timestamp =
                                statusNode
                                        .path("timestamp")
                                        .asText();

                        String failureReason =
                                extractFailureReason(
                                        statusNode
                                );

                        System.out.println(
                                "Message ID : " + messageId
                        );

                        System.out.println(
                                "Phone      : " + recipientPhone
                        );

                        System.out.println(
                                "Status     : " + status
                        );

                        System.out.println(
                                "Timestamp  : " + timestamp
                        );

                        if (!failureReason.isBlank()) {

                            System.out.println(
                                    "Failure    : "
                                            + failureReason
                            );
                        }

                        /*
                         * Later, call your Google Sheet update
                         * method here.
                         */
                        updateGoogleSheetStatus(
                                recipientPhone,
                                messageId,
                                status,
                                failureReason
                        );
                    }
                }
            }

            /*
             * Meta expects HTTP 200 after the webhook
             * is received successfully.
             */
            return ResponseEntity.ok("EVENT_RECEIVED");

        } catch (Exception e) {

            e.printStackTrace();

            /*
             * Return 200 after logging the error,
             * otherwise Meta may keep retrying.
             */
            return ResponseEntity.ok("EVENT_RECEIVED");
        }
    }

    private String extractFailureReason(
            JsonNode statusNode
    ) {

        JsonNode errors =
                statusNode.path("errors");

        if (!errors.isArray() || errors.isEmpty()) {
            return "";
        }

        JsonNode firstError =
                errors.get(0);

        String code =
                firstError.path("code").asText();

        String title =
                firstError.path("title").asText();

        String message =
                firstError.path("message").asText();

        JsonNode errorData =
                firstError.path("error_data");

        String details =
                errorData.path("details").asText();

        return "Code: "
                + code
                + " | "
                + title
                + " | "
                + message
                + " | "
                + details;
    }

    private void updateGoogleSheetStatus(
            String phone,
            String messageId,
            String status,
            String failureReason
    ) {

        String finalStatus =
                status.toUpperCase();

        if ("failed".equalsIgnoreCase(status)
                && !failureReason.isBlank()) {

            finalStatus =
                    "FAILED - " + failureReason;
        }

        System.out.println(
                "Google Sheet Status: " + finalStatus
        );

        /*
         * Add your Google Sheets update code here.
         *
         * Find the row using:
         * 1. Message ID — best option
         * or
         * 2. Recipient phone number
         *
         * Then update the WhatsApp Status column.
         */
    }
}