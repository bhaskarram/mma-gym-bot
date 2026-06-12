package com.mmagym.bot.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record WebhookPayload(
        String object,
        List<Entry> entry
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Entry(
            String id,
            List<Change> changes
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Change(
            Value value,
            String field
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Value(
            @JsonProperty("messaging_product") String messagingProduct,
            List<Contact> contacts,
            List<Message> messages
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Contact(
            String wa_id,
            Profile profile
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Profile(String name) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Message(
            String id,
            String from,
            String type,
            Text text,
            Interactive interactive,
            Long timestamp
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Text(String body) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Interactive(
            String type,
            @JsonProperty("list_reply")   ListReply listReply,
            @JsonProperty("button_reply") ButtonReply buttonReply
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ListReply(String id, String title) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ButtonReply(String id, String title) {}
}
