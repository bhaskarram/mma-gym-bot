package com.mmagym.bot.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

@Component
public class WhatsAppClient {

    private static final Logger log = LoggerFactory.getLogger(WhatsAppClient.class);

    private final RestTemplate restTemplate;
    private final String apiUrl;
    private final String phoneNumberId;
    private final String accessToken;

    public WhatsAppClient(RestTemplate restTemplate,
                          @Value("${meta.api-url}")          String apiUrl,
                          @Value("${meta.phone-number-id}") String phoneNumberId,
                          @Value("${meta.access-token}")    String accessToken) {
        this.restTemplate  = restTemplate;
        this.apiUrl        = apiUrl;
        this.phoneNumberId = phoneNumberId;
        this.accessToken   = accessToken;
    }

    public void sendText(String toPhone, String message) {
        String url = apiUrl + "/" + phoneNumberId + "/messages";

        Map<String, Object> body = Map.of(
                "messaging_product", "whatsapp",
                "recipient_type",    "individual",
                "to",                toPhone,
                "type",              "text",
                "text",              Map.of("body", message)
        );

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(accessToken);

        try {
            ResponseEntity<String> response =
                    restTemplate.exchange(url, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
            if (!response.getStatusCode().is2xxSuccessful()) {
                log.error("Meta API error {} sending to {}: {}", response.getStatusCode(), toPhone, response.getBody());
            }
        } catch (Exception e) {
            log.error("Failed to send WhatsApp message to {}", toPhone, e);
        }
    }
}
