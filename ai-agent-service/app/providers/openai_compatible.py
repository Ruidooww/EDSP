import json
from urllib.parse import urlparse

import httpx

from app.models import AgentRunRequest
from app.providers.base import Provider, ProviderUnavailable
from app.safety.prompt_guard import build_safe_prompt
from app.safety.response_guard import validate_sections
from app.settings import Settings


class OpenAiCompatibleProvider(Provider):
    def __init__(self, key: str, provider_type: str, enabled: bool, base_url: str, api_key: str, model: str, settings: Settings):
        self.key = key
        self.provider_type = provider_type
        self.enabled = enabled
        self.base_url = base_url
        self.api_key = api_key
        self.model = model
        self.settings = settings

    def descriptor(self) -> dict[str, object]:
        enabled = self.enabled and bool(self.base_url and self.model)
        if self.provider_type == "local" and not self.settings.local_allow_remote:
            enabled = enabled and urlparse(self.base_url).hostname in {"127.0.0.1", "localhost", "host.docker.internal"}
        if self.provider_type == "cloud":
            enabled = enabled and bool(self.api_key)
        return {
            "key": self.key,
            "type": self.provider_type,
            "enabled": enabled,
            "baseUrlConfigured": bool(self.base_url),
            "apiKeyConfigured": bool(self.api_key),
            "modelConfigured": bool(self.model),
        }

    def run(self, request: AgentRunRequest) -> list[dict[str, str]]:
        if not self.descriptor()["enabled"]:
            raise ProviderUnavailable("provider_disabled")
        prompt = build_safe_prompt(request, self.settings.max_prompt_chars)
        headers = {"Content-Type": "application/json"}
        if self.api_key:
            headers["Authorization"] = f"Bearer {self.api_key}"
        try:
            response = httpx.post(
                self.base_url,
                headers=headers,
                json={"model": self.model, "messages": [{"role": "user", "content": prompt}]},
                timeout=20,
            )
            response.raise_for_status()
            payload = response.json()
            content = payload["choices"][0]["message"]["content"]
            sections = json.loads(content)["sections"]
            if not validate_sections(sections):
                raise ProviderUnavailable("unsafe_response")
            return sections
        except Exception as exc:
            raise ProviderUnavailable("provider_unavailable") from exc
