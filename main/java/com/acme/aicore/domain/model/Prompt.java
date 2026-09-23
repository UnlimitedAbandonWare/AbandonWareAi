package com.acme.aicore.domain.model;


/**
 * Encapsulates the system, user and context portions of a prompt sent to the
 * language model.  The system portion defines the behaviour or persona,
 * the user portion contains the actual question and the context contains
 * grounded evidence or retrieved documents.  The order of these parts is
 * maintained when serialising the prompt for the model.
 */
public record Prompt(String system, String user, String context) {
}