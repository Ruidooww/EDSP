package com.edsp.core.transform.runtime;

public class TransformRuntimeException extends RuntimeException {
    private final String failureType;
    private final TransformRuntimeReport report;

    public TransformRuntimeException(String failureType, String message, TransformRuntimeReport report) {
        super(message);
        this.failureType = failureType;
        this.report = report == null
            ? TransformRuntimeReport.remoteFailure("remote", failureType, false)
            : report;
    }

    public TransformRuntimeException(
        String failureType,
        String message,
        TransformRuntimeReport report,
        Throwable cause
    ) {
        super(message, cause);
        this.failureType = failureType;
        this.report = report == null
            ? TransformRuntimeReport.remoteFailure("remote", failureType, false)
            : report;
    }

    public String failureType() {
        return failureType;
    }

    public TransformRuntimeReport report() {
        return report;
    }
}
