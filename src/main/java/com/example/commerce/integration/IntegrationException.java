package com.example.commerce.integration;

/** Deliberately excludes credentials, response bodies and financial payloads. */
public class IntegrationException extends RuntimeException {
    private final String code;
    private final Integer httpStatus;
    public IntegrationException(String code, Integer status, String message) {
        super(message); this.code = code; this.httpStatus = status;
    }
    public IntegrationException(String code, Integer status, String message, Throwable cause) {
        super(message, cause); this.code = code; this.httpStatus = status;
    }
    public String code() { return code; }
    public Integer httpStatus() { return httpStatus; }
}
