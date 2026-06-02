from datetime import UTC, datetime

from app.models import AgentRunRequest, ProviderTestResponse
from app.providers.base import Provider


class FallbackProvider(Provider):
    key = "fallback-template"

    def descriptor(self) -> dict[str, object]:
        return {
            "key": self.key,
            "type": "fallback",
            "enabled": True,
            "baseUrlConfigured": False,
            "apiKeyConfigured": False,
            "modelConfigured": True,
        }

    def run(self, request: AgentRunRequest) -> list[dict[str, str]]:
        metrics = request.context
        return [
            {"title": "安全态势概览", "content": f"当前开放告警 {metrics.openAlertCount} 条，高危告警 {metrics.criticalAlertCount + metrics.highAlertCount} 条。"},
            {"title": "规则决策", "content": f"已评估决策 {metrics.alertDecisionCount} 条，其中命中 {metrics.matchedDecisionCount} 条，失败 {metrics.failedDecisionCount} 条。"},
            {"title": "同步链路", "content": f"标准事件 {metrics.standardEventCount} 条，存在 warning 的同步运行 {metrics.warningSyncCount} 次。"},
            {"title": "通知准备度", "content": f"已记录通知投递 {metrics.notificationDeliveryCount} 条。本分析不会自动发送通知。"},
            {"title": "建议动作", "content": "优先人工复核高危开放告警，并检查失败决策和 warning 同步运行。"},
        ]

    def test_connection(self, display_name: str) -> ProviderTestResponse:
        return ProviderTestResponse(
            providerKey=self.key,
            displayName=display_name,
            status="passed",
            message="安全模板可用。",
            testedAt=datetime.now(UTC).isoformat().replace("+00:00", "Z"),
        )

