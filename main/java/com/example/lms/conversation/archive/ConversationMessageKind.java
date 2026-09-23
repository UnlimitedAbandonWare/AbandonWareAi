package com.example.lms.conversation.archive;

public enum ConversationMessageKind {
    HUMAN_MESSAGE("human_message"),
    BOT_SUMMARY("bot_summary"),
    LINK_ARTIFACT("link_artifact"),
    QUARANTINED("quarantined");

    private final String wireName;

    ConversationMessageKind(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }
}
