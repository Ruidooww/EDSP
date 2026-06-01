from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="EDSP_AI_", extra="ignore")

    fallback_enabled: bool = True
    max_prompt_chars: int = 4000
    max_response_chars: int = 6000
    local_openai_enabled: bool = True
    local_openai_base_url: str = "http://host.docker.internal:11434/v1/chat/completions"
    local_openai_api_key: str = ""
    local_openai_model: str = "local-model"
    local_allow_remote: bool = False
    cloud_openai_enabled: bool = False
    cloud_openai_base_url: str = ""
    cloud_openai_api_key: str = ""
    cloud_openai_model: str = ""
    ollama_enabled: bool = False
    ollama_base_url: str = "http://host.docker.internal:11434/api/chat"
    ollama_model: str = "llama3"


settings = Settings()
