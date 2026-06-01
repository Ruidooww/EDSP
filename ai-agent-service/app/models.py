from typing import Literal

from pydantic import BaseModel, ConfigDict


Period = Literal["last_24h", "last_7_days", "last_30_days"]
Theme = Literal[
    "security_overview",
    "high_risk_alerts",
    "rule_effectiveness",
    "sync_pipeline_health",
    "notification_readiness",
]


class SafeContext(BaseModel):
    model_config = ConfigDict(extra="forbid")

    rawEventCount: int = 0
    standardEventCount: int = 0
    alertDecisionCount: int = 0
    matchedDecisionCount: int = 0
    notMatchedDecisionCount: int = 0
    errorDecisionCount: int = 0
    alertCount: int = 0
    openAlertCount: int = 0
    criticalAlertCount: int = 0
    highAlertCount: int = 0
    warningSyncCount: int = 0
    failedDecisionCount: int = 0
    notificationDeliveryCount: int = 0


class AgentRunRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    agentKey: Literal["security-insight-agent"]
    providerKey: str
    period: Period
    theme: Theme
    context: SafeContext


class Section(BaseModel):
    title: str
    content: str


class AgentRunResponse(BaseModel):
    agentKey: str
    providerKey: str
    period: Period
    theme: Theme
    source: str
    status: Literal["passed", "warning"]
    sections: list[Section]
    warnings: list[str] = []

