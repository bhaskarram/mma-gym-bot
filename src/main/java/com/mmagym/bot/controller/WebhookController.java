package com.mmagym.bot.controller;

import com.mmagym.bot.dto.WebhookPayload;
import com.mmagym.bot.service.CommandRouter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;

@RestController
@RequestMapping("/webhook")
public class WebhookController {

    private static final Logger log = LoggerFactory.getLogger(WebhookController.class);

    private final CommandRouter commandRouter;
    private final String verifyToken;
    private final String metaAccessToken;

    public WebhookController(CommandRouter commandRouter,
                             @Value("${meta.verify-token}") String verifyToken,
                             @Value("${meta.access-token}") String metaAccessToken) {
        this.commandRouter   = commandRouter;
        this.verifyToken     = verifyToken;
        this.metaAccessToken = metaAccessToken;
    }

    // Meta webhook verification handshake
    @GetMapping
    public ResponseEntity<String> verify(
            @RequestParam("hub.mode")         String mode,
            @RequestParam("hub.verify_token") String token,
            @RequestParam("hub.challenge")    String challenge) {

        if ("subscribe".equals(mode) && verifyToken.equals(token)) {
            log.info("Webhook verified by Meta");
            return ResponseEntity.ok(challenge);
        }
        log.warn("Webhook verification failed — token mismatch");
        return ResponseEntity.status(403).body("Forbidden");
    }

    // Incoming WhatsApp messages
    @PostMapping
    public ResponseEntity<String> receive(
            @RequestHeader(value = "X-Hub-Signature-256", required = false) String signature,
            @RequestBody String rawBody,
            @RequestBody(required = false) WebhookPayload payload) {

        if (!isValidSignature(signature, rawBody)) {
            log.warn("Invalid X-Hub-Signature-256 — dropping request");
            return ResponseEntity.status(403).body("Forbidden");
        }

        if (payload == null || payload.entry() == null) {
            return ResponseEntity.ok("OK");
        }

        for (WebhookPayload.Entry entry : payload.entry()) {
            if (entry.changes() == null) continue;
            for (WebhookPayload.Change change : entry.changes()) {
                if (change.value() == null) continue;
                List<WebhookPayload.Message> messages = change.value().messages();
                if (messages == null) continue;
                for (WebhookPayload.Message msg : messages) {
                    if (!"text".equals(msg.type()) || msg.text() == null) continue;
                    commandRouter.route(msg.from(), msg.text().body().trim());
                }
            }
        }

        return ResponseEntity.ok("OK");
    }

    @GetMapping("/health")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("OK");
    }

    private boolean isValidSignature(String signature, String body) {
        if (signature == null || !signature.startsWith("sha256=")) return false;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(metaAccessToken.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String computed = "sha256=" + HexFormat.of().formatHex(
                    mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
            return computed.equals(signature);
        } catch (Exception e) {
            log.error("Signature verification error", e);
            return false;
        }
    }
}
