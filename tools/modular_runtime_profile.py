"""Reviewed source selection for the independent-module runtime build only."""
from __future__ import annotations

import json
from pathlib import Path
import prepare_runtime

ROOT = Path(__file__).resolve().parents[1]
REGISTRY = "src/main/java/dev/c4lam/celeste/module/BuiltInFeatures.java"
EXCLUDED = ("src/main/java/dev/c4lam/celeste/module/LegacyBuiltInFeatures.java",
            "src/main/java/dev/c4lam/celeste/module/LegacyModuleHooks.java",
            "src/main/java/dev/c4lam/celeste/module/impl/expansion/ExpansionFeatures.java",
            "src/main/java/dev/c4lam/celeste/module/expansion/inventory/InventoryExpansion.java",
            "src/main/java/dev/c4lam/celeste/module/expansion/inventory/ReBuffNotifierFeature.java",
            "src/main/java/dev/c4lam/celeste/module/expansion/inventory/InventorySupport.java",
            "src/main/java/dev/c4lam/celeste/module/expansion/inventory/HeldUseRepeater.java",
            "src/main/java/dev/c4lam/celeste/module/expansion/inventory/OwnFluidTracker.java",
            "src/main/java/dev/c4lam/celeste/module/impl/expansion/LocalInputOwnership.java",
            "src/main/java/dev/c4lam/celeste/module/impl/expansion/LocalRotationProjection.java")
EXCLUDED += ('src/main/java/dev/c4lam/celeste/module/impl/visual/expansion/BlockHighlightMesh.java', 'src/main/java/dev/c4lam/celeste/module/impl/visual/expansion/ConnectedRibbon.java', 'src/main/java/dev/c4lam/celeste/module/impl/visual/expansion/EquipmentCaptureQueue.java', 'src/main/java/dev/c4lam/celeste/module/impl/visual/expansion/GlowTrail.java', 'src/main/java/dev/c4lam/celeste/module/impl/visual/expansion/JumpCircleAnimation.java', 'src/main/java/dev/c4lam/celeste/module/impl/visual/expansion/ModuleVisualRenderer.java', 'src/main/java/dev/c4lam/celeste/module/impl/visual/expansion/PlayerEchoRenderer.java', 'src/main/java/dev/c4lam/celeste/module/impl/visual/expansion/PlayerModelSnapshot.java', 'src/main/java/dev/c4lam/celeste/module/impl/visual/expansion/PlayerPoseTransforms.java', 'src/main/java/dev/c4lam/celeste/module/impl/visual/expansion/SkyboxMath.java', 'src/main/java/dev/c4lam/celeste/module/impl/visual/expansion/SolidVisualMesh.java', 'src/main/java/dev/c4lam/celeste/module/impl/visual/expansion/SolidVisuals.java', 'src/main/java/dev/c4lam/celeste/module/impl/visual/expansion/TrajectoryAppearance.java', 'src/main/java/dev/c4lam/celeste/module/impl/visual/expansion/UnifiedChamsRenderer.java', 'src/main/java/dev/c4lam/celeste/module/impl/visual/expansion/UnifiedVolumeRenderer.java', 'src/main/java/dev/c4lam/celeste/module/impl/visual/expansion/VisualAtmosphere.java', 'src/main/java/dev/c4lam/celeste/module/impl/visual/expansion/VisualEffects.java', 'src/main/java/dev/c4lam/celeste/module/impl/visual/expansion/VolumeSurfaceMesh.java', 'src/main/java/dev/c4lam/celeste/module/impl/visual/expansion/WorldGlowRenderer.java', 'src/main/java/dev/c4lam/celeste/module/impl/visual/expansion/WorldVfxRenderer.java', 'src/main/java/dev/c4lam/celeste/module/impl/visual/VisualWorldRenderer.java', 'src/main/java/dev/c4lam/celeste/module/impl/visual/expansion/VisualExpansionRenderer.java', 'src/main/java/dev/c4lam/celeste/module/impl/visual/expansion/VisualExpansion.java')
EXCLUDED += ('src/main/java/dev/c4lam/celeste/module/impl/combat/CrystalCombatCoordinator.java', 'src/main/java/dev/c4lam/celeste/module/impl/combat/ExplosiveAuraRuntime.java', 'src/main/java/dev/c4lam/celeste/module/impl/combat/AuraRules.java')
EXCLUDED += ('src/main/java/dev/c4lam/celeste/module/expansion/sword/EnchantedHotbar.java', 'src/main/java/dev/c4lam/celeste/module/expansion/sword/InterceptFeature.java', 'src/main/java/dev/c4lam/celeste/module/expansion/sword/PhysicalBinding.java', 'src/main/java/dev/c4lam/celeste/module/expansion/sword/SwordAttackCoordinator.java', 'src/main/java/dev/c4lam/celeste/module/expansion/sword/SwordExpansion.java', 'src/main/java/dev/c4lam/celeste/module/expansion/sword/SwordExpansionTargets.java', 'src/main/java/dev/c4lam/celeste/module/expansion/sword/VilliansLungeFeature.java', 'src/main/java/dev/c4lam/celeste/module/impl/combat/AuraServerState.java', 'src/main/java/dev/c4lam/celeste/module/impl/combat/CombatFeature.java', 'src/main/java/dev/c4lam/celeste/module/impl/combat/CombatLoadout.java', 'src/main/java/dev/c4lam/celeste/module/impl/combat/ExplosionSafety.java', 'src/main/java/dev/c4lam/celeste/module/impl/combat/HorizontalPearlTrajectory.java', 'src/main/java/dev/c4lam/celeste/module/impl/combat/KnockbackDelayFeature.java', 'src/main/java/dev/c4lam/celeste/module/impl/combat/KnockbackDisplaceFeature.java', 'src/main/java/dev/c4lam/celeste/module/impl/combat/MaceSelection.java', 'src/main/java/dev/c4lam/celeste/module/impl/combat/ShieldBreakPolicy.java', 'src/main/java/dev/c4lam/celeste/module/impl/combat/ShieldBreakSupport.java', 'src/main/java/dev/c4lam/celeste/module/impl/combat/Targeting.java', 'src/main/java/dev/c4lam/celeste/module/impl/combat/VanillaCombatReach.java', 'src/main/java/dev/c4lam/celeste/module/impl/visual/BlockEspSettings.java', 'src/main/java/dev/c4lam/celeste/module/impl/visual/expansion/TargetAuraShape.java')
TEMPLATE = "tools/packaging/ModularBuiltInFeatures.java"
MARKER = "META-INF/celeste-beta/module-source-profile.json"


