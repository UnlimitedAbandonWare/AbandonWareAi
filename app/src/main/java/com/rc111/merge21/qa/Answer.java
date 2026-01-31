
package com.rc111.merge21.qa;

public class Answer {
    public enum Status { OK, NEED_MORE_CONTEXT, LOW_CONFIDENCE }
    public String text;
    public String[] citations;
    public double confidence;
    public Status status = Status.OK;

    public Answer(String text, String[] citations, double confidence) {
        this.text = text; this.citations = citations; this.confidence = confidence;
    }
}
