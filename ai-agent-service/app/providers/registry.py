from datetime import UTC, datetime

from app.models import AgentRunRequest, AgentRunResponse, ProviderTestResponse
from app.providers.base import ProviderUnavailable
from app.providers.fallback import FallbackProvider
from app.providers.ollama_compatible import OllamaCompatibleProvider
from app.providers.openai_compatible import OpenAiCompatibleProvider
from app.settings import Settings


class ProviderRegistry:
    DISPLAY_NAMES = {
        "local-openai-compatible": "本地模型",
        "local-ollama-compatible": "本地 Ollama 模型",
        "cloud-openai-compatible": "企业云模型",
        "fallback-template": "安全模板生成",
    }

    def __init__(self, settings: Settings):
        self.fallback = FallbackProvider()
        self.providers = {
            "local-openai-compatible": OpenAiCompatibleProvider(
                "local-openai-compatible", "local", settings.local_openai_enabled,
                settings.local_openai_base_url, settings.local_openai_api_key, settings.local_openai_model, settings
            ),
            "local-ollama-compatible": OllamaCompatibleProvider(settings),
            "cloud-openai-compatible": OpenAiCompatibleProvider(
                "cloud-openai-compatible", "cloud", settings.cloud_openai_enabled,
                settings.cloud_openai_base_url, settings.cloud_openai_api_key, settings.cloud_openai_model, settings
            ),
            self.fallback.key: self.fallback,
        }

    def descriptors(self) -> list[dict[str, object]]:
        return [provider.descriptor() for provider in self.providers.values()]

    def test_provider(self, provider_key: str) -> ProviderTestResponse:
        provider = self.providers.get(provider_key)
        if provider is None:
            raise KeyError(provider_key)
        display_name = self.DISPLAY_NAMES.get(provider_key, "其他分析模型")
        if hasattr(provider, "test_connection"):
            return provider.test_connection(display_name)
        return ProviderTestResponse(
            providerKey=provider_key,
            displayName=display_name,
            status="failed",
            message="当前模型暂未开放连接测试。",
            testedAt=datetime.now(UTC).isoformat().replace("+00:00", "Z"),
        )

    def run(self, request: AgentRunRequest) -> AgentRunResponse:
        provider = self.providers.get(request.providerKey, self.fallback)
        try:
            sections = provider.run(request)
            fallback_used = provider is self.fallback
        except ProviderUnavailable:
            sections = self.fallback.run(request)
            fallback_used = True
        return AgentRunResponse(
            agentKey=request.agentKey,
            providerKey=request.providerKey,
            period=request.period,
            theme=request.theme,
            source="fallback-template" if fallback_used else "llm",
            status="warning" if fallback_used else "passed",
            sections=sections,
            warnings=["provider_fallback_used"] if fallback_used else [],
        )

