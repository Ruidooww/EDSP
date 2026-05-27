package com.edsp.core.transform.runtime;

import com.edsp.transform.contract.BatchTransformRequest;

public class FallbackTransformRuntimeClient implements TransformRuntimeClient {
    private final TransformRuntimeClient remote;
    private final TransformRuntimeClient local;

    public FallbackTransformRuntimeClient(TransformRuntimeClient remote, TransformRuntimeClient local) {
        this.remote = remote;
        this.local = local;
    }

    @Override
    public String mode() {
        return "fallback";
    }

    @Override
    public TransformBatchResult transform(BatchTransformRequest request) {
        try {
            var remoteResult = remote.transform(request);
            return new TransformBatchResult(remoteResult.results(), TransformRuntimeReport.remoteSuccess("fallback"));
        } catch (TransformRuntimeException ex) {
            var localResult = local.transform(request);
            return new TransformBatchResult(
                localResult.results(),
                TransformRuntimeReport.remoteFailure("fallback", ex.failureType(), true)
            );
        } catch (RuntimeException ex) {
            var localResult = local.transform(request);
            return new TransformBatchResult(
                localResult.results(),
                TransformRuntimeReport.remoteFailure("fallback", "remote_unavailable", true)
            );
        }
    }
}
