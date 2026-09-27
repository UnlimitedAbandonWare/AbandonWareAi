package com.example.lms.dto.help;

import java.util.Map;



public class ContextHelpRequest {
        private String contextType;                 // e.g., "ui"
        private Map<String, Object> contextData;    // e.g., { elementId: "send-button", /* ... */ }

        public String getContextType() { return contextType; }
        public void setContextType(String contextType) { this.contextType = contextType; }

        public Map<String, Object> getContextData() { return contextData; }
        public void setContextData(Map<String, Object> contextData) { this.contextData = contextData; }
}