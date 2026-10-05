"""Operator-controlled installation assignment for same-origin modular QA.

No request parameter selects a release. These immutable settings change only
when the service is deliberately restarted with reviewed deployment settings.
"""
from dataclasses import replace
from pathlib import Path
import re


def selected_settings(settings, device_id=None):
    if device_id and device_id in getattr(settings, "plus_qa_installations", frozenset()):
        return replace(settings, beta_release_dir=settings.plus_qa_beta_release_dir,
                       module_release_dir=settings.plus_qa_module_release_dir,
                       module_delivery_required=True, plus_qa_installations=frozenset(),
                       plus_qa_beta_release_dir="", plus_qa_module_release_dir="")
    return settings


def validate_qa_assignment(settings):
    beta = getattr(settings, "plus_qa_beta_release_dir", "")
    modules = getattr(settings, "plus_qa_module_release_dir", "")
    ids = getattr(settings, "plus_qa_installations", frozenset())
    if (bool(beta) != bool(modules) or ids and not beta
            or len(ids) > 16 or any(not isinstance(value, str) or not re.fullmatch(r"[A-Za-z0-9_-]{32,48}", value) for value in ids)):
        raise RuntimeError("QA requires a paired release and at most 16 explicit installation identities")
    if not beta:
        return None
    if (not Path(beta).is_absolute() or not Path(modules).is_absolute()
            or not settings.plus_enabled or settings.plus_auth_protocol != 2
            or settings.module_key_encryption_key is None):
        raise RuntimeError("QA requires absolute paired release directories, Plus protocol 2 and the persistent module key")
    # Paired paths may be the same release directory; neither may replace a
    # default production path while QA-only assignment is being staged.
    candidate = {Path(beta).resolve(), Path(modules).resolve()}
    defaults = {Path(p).resolve() for p in (settings.beta_release_dir, settings.module_release_dir, settings.artifact_dir) if p}
    if any(a == b or a in b.parents or b in a.parents for a in candidate for b in defaults):
        raise RuntimeError("QA and default release directories must be isolated")
    selected = replace(settings, beta_release_dir=beta, module_release_dir=modules,
                       module_delivery_required=True, plus_qa_installations=frozenset(),
                       plus_qa_beta_release_dir="", plus_qa_module_release_dir="")
    from .beta_delivery import _read_release
    try:
        _read_release(selected)  # Includes exact mandatory resident/module binding.
    except Exception as failure:
        raise RuntimeError("QA resident and module release do not match") from failure
    return selected
