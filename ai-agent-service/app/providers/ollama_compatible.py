from app.models import AgentRunRequest
from app.providers.base import Provider, ProviderUnavailable
from app.settings import Settings


class OllamaCompatibleProvider(Provider):
    key = "local-ollama-compatible"

    def __init__(self, settings: Settings):
        self.settings = settings

    def descriptor(self) -> dict[str, object]:
        return {
            "key": self.key,
            "type": "local",
            "enabled": self.settings.ollama_enabled and bool(self.settings.ollama_base_url and self.settings.ollama_model),
            "baseUrlConfigured": bool(self.settings.ollama_base_url),
            "apiKeyConfigured": False,
            "modelConfigured": bool(self.settings.ollama_model),
        }

    def run(self, request: AgentRunRequest) -> list[dict[str, str]]:
        raise ProviderUnavailable("ollama_placeholder")

