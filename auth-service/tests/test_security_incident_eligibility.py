"""Actual signed HTTP requests against disposable owner/public release assignments."""
from contextlib import contextmanager
from dataclasses import replace
import secrets
from types import SimpleNamespace

import pytest
from fastapi.testclient import TestClient

from app.main import create_app
from test_dpop import env, grant, headers
from test_module_delivery import modules
from test_module_qa_assignment import qa
from test_products import heartbeat
from test_protocol import enroll, release
from test_protocol_v2 import v2, auth
from test_security_incident_routes import configured
from test_security_incidents import begin, confirm_body, decode, fresh, send, snapshot


@contextmanager
def reload_policy(e, **updates):
    """Reload operator settings without renewing or rewriting the original lease."""
    config = replace(e.config, **updates)
    app = create_app(config)
    with TestClient(app, base_url="https://testserver") as client:
        yield SimpleNamespace(**{**vars(e), "config": config, "client": client})


def remove_eligibility(kind):
    # The other harmless entry keeps the explicit enabled-policy configuration
    # valid while removing exactly this installation or release.
    return {"plus_security_incident_" + kind: frozenset(
        {"Z" * 32} if kind == "installations" else {"another-approved-release"})}


@pytest.mark.parametrize("scope", ["celeste_plus", "license"])
def test_exact_owner_installation_and_selected_release_complete_flow(configured, qa, scope):
    with configured(scope, base=qa) as e:
        assert e.claims["sub"] == "1"
        assert e.claims["device"] == qa.enrolled["device_id"]
        assert e.claims["release"] == qa.catalog["release_id"]
        assert e.claims["security_incident_scope"] == scope
        warning = begin(e)
        assert warning["status"] == "warning"
        assert decode(e, send(e, confirm_body(e, warning)))["status"] == "revoked"
        with e.database.connect() as connection:
            state = connection.execute("SELECT status FROM licenses WHERE uid=1").fetchone()[0]
            assert state == ("revoked" if scope == "license" else "active")


def test_second_real_installation_on_same_uid_gets_no_scope_even_on_allowed_release(configured, qa):
    # Use a separately enrolled installation with its own real signing keys;
    # reusing UID 1 must not make this an owner-wide punitive policy.
    with qa.app.state.database.connect() as connection:
        connection.execute("UPDATE licenses SET max_devices=2 WHERE uid=?", (qa.uid,))
    enrolled, install, token = enroll(qa.client, qa.license_key)
    other = SimpleNamespace(**{**vars(qa), "enrolled": enrolled, "install": install, "token": token})
    assert auth(other).status_code == 200
    overrides = {
        "plus_qa_installations": frozenset({qa.enrolled["device_id"], enrolled["device_id"]}),
        "plus_security_incident_installations": frozenset({qa.enrolled["device_id"]}),
    }
    with configured(base=other, overrides=overrides) as e:
        assert e.claims["sub"] == "1"
        assert e.claims["device"] != qa.enrolled["device_id"]
        assert e.claims["release"] == qa.catalog["release_id"]
        assert "security_incident_scope" not in e.claims
        before = snapshot(e)
        assert send(e).status_code == 401
        assert send(e, fresh(e, e.body, device=qa.enrolled["device_id"])).status_code == 401
        assert snapshot(e) == before


def test_allowed_owner_installation_on_public_release_has_no_scope(configured, qa):
    with configured(base=qa, overrides={"plus_qa_installations": frozenset()}) as e:
        assert e.claims["sub"] == "1"
        assert e.claims["device"] in e.config.plus_security_incident_installations
        assert e.claims["release"] != qa.catalog["release_id"]
        assert "security_incident_scope" not in e.claims
        before = snapshot(e)
        assert send(e).status_code == 401
        assert send(e, fresh(e, e.body, release=qa.catalog["release_id"])).status_code == 401
        assert snapshot(e) == before


@pytest.mark.parametrize("field,value", [("device", "Z" * 32), ("release", "another-approved-release")])
def test_freshly_signed_body_cannot_replace_authenticated_installation_or_release(configured, qa, field, value):
    overrides = {
        "plus_security_incident_installations": frozenset({qa.enrolled["device_id"], "Z" * 32}),
        "plus_security_incident_releases": frozenset({qa.catalog["release_id"], "another-approved-release"}),
    }
    with configured(base=qa, overrides=overrides) as e:
        assert e.claims["security_incident_scope"] == "celeste_plus"
        before = snapshot(e)
        # send() signs fresh DPoP, the actual changed body hash, and token proof.
        assert send(e, fresh(e, e.body, **{field: value})).status_code == 401
        assert snapshot(e) == before


