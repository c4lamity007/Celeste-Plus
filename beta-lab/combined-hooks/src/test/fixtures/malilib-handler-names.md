# Reviewed MaLiLib merged-handler spellings

Read-only bytecode audit on 2026-09-08. This is naming/provenance evidence, not
a captured clean Minecraft class or an in-game verification result. The
executable cases are in `CombinedHooksCheck.java`: both spellings, namespaces,
callback orders and independent cancellation decisions are executed there.

## Actual installed inputs

- Active Mixin JAR (identified by the 14:12:41 game startup log):
  `C:/Users/c4lam/AppData/Roaming/ModrinthApp/meta/libraries/net/fabricmc/sponge-mixin/0.17.4+mixin.0.8.7/sponge-mixin-0.17.4+mixin.0.8.7.jar`
  SHA-256 `1f0ae44db7295f8626f33b1dc0ad7f043d8954a8d6847247875fcc5dfcecc934`.
- Its `org/spongepowered/asm/mixin/transformer/MethodMapper.class`:
  SHA-256 `c3a420f02c2431ea123aaf06ad9d5e499d2e96f0ad5ad628a941f3ce68808ea7`.
- Its `org/spongepowered/asm/mixin/transformer/MixinTargetContext.class`:
  SHA-256 `5000b0bbd50518324f8d70f2235c01c2f9d82f21de890e1327cd71d4d8d687db`.
- Installed MaLiLib JAR:
  `C:/Users/c4lam/AppData/Roaming/ModrinthApp/profiles/Celeste Owner Beta QA/mods/malilib-fabric-1.21.11-0.27.20.jar`
  SHA-256 `48461c24a560c68afc4042545b654d8d5ea3796a9339681485aed76a326f6ef3`.

The reported runtime helper was exactly
`handler$bfk000$malilib$onLeftClickMouse`. `bfk000` is a generated UID, not an
artifact/version identity. The fixture deliberately includes this reported name.

## Compiled MethodMapper evidence

Inspected with JDK 21.0.11 `javap -p -c` against the active JAR above; no Mixin or
Minecraft class was initialized. Relevant instructions in `getHandlerName`:

```text
 22: aload_1
 23: ldc           #1  // String [empty]
 25: invokestatic  #74 // getMixinSourceId:(MixinInfo,String)String
 28: astore        5
 30: aload_2
 31: getfield      #46 // MixinMethodNode.name:String
 34: astore        6
 36: aload         5
 38: invokevirtual #53 // String.isEmpty:()Z
 41: ifne          134
 44: aload         6
 46: aload         5
 48: invokevirtual #55 // String.startsWith:(String)Z
 51: ifeq          112
 54: aload         6
 56: invokevirtual #54 // String.length:()I
 59: aload         5
 61: invokevirtual #54 // String.length:()I
 64: iconst_1
 65: iadd
 66: if_icmple     112
 69: iconst_2
 70: newarray       char
 72: dup
 73: iconst_0
 74: bipush        95  // '_'
 76: castore
 77: dup
 78: iconst_1
 79: bipush        36  // '$'
 81: castore
 82: aload         6
 84: aload         5
 86: invokevirtual #54 // String.length:()I
 89: invokevirtual #51 // String.charAt:(I)C
 92: invokestatic  #83 // Chars.contains:([CC)Z
 95: ifeq          112
 98: aload         6
100: aload         5
102: invokevirtual #54 // String.length:()I
105: iconst_1
106: iadd
107: invokevirtual #56 // String.substring:(I)String
110: astore        6
112: new           #14 // StringBuilder
115: dup
116: invokespecial #58 // StringBuilder.<init>:()V
119: aload         5
121: invokevirtual #60 // StringBuilder.append:(String)StringBuilder
124: bipush        36  // '$'
126: invokevirtual #59 // StringBuilder.append:(C)StringBuilder
129: invokevirtual #61 // StringBuilder.toString:()String
132: astore        5
157: ldc           #5  // String %s$%s%s$%s%s
```

