package com.example.lms.service.rag.selfask;

import java.util.Objects;

public class SubQuestion {
    public enum Type { DEFINITION, ALIAS, RELATION }
    private final Type type;
    private final String text;
    private final String notes;

    public SubQuestion(Type type, String text, String notes) {
        this.type = type;
        this.text = text;
        this.notes = notes;
    }

    public Type getType() { return type; }
    public String getText() { return text; }
    public String getNotes() { return notes; }

    @Override
    public String toString() {
        return "SubQuestion{" +
                "type=" + type +
                ", text='" + text + '\'' +
                (notes == null ? "" : ", notes='" + notes + '\'') +
                '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SubQuestion)) return false;
        SubQuestion that = (SubQuestion) o;
        return type == that.type &&
               Objects.equals(text, that.text) &&
               Objects.equals(notes, that.notes);
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, text, notes);
    }
}