@pytest.mark.parametrize("kind", ["installations", "releases"])
@pytest.mark.parametrize("phase", ["begin", "confirm"])
def test_old_real_signed_scope_cannot_bypass_removed_eligibility(configured, qa, kind, phase):
    with configured(base=qa) as e:
        body = e.body if phase == "begin" else confirm_body(e, begin(e))
        assert e.claims["security_incident_scope"] == "celeste_plus"
        with reload_policy(e, **remove_eligibility(kind)) as changed:
            before = snapshot(changed)
            assert send(changed, body).status_code == 403
            assert snapshot(changed) == before


@pytest.mark.parametrize("kind", ["installations", "releases"])
@pytest.mark.parametrize("confirmed", [False, True])
def test_stored_outcome_remains_available_without_new_grants_after_removal(configured, qa, kind, confirmed):
    with configured(base=qa) as e:
        body = confirm_body(e, begin(e))
        if confirmed:
            assert send(e, body).status_code == 200
        with reload_policy(e, **remove_eligibility(kind)) as changed:
            before = snapshot(changed)
            decision = decode(changed, send(changed, fresh(changed, body, phase="outcome")))
            assert decision["status"] == ("revoked" if confirmed else "pending")
            assert decision["observation_id"] == body["observation_id"]
            after = snapshot(changed)
            for table in ("licenses", "devices", "active_tokens", "revoked_tokens", "security_product_blocks", "security_incidents"):
                assert after[table] == before[table]
            if not confirmed:
                # Read-only punitive outcome lookup cancels this ambiguous
                # attempt, but cannot revoke, renew, or introduce an incident.
                assert after["security_incident_observations"][-1][3] == "cancelled"


@pytest.mark.parametrize("route", ["/v1/heartbeat", "/v1/session/check"])
def test_standard_heartbeat_on_eligible_installation_never_gets_incident_scope(configured, qa, route):
    with configured(base=qa) as e:
        token = grant(e.delivery, scope="auth", product="celeste")
        assert "security_incident_scope" not in e.signer.verify(token)
        body = heartbeat({**release(), "device_id": e.body["device"]}, e.install, e.token_key,
                         nonce=secrets.token_urlsafe(24))
        response = e.client.post(route, json=body, headers=headers(e.delivery, "POST", route))
        assert response.status_code == 200, response.text
        claims = e.signer.verify(response.json()["session_token"])
        assert claims["product"] == "celeste" and "security_incident_scope" not in claims


@pytest.mark.parametrize("scope", ["celeste_plus", "license"])
@pytest.mark.parametrize("missing", ["installations", "releases", "both"])
def test_enabled_scope_requires_both_explicit_allowlists_at_startup(modules, scope, missing):
    config = replace(modules.config, plus_security_incident_scope=scope,
        plus_security_incident_installations=frozenset({modules.enrolled["device_id"]}) if missing == "releases" else frozenset(),
        plus_security_incident_releases=frozenset({modules.catalog["release_id"]}) if missing == "installations" else frozenset())
    with pytest.raises(RuntimeError, match="explicit eligible installations and releases"):
        create_app(config)


@pytest.mark.parametrize("field,value", [
    ("installations", frozenset({"*"})),
    ("installations", frozenset({"d" * 31})),
    ("installations", frozenset({"d" * 49})),
    ("installations", frozenset({f"{i:032x}" for i in range(257)})),
    ("installations", ["d" * 32]),
    ("releases", frozenset({"owner/release"})),
    ("releases", frozenset({"r" * 129})),
    ("releases", frozenset({f"release-{i}" for i in range(65)})),
    ("releases", frozenset({None})),
])
def test_programmatic_malformed_or_unbounded_eligibility_rejected(modules, field, value):
    config = replace(modules.config, plus_security_incident_scope="celeste_plus",
        plus_security_incident_installations=frozenset({modules.enrolled["device_id"]}),
        plus_security_incident_releases=frozenset({modules.catalog["release_id"]}))
    with pytest.raises(RuntimeError, match="incident eligibility"):
        create_app(replace(config, **{"plus_security_incident_" + field: value}))
