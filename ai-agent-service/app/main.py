from fastapi import FastAPI, HTTPException

from app.models import AgentRunRequest, AgentRunResponse, ProviderTestResponse
from app.providers.registry import ProviderRegistry
from app.settings import settings


app = FastAPI(title="EDSP AI Agent Service")
registry = ProviderRegistry(settings)


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "ok", "service": "ai-agent-service", "version": "foundation-compose-mvp"}


@app.get("/agent/providers")
def providers() -> dict[str, object]:
    return {"providers": registry.descriptors()}


@app.post("/agent/providers/{provider_key}/test", response_model=ProviderTestResponse)
def test_provider(provider_key: str) -> ProviderTestResponse:
    try:
        return registry.test_provider(provider_key)
    except KeyError as exc:
        raise HTTPException(status_code=400, detail="invalid_ai_provider_key") from exc


@app.post("/agent/runs", response_model=AgentRunResponse)
def run_agent(request: AgentRunRequest) -> AgentRunResponse:
    return registry.run(request)

