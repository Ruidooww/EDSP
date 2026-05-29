package com.edsp.transformservice;

import com.edsp.transform.contract.TransformDraftDto;
import com.edsp.transform.contract.TransformMappingPlanDto;
import com.edsp.transform.contract.TransformOptionsDto;
import com.edsp.transform.contract.TransformResponse;
import com.edsp.transform.standardevent.MappingPlan;
import com.edsp.transform.standardevent.StandardEventDraft;
import com.edsp.transform.standardevent.TransformOptions;
import com.edsp.transform.standardevent.TransformResult;
import java.util.List;

final class TransformContractMapper {
    private TransformContractMapper() {
    }

    static MappingPlan mappingPlan(TransformMappingPlanDto dto) {
        if (dto == null) {
            return new MappingPlan(null, null);
        }
        return new MappingPlan(dto.fieldMappings(), dto.dedupFields(), fieldMappingDetails(dto));
    }

    private static List<MappingPlan.FieldMappingDetail> fieldMappingDetails(TransformMappingPlanDto dto) {
        return dto.fieldMappingDetails().stream()
            .map(detail -> new MappingPlan.FieldMappingDetail(
                detail.sourceField(),
                detail.standardField(),
                detail.transformRule()
            ))
            .toList();
    }

    static TransformOptions options(TransformOptionsDto dto) {
        if (dto == null) {
            return new TransformOptions(null, null, null, null);
        }
        return new TransformOptions(dto.dataSourceId(), dto.schemaTableId(), dto.sourceTable(), dto.syncMode());
    }

    static TransformResponse response(TransformResult result) {
        return new TransformResponse(draft(result.draft()), result.errors(), result.warnings());
    }

    private static TransformDraftDto draft(StandardEventDraft draft) {
        if (draft == null) {
            return null;
        }
        return new TransformDraftDto(
            draft.sourceSystem(),
            draft.externalId(),
            draft.eventType(),
            draft.occurredAt() == null ? null : draft.occurredAt().toString(),
            draft.actor(),
            draft.assetRef(),
            draft.subjectType(),
            draft.subjectRef(),
            draft.action(),
            draft.result(),
            draft.severity(),
            draft.riskScore(),
            draft.dedupKey(),
            draft.normalized(),
            draft.extra()
        );
    }
}
