param(
    [Parameter(Mandatory=$true)][string]$ProjectRoot,
    [Parameter(Mandatory=$true)][string]$OutputRoot,
    [string]$JdkHome='C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot'
)
$ErrorActionPreference='Stop'
$taskProject=(Resolve-Path -LiteralPath $ProjectRoot).Path
$taskOutput=[IO.Path]::GetFullPath($OutputRoot)
$taskClasses=Join-Path $taskOutput 'classes'
$taskFixtures=Join-Path $taskOutput 'fixtures'
New-Item -ItemType Directory -Force -Path $taskClasses,$taskFixtures | Out-Null
$taskCache=Join-Path $env:USERPROFILE '.gradle\caches\modules-2\files-2.1'
$taskGson=@(rg --files (Join-Path $taskCache 'com.google.code.gson\gson\2.11.0') -g '*.jar')[0]
$taskJoml=@(rg --files (Join-Path $taskCache 'org.joml\joml\1.10.8') -g '*.jar')[0]
if(-not $taskGson -or -not $taskJoml){throw 'Cached Gson and JOML are required; no downloads attempted'}
$taskClasspath="$taskGson;$taskJoml"
# Replace only graphics submission boundaries. Font, glyph geometry and all atlas metadata are production sources.
$taskStubs=@{
 'dev/c4lam/celeste/render/GuiPipelines.java'='package dev.c4lam.celeste.render; public final class GuiPipelines { public static final Object TEXT=new Object(); }'
 'dev/c4lam/celeste/render/GuiTextures.java'='package dev.c4lam.celeste.render; public final class GuiTextures { public static Object linear(){return null;} }'
 'dev/c4lam/celeste/render/GuiUniform.java'='package dev.c4lam.celeste.render; public final class GuiUniform { public static GuiUniform of(float... values){return new GuiUniform();} }'
 'dev/c4lam/celeste/render/GuiRenderer.java'='package dev.c4lam.celeste.render; import net.minecraft.client.render.BufferBuilder; import net.minecraft.client.texture.TextureSetup; public final class GuiRenderer { private static final GuiRenderer INSTANCE=new GuiRenderer(); public static BufferBuilder last; public static GuiRenderer getInstance(){return INSTANCE;} public BufferBuilder begin(Object pipeline){last=new BufferBuilder();return last;} public void submit(BufferBuilder builder,Object pipeline,TextureSetup texture,GuiUniform uniform){} }'
 'dev/c4lam/celeste/render/font/FontBuilder.java'='package dev.c4lam.celeste.render.font; public final class FontBuilder {}'
 'net/minecraft/client/render/VertexConsumer.java'='package net.minecraft.client.render; import org.joml.Matrix4f; public interface VertexConsumer { VertexConsumer vertex(Matrix4f matrix,float x,float y,float z); VertexConsumer texture(float u,float v); VertexConsumer color(int argb); }'
 'net/minecraft/client/render/BufferBuilder.java'='package net.minecraft.client.render; import java.util.*; import org.joml.Matrix4f; public final class BufferBuilder implements VertexConsumer { public final List<List<Float>> vertices=new ArrayList<>(); private float x,y,z,u,v; public VertexConsumer vertex(Matrix4f matrix,float x,float y,float z){this.x=x;this.y=y;this.z=z;return this;} public VertexConsumer texture(float u,float v){this.u=u;this.v=v;return this;} public VertexConsumer color(int argb){vertices.add(List.of(x,y,z,u,v));return this;} }'
 'net/minecraft/client/texture/AbstractTexture.java'='package net.minecraft.client.texture; public class AbstractTexture { public Object getGlTextureView(){return null;} }'
 'net/minecraft/client/texture/TextureSetup.java'='package net.minecraft.client.texture; public final class TextureSetup { public static TextureSetup of(Object view,Object sampler){return new TextureSetup();} }'
 'net/minecraft/client/util/math/MatrixStack.java'='package net.minecraft.client.util.math; import org.joml.Matrix4f; public final class MatrixStack { public Entry peek(){return new Entry();} public static final class Entry { public Matrix4f getPositionMatrix(){return new Matrix4f();} } }'
}
$taskSources=@()
foreach($taskStub in $taskStubs.GetEnumerator()){
    $taskPath=Join-Path $taskFixtures $taskStub.Key
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $taskPath) | Out-Null
    [IO.File]::WriteAllText($taskPath,$taskStub.Value)
    $taskSources+=$taskPath
}
foreach($taskSource in @('src/main/java/dev/c4lam/celeste/render/font/Font.java','src/main/java/dev/c4lam/celeste/render/font/FontData.java','src/main/java/dev/c4lam/celeste/render/font/MsdfGlyph.java','tools/qa/FontUnicodeCheck.java')){
    $taskSources+=Join-Path $taskProject $taskSource
}
$taskCompile=@(& (Join-Path $JdkHome 'bin/javac.exe') --release 21 -encoding UTF-8 -cp $taskClasspath -d $taskClasses @taskSources 2>&1)
$taskExit=$LASTEXITCODE
$taskCompile | Set-Content -LiteralPath (Join-Path $taskOutput 'compile.log')
if($taskExit -ne 0){throw "Font Unicode compile failed: $taskCompile"}
$taskResults=@()
foreach($taskCheck in @('dev.c4lam.celeste.render.font.FontUnicodeCheck')){
    $taskLines=@(& (Join-Path $JdkHome 'bin/java.exe') -ea -cp "$taskClasses;$taskClasspath" $taskCheck $taskProject 2>&1)
    $taskResults+=$taskLines
    if($LASTEXITCODE -ne 0){$taskResults | Set-Content -LiteralPath (Join-Path $taskOutput 'behavior.log'); throw "Font Unicode check failed: $taskLines"}
}
$taskResults | Set-Content -LiteralPath (Join-Path $taskOutput 'behavior.log')
$taskResults | Write-Output
