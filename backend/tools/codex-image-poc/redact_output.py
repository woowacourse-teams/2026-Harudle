"""Remove known runtime secrets before returning diagnostic errors to the operator."""

import re
from urllib.parse import quote, quote_plus

_SECRET_NAME = re.compile(r"KEY|TOKEN|PASSWORD|SECRET|CREDENTIAL|AUTHORIZATION", re.IGNORECASE)


def redact_output(text, settings):
    """Mask raw and URL-encoded secret values, including overlapping values."""
    secrets = set()
    for name, value in settings.items():
        if not isinstance(value, str) or not value or not _SECRET_NAME.search(name):
            continue
        secrets.update((value, quote(value, safe=""), quote_plus(value, safe="")))
    if not secrets:
        return text
    pattern = re.compile("|".join(re.escape(value) for value in sorted(secrets, key=len, reverse=True)))
    return pattern.sub("[redacted]", text)
