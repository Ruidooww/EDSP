package com.edsp.core.transform.runtime;

import com.edsp.transform.contract.BatchTransformRequest;
import com.edsp.transform.contract.TransformResponse;
import com.edsp.transform.standardevent.SourceRow;
import com.edsp.transform.standardevent.StandardEventTransformService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class LocalTransformRuntimeClient implements TransformRuntimeClient {
    private final StandardEventTransformService transformService;

    public LocalTransformRuntimeClient(StandardEventTransformService transformService) {
        this.transformService = transformService;
    }

    @Override
    public String mode() {
        return "local";
    }

    @Override
    public TransformBatchResult transform(BatchTransformRequest request) {
        var mappingPlan = TransformContractSupport.mappingPlan(request == null ? null : request.mappingPlan());
        var options = TransformContractSupport.options(request == null ? null : request.options());
        var rows = request == null || request.rows() == null ? List.<Map<String, Object>>of() : request.rows();
        var results = new ArrayList<TransformResponse>();
        for (var row : rows) {
            var result = transformService.transform(new SourceRow(row), mappingPlan, options);
            results.add(TransformContractSupport.response(result));
        }
        return new TransformBatchResult(results, TransformRuntimeReport.disabled());
    }
}
