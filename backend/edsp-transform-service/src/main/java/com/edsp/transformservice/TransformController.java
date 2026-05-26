package com.edsp.transformservice;

import com.edsp.transform.contract.BatchTransformRequest;
import com.edsp.transform.contract.BatchTransformResponse;
import com.edsp.transform.contract.TransformRequest;
import com.edsp.transform.contract.TransformResponse;
import com.edsp.transform.contract.TransformResultItem;
import com.edsp.transform.standardevent.SourceRow;
import com.edsp.transform.standardevent.StandardEventTransformService;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/transform")
public class TransformController {
    private static final int MAX_BATCH_ROWS = 100;

    private final StandardEventTransformService transformService;

    public TransformController(StandardEventTransformService transformService) {
        this.transformService = transformService;
    }

    @PostMapping("/standard-events")
    public TransformResponse transform(@RequestBody(required = false) TransformRequest request) {
        if (request == null || request.row() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_transform_request");
        }
        var result = transformService.transform(
            new SourceRow(request.row()),
            TransformContractMapper.mappingPlan(request.mappingPlan()),
            TransformContractMapper.options(request.options())
        );
        return TransformContractMapper.response(result);
    }

    @PostMapping("/standard-events/batch")
    public BatchTransformResponse transformBatch(@RequestBody(required = false) BatchTransformRequest request) {
        if (request == null || request.rows() == null || request.rows().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_transform_request");
        }
        if (request.rows().size() > MAX_BATCH_ROWS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "batch_too_large");
        }
        var results = new ArrayList<TransformResultItem>();
        for (var index = 0; index < request.rows().size(); index++) {
            var row = request.rows().get(index);
            if (row == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_transform_request");
            }
            var result = transformService.transform(
                new SourceRow(row),
                TransformContractMapper.mappingPlan(request.mappingPlan()),
                TransformContractMapper.options(request.options())
            );
            var response = TransformContractMapper.response(result);
            results.add(new TransformResultItem(index, response.draft(), response.errors(), response.warnings()));
        }
        return new BatchTransformResponse(results, List.of(), List.of());
    }
}
