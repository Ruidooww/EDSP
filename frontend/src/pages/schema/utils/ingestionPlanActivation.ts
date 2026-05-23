import type {
  IngestionPlanActivationRow,
  IngestionPlanShadowRunRow,
  IngestionPlanSyncRunRow,
  IngestionPlanSyncScheduleRow,
} from '../../../types';

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

export function canSyncOnce(activation?: IngestionPlanActivationRow | null) {
  return getActivationStatus(activation) === 'active';
}

export function getSyncRunStatus(run?: IngestionPlanSyncRunRow | null) {
  return run?.status;
}

export function getSyncRunTriggerType(run?: IngestionPlanSyncRunRow | null) {
  return run?.triggerType ?? run?.trigger_type ?? run?.report?.triggerType;
}

export function getSyncRunMetric(
  run: IngestionPlanSyncRunRow | null | undefined,
  camelKey: keyof IngestionPlanSyncRunRow,
  snakeKey: keyof IngestionPlanSyncRunRow,
) {
  const value = run?.[camelKey] ?? run?.[snakeKey];
  return typeof value === 'number' ? value : 0;
}

export function getSyncRunTime(run?: IngestionPlanSyncRunRow | null) {
  return run?.finishedAt ?? run?.finished_at ?? run?.createdAt ?? run?.created_at;
}

export function getSyncRunErrorMessage(run?: IngestionPlanSyncRunRow | null) {
  return run?.errorMessage ?? run?.error_message ?? run?.report?.errorMessage;
}

export function getSyncRunWarnings(run?: IngestionPlanSyncRunRow | null) {
  return run?.report?.warnings ?? [];
}

export function getSyncScheduleStatus(schedule?: IngestionPlanSyncScheduleRow | null) {
  return schedule?.status;
}

export function getSyncScheduleActivationId(schedule?: IngestionPlanSyncScheduleRow | null) {
  return schedule?.activationId ?? schedule?.activation_id;
}

export function getSyncScheduleMetric(
  schedule: IngestionPlanSyncScheduleRow | null | undefined,
  camelKey: keyof IngestionPlanSyncScheduleRow,
  snakeKey: keyof IngestionPlanSyncScheduleRow,
) {
  const value = schedule?.[camelKey] ?? schedule?.[snakeKey];
  return typeof value === 'number' ? value : 0;
}

export function getSyncScheduleTime(
  schedule: IngestionPlanSyncScheduleRow | null | undefined,
  camelKey: keyof IngestionPlanSyncScheduleRow,
  snakeKey: keyof IngestionPlanSyncScheduleRow,
) {
  const value = schedule?.[camelKey] ?? schedule?.[snakeKey];
  return typeof value === 'string' || typeof value === 'number' ? value : undefined;
}

export function getSyncScheduleText(
  schedule: IngestionPlanSyncScheduleRow | null | undefined,
  camelKey: keyof IngestionPlanSyncScheduleRow,
  snakeKey: keyof IngestionPlanSyncScheduleRow,
) {
  const value = schedule?.[camelKey] ?? schedule?.[snakeKey];
  return typeof value === 'string' ? value : undefined;
}
