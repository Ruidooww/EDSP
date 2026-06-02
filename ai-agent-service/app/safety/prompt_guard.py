from app.models import AgentRunRequest
from app.safety.policy import SAFE_POLICY_WARNING_CODE, evaluate_prompt_context


def build_safe_prompt(request: AgentRunRequest, max_chars: int) -> str:
    context = request.context.model_dump()
    if evaluate_prompt_context(context):
        raise ValueError(SAFE_POLICY_WARNING_CODE)
    prompt = (
        "Generate a concise security operations insight as JSON sections. "
        f"theme={request.theme}; period={request.period}; metrics={context}"
    )
    if len(prompt) > max_chars:
        raise ValueError("prompt_too_large")
    return prompt

