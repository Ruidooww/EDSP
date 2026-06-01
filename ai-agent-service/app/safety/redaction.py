import re


SECRET_PATTERN = re.compile(
    r"(?i)\b(token|secret|password|api[_-]?key|authorization|bearer)\s*[:=]\s*[^\s,;]+"
)


def redact(value: str) -> str:
    return SECRET_PATTERN.sub(lambda match: f"{match.group(1)}=[redacted]", value)

