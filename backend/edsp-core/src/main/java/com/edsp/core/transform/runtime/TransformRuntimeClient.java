package com.edsp.core.transform.runtime;

import com.edsp.transform.contract.BatchTransformRequest;

public interface TransformRuntimeClient {
    String mode();

    TransformBatchResult transform(BatchTransformRequest request);
}
