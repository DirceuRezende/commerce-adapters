package com.example.commerce;

/** Shared mapping and integration failure; messages exclude credentials and partner response bodies. */
public class CommerceException extends RuntimeException {
    private final String code;
    private final Integer httpStatus;

    public CommerceException(String message) { this("MAPPING_ERROR", null, message); }
    public CommerceException(String message, Throwable cause) { this("MAPPING_ERROR", null, message, cause); }
    public CommerceException(String code, Integer httpStatus, String message) {
        this(code, httpStatus, message, null);
    }
    public CommerceException(String code, Integer httpStatus, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.httpStatus = httpStatus;
    }
    public String code() { return code; }
    public Integer httpStatus() { return httpStatus; }
}
