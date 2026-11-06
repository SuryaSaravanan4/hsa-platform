package com.example.hsa.service;

/** A business error that maps directly to an HTTP status and a stable error code. */
public class ApiException extends RuntimeException {

    public final int status;
    public final String code;

    public ApiException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public static ApiException notFound(String what, long id) {
        return new ApiException(404, "NOT_FOUND", what + " " + id + " not found");
    }
}
