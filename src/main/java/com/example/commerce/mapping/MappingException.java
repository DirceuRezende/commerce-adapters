package com.example.commerce.mapping;

public class MappingException extends RuntimeException {
    public MappingException(String message) { super(message); }
    public MappingException(String message, Throwable cause) { super(message, cause); }
}
