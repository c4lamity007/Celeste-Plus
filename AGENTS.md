# Celeste+ repository

This checkout belongs to `https://github.com/c4lamity007/Celeste-Plus.git`.
Verify the exact push URL before publishing any change. The separate Celeste
repository is a pinned runtime dependency, not the destination for Plus work.

Keep the launcher and resident host independently buildable. Game source lives
in `runtime-overlay/` with reviewed hashes in its manifest and `core.lock.json`.
Use `tools/prepare_runtime.py` to reconstruct it; keep generated outputs ignored.
Preserve the standalone loader/resource retirement fixes when importing code
from an older integrated checkout.

Authentication source is under `auth-service/`, `beta-lab/shared-auth/`,
`beta-lab/native-shared/`, `shared-auth/`, and the runtime overlay. The backend
serves both products; Plus clients must remain scoped to `celeste_plus`.
Source publication is separate from a coordinated production deployment.

For changed game modules, use the `celeste-module-qa` skill when available.
Report fixture/build checks separately from actual game verification. Never
claim that unsigned CI candidates have passed the production protection gate.
