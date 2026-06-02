from app.safety.policy import evaluate_text


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
        if evaluate_text(f"{title} {content}"):
            return False
    return True

