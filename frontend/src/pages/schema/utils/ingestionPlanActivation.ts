import type { IngestionPlanActivationRow, IngestionPlanShadowRunRow } from '../../../types';

export function getActivationStatus(activation?: IngestionPlanActivationRow | null) {
  return activation?.status;
}

export function getActivationShadowRunId(activation?: IngestionPlanActivationRow | null) {
  return activation?.shadowRunId ?? activation?.shadow_run_id;
}

export function getActivationPlanId(activation?: IngestionPlanActivationRow | null) {
  return activation?.ingestionPlanId ?? activation?.ingestion_plan_id;
}

export function getActivationOperator(activation?: IngestionPlanActivationRow | null) {
  return activation?.activatedBy ?? activation?.activated_by ?? activation?.operatorName ?? activation?.operator_name;
}

export function getActivationTime(activation?: IngestionPlanActivationRow | null) {
  return activation?.activatedAt ?? activation?.activated_at ?? activation?.createdAt ?? activation?.created_at;
}

export function findActiveActivation(activations: IngestionPlanActivationRow[]) {
  return activations.find((activation) => getActivationStatus(activation) === 'active') ?? null;
}

export function getShadowRunId(run?: IngestionPlanShadowRunRow | null) {
  return run?.id;
}

export function getShadowRunStatus(run?: IngestionPlanShadowRunRow | null) {
  return run?.status;
}

export function canActivatePlan(planStatus: string, latestShadowRun?: IngestionPlanShadowRunRow | null, activation?: IngestionPlanActivationRow | null) {
  return !activation
    && (planStatus === 'approved' || planStatus === 'shadow_ready')
    && getShadowRunStatus(latestShadowRun) === 'passed';
}
