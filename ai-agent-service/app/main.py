from fastapi import FastAPI

from app.models import AgentRunRequest, AgentRunResponse
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


@app.post("/agent/runs", response_model=AgentRunResponse)
def run_agent(request: AgentRunRequest) -> AgentRunResponse:
    return registry.run(request)