The format operands at offsets 165–184 are injector prefix, class UID, method
UID, normalized source-ID prefix, and remaining handler name. Thus source
`malilib_onLeftClickMouse` becomes the suffix `malilib$onLeftClickMouse`;
`malilib_handleBreakingRestriction1` becomes `malilib$handleBreakingRestriction1`.
`getMixinSourceId` reads `IMixinConfig.getCleanSourceId()`, falling back to
`FabricUtil.getModId(config, null)` when it is null. The cached 0.17.3 build was
also inspected and has this same prefix-normalization instruction sequence.

## Exact reviewed identities

| Target | Source handler | Runtime normalized suffix | MixinMerged origin |
| --- | --- | --- | --- |
| `method_1536()Z` / `doAttack()Z` | `malilib_onLeftClickMouse` | `malilib$onLeftClickMouse` | `fi.dy.masa.malilib.mixin.test.MixinMinecraft_testSelector` |
| `method_2910(BlockPos,Direction)Z` / `attackBlock(BlockPos,Direction)Z` | `malilib_handleBreakingRestriction1` | `malilib$handleBreakingRestriction1` | `fi.dy.masa.malilib.mixin.test.MixinClientPlayInteractionManager_testSelector` |

Both are private instance `@Inject(at=HEAD, cancellable=true)` callbacks at
priority 1000 (`@Mixin` has no override; compiled `Mixin.priority()` default is
1000). Their descriptors append `CallbackInfoReturnable` to the exact original
method arguments and return void. The client helper calls `cir.cancel()` after
its selector operation; the interaction helper calls `cir.setReturnValue(false)`.

Compiled `MixinTargetContext.methodMerged` offsets 27–74 writes one visible
`MixinMerged` annotation with `mixin=getClassName()`, `priority=getPriority()` and
`sessionId`. Handler renaming does not change either reviewed origin.

## Structural compatibility regression contract

The source and normalized spellings are historical naming evidence, not an
allowlist. Standard guards from arbitrary nonblank MixinMerged origins and any
integer priority are accepted. Independent injection sites may reuse the same
helper or use both naming aliases; neither a generated UID nor a mod name can
determine cancellation semantics. Duplicate helper declarations and missing,
ambiguous or malformed metadata still fail closed.

`CombinedHooksCheck` executes callback-only, original-argument, CI-first,
captured-local and static-captured helpers. Captured reference and category-2
`long` values are checked at runtime. Every interaction result category is
exercised with no cancellation, cancellation at the first site, and cancellation
at a second independent invocation of the same helper. Noncancellable observer
callbacks remain intact. A reflection-only parser matrix additionally executes
void, all eight primitive result types, object and both reference/multidimensional
primitive array results without loading Minecraft. A well-formed bytecode case
that supplies one initialized callback-info object and checks another is rejected.

The installed Litematica 0.26.16 easy-place mixin provides a third concrete case:
`fi.dy.masa.litematica.mixin.easyplace.MixinClientPlayerInteractionManager_easyPlace`
has explicit priority 980 and source helper `litematica_onInteractBlock`, reported
at runtime as `handler$bco000$litematica$onInteractBlock`. Its intermediary
descriptor is `(Lnet/minecraft/class_746;Lnet/minecraft/class_1268;Lnet/minecraft/class_3965;Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable;)V`.
The compiled mixin class SHA-256 is
`d62493e0c55b792f92922794ff6cd980a3124550410126320afe54e2178aaf14`.
The fixture uses this exact runtime name, descriptor and metadata in its
original-argument shape, and exercises independent generic shapes at the same
target. Helpers, guards, cancellation results and callback order are retained.

The network fixture covers a previously unreviewed edited target: cancellation
does not acquire the startup TAIL callback, the final ordinary return retains
it, and an earlier ordinary return does not acquire it. The separate
`GenericReturnHooksCheck` covers additional edited owners and RETURN/TAIL hooks.
These are deterministic reconstructed fixtures, not an actual merged game dump
or evidence that arbitrary third-party bytecode transformations are compatible.
