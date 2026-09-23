package com.abandonware.ai.agent.tool;

public class ToolInvocationException extends RuntimeException {
    private final int status;
    private final String code;

    public ToolInvocationException(int status, String code) {
        super(code);
        this.status = status;
        this.code = code;
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }

    public static ToolInvocationException badRequest(String code) {
        return new ToolInvocationException(400, code);
    }

    public static ToolInvocationException forbidden(String code) {
        return new ToolInvocationException(403, code);
    }

    public static ToolInvocationException notFound(String code) {
        return new ToolInvocationException(404, code);
    }

    public static ToolInvocationException failed(String code) {
        return new ToolInvocationException(500, code);
    }
}
