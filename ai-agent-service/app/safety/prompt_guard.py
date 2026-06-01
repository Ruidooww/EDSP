from app.models import AgentRunRequest


FORBIDDEN_KEYS = {
    "payload_json",
    "normalized_json",
    "extra_json",
    "config_json",
    "endpoint",
    "secret",
    "token",
    "password",
}


def build_safe_prompt(request: AgentRunRequest, max_chars: int) -> str:
    context = request.context.model_dump()
    if FORBIDDEN_KEYS.intersection(context):
        raise ValueError("unsafe_context")
    prompt = (
        "Generate a concise security operations insight as JSON sections. "
        f"theme={request.theme}; period={request.period}; metrics={context}"
    )
    if len(prompt) > max_chars:
        raise ValueError("prompt_too_large")
    return prompt

