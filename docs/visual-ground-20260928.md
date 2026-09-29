# Celeste+ Ground Shaders

Release: `celeste-plus-20260928-visuals-06`.

## Use

Enable **Visuals > Ground Shaders**. Choose **Currents** for flowing light or **Veins** for connected lines. Defaults: disabled, Currents, 35% intensity, 32-block range, scale 1 and speed 0.6. Larger Scale makes the pattern larger.

Atmosphere's Basic presets supply the color. Advanced or Atmosphere off uses Ground Shaders' saved Color (blue/cyan by default); switching presets preserves that setting.

The effect follows visible, upward-facing terrain using scene depth. It renders before entities, without tinting entities or writing terrain depth. Shader packs and their shadow passes opt out. Disabling the module, changing worlds and disconnecting clear its frame/resource state.

## Verification

- Build, Atmosphere regression, Ground Shaders lifecycle and shader GPU checks passed.
- All 41 native checks and 15 Java protection stages passed.
- Actual-catalog fixtures: 82 passed; one expected Windows symlink-privilege skip.
- Live verification matched 78 game files and 28 backend source hashes, signed manifests, the public loader and five anonymous download denials.

**Not verified in game:** the user is handling the Minecraft check. GPU previews are synthetic, not Minecraft screenshots. In-game appearance and performance remain unmeasured. Try both styles, nearby slopes, fog, module toggles and a world change.

Keep the existing **Celeste+Setup.exe**. Fully restart Minecraft and load normally for this update.

## Release identity

- Raw runtime: `5f3c00c025f6ae8f067f123d013936e7c783715c2cec57bf2008b598200cf563`
- Protected payload: `8c6a5235c9ff8f83520b41accf3162751d29f3da7364614e342349c10c92ef25`
- Catalog: `bcec45f3293071917e43a9437ee361333d1f1b1f5f30fc10f033c5d23813c52b`
- QA run: `20260929T015404Z-5c1a71d9`; receipt `a84d83fd799753064fd5aa3157ebd83a5c8dc5d1728e5f6f15442f17414016ae`
- Protection result: `302c94ffff985934b1f4906f8faff46b13a630656451e2aa66953a944304ac3f`
- Live receipt: `122075f4833e6b2446f60bcdd4909071a37e747083fd2d367ed88b57e455df79`
- Live image: `sha256:947f1a9c70582633e7ea7a5ae2cd432000590e77800568048332caa00d948d26`
