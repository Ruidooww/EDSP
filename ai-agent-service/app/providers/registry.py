from app.models import AgentRunRequest, AgentRunResponse
from app.providers.base import ProviderUnavailable
from app.providers.fallback import FallbackProvider
from app.providers.ollama_compatible import OllamaCompatibleProvider
from app.providers.openai_compatible import OpenAiCompatibleProvider
from app.settings import Settings


class ProviderRegistry:
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

