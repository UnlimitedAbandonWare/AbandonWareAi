package com.example.lms.conversation.archive;

public record ConversationMessageRecord(
        String sourcePath,
        int lineNo,
        String timestampText,
        String sender,
        String message
) {
    public ConversationMessageRecord withMessage(String nextMessage) {
        return new ConversationMessageRecord(sourcePath, lineNo, timestampText, sender, nextMessage);
    }
}
