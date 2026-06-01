from abc import ABC, abstractmethod

from app.models import AgentRunRequest


class ProviderUnavailable(RuntimeError):
    pass


class Provider(ABC):
    key: str

    @abstractmethod
    def descriptor(self) -> dict[str, object]:
        raise NotImplementedError

    @abstractmethod
    def run(self, request: AgentRunRequest) -> list[dict[str, str]]:
        raise NotImplementedError

