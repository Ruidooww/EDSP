package com.edsp.core.transform;

import com.edsp.transform.contract.BatchTransformRequest;
import com.edsp.transform.contract.TransformResponse;
import java.util.List;

public interface TransformRemoteShadowClient {
    boolean enabled();

    TransformShadowReport shadow(BatchTransformRequest request, List<TransformResponse> localResults);

    static TransformRemoteShadowClient disabled() {
        return new TransformRemoteShadowClient() {
            @Override
            public boolean enabled() {
                return false;
            }

            @Override
            public TransformShadowReport shadow(BatchTransformRequest request, List<TransformResponse> localResults) {
                return TransformShadowReport.disabled();
            }
        };
    }
}
