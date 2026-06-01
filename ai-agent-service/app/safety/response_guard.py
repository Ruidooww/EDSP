import re


FORBIDDEN_PATTERN = re.compile(
    r"(?i)(https?://|select\s+.+\s+from|insert\s+into|update\s+\w+\s+set|delete\s+from|"
    r"token\s*[:=]|secret\s*[:=]|password\s*[:=]|api[_-]?key\s*[:=]|authorization\s*[:=]|"
    r"已执行|已关闭告警|已发送通知)"
)


def validate_sections(sections: list[dict[str, object]]) -> bool:
    if not 1 <= len(sections) <= 5:
        return False
    for section in sections:
        title = section.get("title")
        content = section.get("content")
        if not isinstance(title, str) or not isinstance(content, str):
            return False
        if not title.strip() or len(title) > 40 or not content.strip() or len(content) > 500:
            return False
        if FORBIDDEN_PATTERN.search(f"{title} {content}"):
            return False
    return True

