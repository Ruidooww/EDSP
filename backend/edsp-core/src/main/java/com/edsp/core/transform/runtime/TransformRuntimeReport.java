package com.edsp.core.transform.runtime;

import java.util.LinkedHashMap;
import java.util.Map;

public record TransformRuntimeReport(
    boolean enabled,
    String mode,
    boolean remoteAttempted,
    boolean remoteSucceeded,
    boolean fallbackUsed,
    String failureType
) {
    public static TransformRuntimeReport disabled() {
        return new TransformRuntimeReport(false, "local", false, false, false, null);
    }

    public static TransformRuntimeReport remoteSuccess(String mode) {
        return new TransformRuntimeReport(true, mode, true, true, false, null);
    }

    public static TransformRuntimeReport remoteFailure(String mode, String failureType, boolean fallbackUsed) {
        return new TransformRuntimeReport(true, mode, true, false, fallbackUsed, failureType);
    }

    public static TransformRuntimeReport noRows(String mode) {
        return new TransformRuntimeReport(true, mode, false, false, false, null);
    }

    public Map<String, Object> toReportMap() {
        var report = new LinkedHashMap<String, Object>();
        report.put("mode", mode);
        report.put("remoteAttempted", remoteAttempted);
        report.put("remoteSucceeded", remoteSucceeded);
        report.put("fallbackUsed", fallbackUsed);
        if (failureType != null) {
            report.put("failureType", failureType);
        }
        return report;
    }
}
