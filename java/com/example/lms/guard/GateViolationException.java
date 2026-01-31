package com.example.lms.guard;

public class GateViolationException extends RuntimeException {
    public GateViolationException(String message) { super(message); }
}