def profile(overlay, repo=ROOT):
    require = prepare_runtime.require
    import module_release
    _, _, resources = module_release.resource_copy_plan(repo)
    excluded = (*EXCLUDED, *resources)
    require(all(name in overlay for name in (REGISTRY, *excluded)),
            "Modular profile requires reviewed registry and both legacy adapters")
    source = prepare_runtime.plain_path(repo, TEMPLATE).read_bytes()
    require(b"\r" not in source and b"Legacy" not in source and b"registerPayloadCatalog" in source,
            "Unexpected modular registry template")
    return {"schema": "celeste-module-source-profile-1", "mode": "independent-modules",
            "replacement": {"path": REGISTRY, "source_sha256": prepare_runtime.sha(overlay[REGISTRY]),
                            "replacement_sha256": prepare_runtime.sha(source)},
            "excluded": {name: prepare_runtime.sha(overlay[name]) for name in excluded}}, source


def apply(destination, overlay, repo=ROOT):
    """Select source before compilation; excluded classes are retained outside src/."""
    value, replacement = profile(overlay, repo)
    target = prepare_runtime.plain_path(destination, REGISTRY)
    prepare_runtime.require(target.read_bytes() == overlay[REGISTRY], "Registry changed before modular source selection")
    for name in value["excluded"]:
        original = prepare_runtime.plain_path(destination, name)
        prepare_runtime.require(original.read_bytes() == overlay[name], "Legacy source changed before exclusion")
        saved = prepare_runtime.plain_path(destination, ".prepare/module-profile-excluded/" + name, exists=False)
        saved.parent.mkdir(parents=True, exist_ok=True)
        prepare_runtime.require(not saved.exists(), "Profile exclusion output already exists")
        original.rename(saved)
    target.write_bytes(replacement)
    marker = prepare_runtime.plain_path(destination, "src/main/resources/" + MARKER, exists=False)
    prepare_runtime.require(not marker.exists(), "Source profile marker already exists")
    marker.parent.mkdir(parents=True, exist_ok=True)
    marker.write_bytes(json.dumps(value, sort_keys=True, separators=(",", ":")).encode("utf-8"))
    return value


def verify(entries, repo=ROOT):
    _, _, overlay = prepare_runtime.read_contract(repo)
    expected, _ = profile(overlay, repo)
    encoded = json.dumps(expected, sort_keys=True, separators=(",", ":")).encode("utf-8")
    prepare_runtime.require(entries.get(MARKER) == encoded, "Module artifact lacks its exact reviewed source profile")
    for path in expected["excluded"]:
        if path.startswith("src/main/resources/"):
            prepare_runtime.require(path.removeprefix("src/main/resources/") not in entries, "Excluded original resource remains in modular input")
            continue
        binary = path.removeprefix("src/main/java/").removesuffix(".java")
        prepare_runtime.require(not any(name == binary + ".class" or name.startswith(binary + "$") for name in entries),
                                "Excluded legacy implementation remains in modular input")
    return expected
