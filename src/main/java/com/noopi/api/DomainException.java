package com.noopi.api;

public class DomainException extends RuntimeException {
    private final ErrorCode code;
    public DomainException(ErrorCode code) { super(code.message); this.code = code; }
    public ErrorCode code() { return code; }
}
