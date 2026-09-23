package com.example.lms.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

@Data
public class ChannelRecipients {
    @JsonProperty("elements")
    private List<Element> elements;

    @Data
    public static class Element {
        private String id;

        @JsonProperty("uuid")
        public void setLegacyUuid(String uuid) {
            this.id = uuid;
        }

        public String getUuid() {
            return id;
        }
    }
}
