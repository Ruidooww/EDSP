from fastapi.testclient import TestClient

from app import main
from app.main import app
from app.safety.redaction import redact
from app.safety.response_guard import validate_sections
from app.providers.registry import ProviderRegistry
from app.providers.openai_compatible import OpenAiCompatibleProvider
from app.settings import Settings


client = TestClient(app)


def test_health_endpoint():
    response = client.get("/health")
    assert response.status_code == 200
    assert response.json() == {
        "status": "ok",
        "service": "ai-agent-service",
        "version": "foundation-compose-mvp",
    }


def test_provider_discovery_does_not_expose_credentials_or_urls():
    payload = client.get("/agent/providers").json()
    serialized = str(payload).lower()
    assert "api_key" not in serialized
    assert "base_url" not in serialized
    assert "authorization" not in serialized
    assert {provider["key"] for provider in payload["providers"]} == {
        "local-openai-compatible",
        "local-ollama-compatible",
        "cloud-openai-compatible",
        "fallback-template",
    }


def test_unknown_provider_falls_back_to_safe_template():
    response = client.post(
        "/agent/runs",
        json={
            "agentKey": "security-insight-agent",
            "providerKey": "missing-provider",
            "period": "last_7_days",
            "theme": "security_overview",
            "context": {"rawEventCount": 5, "alertCount": 2},
        },
    )
    assert response.status_code == 200
    payload = response.json()
    assert payload["source"] == "fallback-template"
    assert payload["status"] == "warning"
    assert len(payload["sections"]) == 5


def test_run_request_rejects_unknown_context_fields():
    response = client.post(
        "/agent/runs",
        json={
            "agentKey": "security-insight-agent",
            "providerKey": "fallback-template",
            "period": "last_7_days",
            "theme": "security_overview",
            "context": {"rawEventCount": 5, "payload_json": {"secret": "no"}},
        },
    )
    assert response.status_code == 422


def test_redaction_masks_secret_assignments():
    assert "[redacted]" in redact("token=abc123 password=hunter2 secret=value")
    assert "hunter2" not in redact("password=hunter2")


def test_response_guard_rejects_sql_and_urls():
    assert not validate_sections([{"title": "bad", "content": "select * from alerts"}])
    assert not validate_sections([{"title": "bad", "content": "https://example.com"}])


def test_local_provider_rejects_remote_endpoint_by_default():
    settings = Settings(local_openai_base_url="https://example.com/v1/chat/completions")
    provider = OpenAiCompatibleProvider(
        "local-openai-compatible", "local", True,
        settings.local_openai_base_url, "", "local-model", settings,
    )
    assert provider.descriptor()["enabled"] is False


def test_provider_test_unknown_returns_400():
    response = client.post("/agent/providers/unknown/test")
    assert response.status_code == 400
    assert response.json()["detail"] == "invalid_ai_provider_key"


def test_provider_test_fallback_returns_passed_without_secret_fields():
    response = client.post("/agent/providers/fallback-template/test")
    assert response.status_code == 200
    payload = response.json()
    assert payload["providerKey"] == "fallback-template"
    assert payload["status"] == "passed"
    serialized = str(payload).lower()
    assert "api_key" not in serialized
    assert "authorization" not in serialized
    assert "bearer" not in serialized
    assert "sk-" not in serialized


def test_provider_test_cloud_missing_api_key_returns_sanitized_failed_message():
    old_registry = main.registry
    main.registry = ProviderRegistry(Settings(
        cloud_openai_enabled=True,
        cloud_openai_base_url="https://model.example/v1/chat/completions",
        cloud_openai_api_key="",
        cloud_openai_model="secure-model",
    ))
    try:
        response = client.post("/agent/providers/cloud-openai-compatible/test")
    finally:
        main.registry = old_registry
    assert response.status_code == 200
    payload = response.json()
    assert payload["status"] == "failed"
    serialized = str(payload).lower()
    assert "api_key" not in serialized
    assert "authorization" not in serialized
    assert "bearer" not in serialized
    assert "sk-" not in serialized


def test_provider_test_cloud_success_uses_minimal_prompt_without_leaking_key(monkeypatch):
    calls = []

    class FakeResponse:
        status_code = 200

        def raise_for_status(self):
            return None

        def json(self):
            return {"choices": [{"message": {"content": "OK"}}]}

    def fake_post(url, headers, json, timeout):
        calls.append({"url": url, "headers": headers, "json": json, "timeout": timeout})
        return FakeResponse()

    monkeypatch.setattr("app.providers.openai_compatible.httpx.post", fake_post)
    old_registry = main.registry
    main.registry = ProviderRegistry(Settings(
        cloud_openai_enabled=True,
        cloud_openai_base_url="https://model.example/v1/chat/completions",
        cloud_openai_api_key="demo-key",
        cloud_openai_model="secure-model",
    ))
    try:
        response = client.post("/agent/providers/cloud-openai-compatible/test")
    finally:
        main.registry = old_registry

    assert response.status_code == 200
    payload = response.json()
    assert payload["status"] == "passed"
    assert calls[0]["headers"]["Authorization"] == "Bearer demo-key"
    assert calls[0]["json"]["messages"][0]["content"] == "Return OK only."
    serialized = str(payload).lower()
    assert "demo-key" not in serialized
    assert "authorization" not in serialized
    assert "https://model.example" not in serialized
