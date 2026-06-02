import json
from datetime import UTC, datetime
from urllib.parse import urlparse

import httpx

from app.models import AgentRunRequest, ProviderTestResponse
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

    def test_connection(self, display_name: str) -> ProviderTestResponse:
        descriptor = self.descriptor()
        if not self.enabled:
            return self._test_result(display_name, "failed", "模型配置未启用，请联系管理员检查部署环境变量。")
        if not descriptor["baseUrlConfigured"] or not descriptor["modelConfigured"]:
            return self._test_result(display_name, "failed", "模型接口未配置，请联系管理员检查部署环境变量。")
        if self.provider_type == "cloud" and not descriptor["apiKeyConfigured"]:
            return self._test_result(display_name, "failed", "模型接口未配置，请联系管理员检查部署环境变量。")
        if not descriptor["enabled"]:
            return self._test_result(display_name, "failed", "模型接口不可用，请检查接口地址和模型名称。")

        headers = {"Content-Type": "application/json"}
        if self.api_key:
            headers["Authorization"] = f"Bearer {self.api_key}"
        try:
            response = httpx.post(
                self.base_url,
                headers=headers,
                json={"model": self.model, "messages": [{"role": "user", "content": "Return OK only."}]},
                timeout=10,
            )
            response.raise_for_status()
            return self._test_result(display_name, "passed", "模型连接测试通过。")
        except httpx.HTTPStatusError as exc:
            status_code = exc.response.status_code if exc.response is not None else 0
            if status_code in {401, 403}:
                message = "认证失败，请检查 API Key。"
            elif status_code == 404:
                message = "模型或接口路径不可用，请检查接口地址和模型名称。"
            else:
                message = "模型连接测试失败，请检查配置。"
            return self._test_result(display_name, "failed", message)
        except httpx.TimeoutException:
            return self._test_result(display_name, "failed", "连接超时，请稍后重试或检查网络。")
        except httpx.RequestError:
            return self._test_result(display_name, "failed", "接口不可达，请检查接口地址。")
        except Exception:
            return self._test_result(display_name, "failed", "模型连接测试失败，请检查配置。")

    def _test_result(self, display_name: str, status: str, message: str) -> ProviderTestResponse:
        return ProviderTestResponse(
            providerKey=self.key,
            displayName=display_name,
            status=status,
            message=message,
            testedAt=datetime.now(UTC).isoformat().replace("+00:00", "Z"),
        )
