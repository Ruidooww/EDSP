package com.edsp.core.transform.runtime;

import com.edsp.transform.contract.TransformResponse;
import java.util.List;

public record TransformBatchResult(
    List<TransformResponse> results,
    TransformRuntimeReport report
) {
    public TransformBatchResult {
        results = results == null ? List.of() : List.copyOf(results);
        report = report == null ? TransformRuntimeReport.disabled() : report;
    }
}
