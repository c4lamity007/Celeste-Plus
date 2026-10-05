package dev.c4lam.celeste.module.delivery;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.shaders.ShaderType;
import com.mojang.blaze3d.systems.RenderSystem;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.CompiledShader;
import net.minecraft.client.gl.CompiledShaderPipeline;
import net.minecraft.client.gl.GlBackend;
import net.minecraft.client.gl.GlImportProcessor;
import net.minecraft.client.gl.ShaderProgram;
import net.minecraft.util.Identifier;

/** Resident shader compilation and exact per-pipeline GPU retirement for the supported game backend. */
public final class ModulePipelineResources {
    private static final Set<String> retiredResourcePaths = new HashSet<>();
    private static final Map<String,dev.c4lam.celeste.compat.BetaTextureLease> textureLeases = new java.util.HashMap<>();
    private static java.lang.ref.WeakReference<Object> purgedShaderCache = new java.lang.ref.WeakReference<>(null);
    static {
        ModuleResourceAssets.setRetirementObserver(ModulePipelineResources::resourcesRetired);
        ModuleResourceAssets.setInstallationObserver(ModulePipelineResources::resourcesAvailable);
    }
    private ModulePipelineResources() { }

    public static void initialize() { }

    /** Assets are installed before factory/class initialization. No full game resource reload is needed. */
    public static void register(ModuleScope scope, RenderPipeline pipeline) {
        Objects.requireNonNull(scope); Objects.requireNonNull(pipeline);
        if (!(RenderSystem.getDevice() instanceof GlBackend backend))
            throw new IllegalStateException("Independent module shader lifetime requires the supported OpenGL backend");
        // Resolve the fixed-version adapter before publishing a GPU resource which could not be retired.
        Map<RenderPipeline, CompiledShaderPipeline> pipelines = Access.pipelines(backend);
        Access.shaders(backend);
        SourceCache.CACHE.getDeclaringClass();
        if (pipelines.containsKey(pipeline))
            throw new IllegalStateException("Module pipeline was compiled outside its owning scope");
        scope.releaseAfterCallbacks(() -> retire(backend, pipeline));
        var compiled = backend.precompilePipeline(pipeline, (id, type) -> source(scope, id, type));
        if (!compiled.isValid()) throw new IllegalStateException("Module shader compilation failed: " + pipeline.getLocation());
    }

    /** Raw module sources must be preprocessed with the same import mechanism as ordinary game shaders. */
    private static String source(ModuleScope scope, Identifier id, ShaderType type) {
        Identifier file = type.idConverter().toResourcePath(id);
        byte[] owned = ModuleResourceAssets.bytes(scope, path(file));
        if (owned == null) return MinecraftClient.getInstance().getShaderLoader().getSource(id, type);
        String text = new String(owned, StandardCharsets.UTF_8);
        Set<Identifier> processed = new HashSet<>();
        String parent = file.getPath().substring(0, file.getPath().lastIndexOf('/') + 1);
        GlImportProcessor processor = new GlImportProcessor() {
            @Override public String loadImport(boolean relative, String name) {
                Identifier target;
                try {
                    target = relative ? Identifier.of(file.getNamespace(), normalize(parent + name))
                        : Identifier.of(name).withPrefixedPath("shaders/include/");
                } catch (RuntimeException invalid) { return "#error Invalid module shader import\n"; }
                if (!processed.add(target)) return null;
                byte[] bytes = ModuleResourceAssets.bytes(scope, path(target));
                if (bytes != null) return new String(bytes, StandardCharsets.UTF_8);
                try (var input = MinecraftClient.getInstance().getResourceManager().getResourceOrThrow(target).getInputStream()) {
                    return new String(input.readAllBytes(), StandardCharsets.UTF_8);
                } catch (IOException missing) { return "#error Missing module shader import\n"; }
            }
        };
        return String.join("", processor.readSource(text));
    }

    private static String normalize(String path) {
        java.util.ArrayDeque<String> parts = new java.util.ArrayDeque<>();
        for (String part : path.split("/")) {
            if (part.equals("..")) {
                if (parts.isEmpty()) throw new IllegalArgumentException("Import escapes namespace");
                parts.removeLast();
            } else if (!part.isEmpty() && !part.equals(".")) parts.addLast(part);
        }
        return String.join("/", parts);
    }

    private static String path(Identifier id) { return "assets/" + id.getNamespace() + "/" + id.getPath(); }

    public static synchronized void resourcesAvailable(Set<String> paths) {
        retiredResourcePaths.removeAll(paths);
        for(String path:paths) if(path.startsWith("assets/celeste/textures/") && path.endsWith(".png"))
            textureLeases.computeIfAbsent(path, entry->dev.c4lam.celeste.compat.BetaTextureLease.open(
                MinecraftClient.getInstance().getTextureManager(),Set.of(entry)));
    }

    /** Called by the resident asset registry only when the last module owner for a path has drained. */
    public static synchronized void resourcesRetired(Set<String> paths) {
        for(String path:paths) {
            var lease=textureLeases.remove(path);
            if(lease!=null)lease.close();
        }
        for (String path : paths)
            if (path.startsWith("assets/celeste/shaders/") && (path.endsWith(".vsh") || path.endsWith(".fsh")))
                retiredResourcePaths.add(path);
        purgedShaderCache.clear();
        maintenance();
    }

    /** Handles resource reload preparation which raced with retirement, without reloading the game. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static synchronized void maintenance() {
        if (retiredResourcePaths.isEmpty()) return;
        Object cache = SourceCache.get(SourceCache.CACHE, MinecraftClient.getInstance().getShaderLoader());
        if (cache == purgedShaderCache.get()) return;
        purgeSources(cache, retiredResourcePaths);
        purgedShaderCache = new java.lang.ref.WeakReference<>(cache);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    static void purgeSources(Object cache, Set<String> paths) {
        var definitions = (net.minecraft.client.gl.ShaderLoader.Definitions) SourceCache.get(SourceCache.DEFINITIONS, cache);
        Map<Object,String> kept = new java.util.HashMap<>((Map)definitions.shaderSources());
        boolean changed = kept.keySet().removeIf(key -> {
            Identifier id=(Identifier)SourceCache.get(SourceCache.ID,key);
            ShaderType type=(ShaderType)SourceCache.get(SourceCache.TYPE,key);
            return paths.contains(path(type.idConverter().toResourcePath(id)));
        });
        if (changed) {
            var replacement = new net.minecraft.client.gl.ShaderLoader.Definitions((Map)Map.copyOf(kept), definitions.postChains());
            try { SourceCache.DEFINITIONS.set(cache,replacement); }
            catch (IllegalAccessException failure) { throw new IllegalStateException("Module shader sources could not retire",failure); }
        }
    }

    /** All callbacks/passes have drained before this executes on the render/client owner thread. */
    private static void retire(GlBackend backend, RenderPipeline pipeline) {
        Map<RenderPipeline, CompiledShaderPipeline> pipelines = Access.pipelines(backend);
        retireCaches(pipelines, Access.shaders(backend), pipeline, ShaderProgram::close, CompiledShader::close);
    }

    static void retireCaches(Map<RenderPipeline, CompiledShaderPipeline> pipelines, Map<Object,CompiledShader> shaders,
                             RenderPipeline pipeline, java.util.function.Consumer<ShaderProgram> closeProgram,
                             java.util.function.Consumer<CompiledShader> closeShader) {
        CompiledShaderPipeline compiled = pipelines.remove(pipeline);
        if (compiled != null && compiled.program() != ShaderProgram.INVALID
                && pipelines.values().stream().noneMatch(other -> other.program() == compiled.program()))
            closeProgram.accept(compiled.program());
        // Program links share shader objects. Keep a stage used by any surviving pipeline, regardless of owner.
        for (var iterator = shaders.entrySet().iterator(); iterator.hasNext();) {
            var entry = iterator.next();
            Identifier id = Access.shaderId(entry.getKey());
            if (!id.getNamespace().equals("celeste")) continue;
            ShaderType type = Access.shaderType(entry.getKey());
            Object defines = Access.shaderDefines(entry.getKey());
            Identifier expected = type == ShaderType.VERTEX ? pipeline.getVertexShader() : pipeline.getFragmentShader();
            if (!id.equals(expected) || !defines.equals(pipeline.getShaderDefines())) continue;
            boolean stillUsed = pipelines.keySet().stream().anyMatch(other ->
                defines.equals(other.getShaderDefines()) && id.equals(type == ShaderType.VERTEX
                    ? other.getVertexShader() : other.getFragmentShader()));
            if (stillUsed) continue;
            iterator.remove();
            if (entry.getValue() != CompiledShader.INVALID_SHADER
                    && shaders.values().stream().noneMatch(other -> other == entry.getValue()))
                closeShader.accept(entry.getValue());
        }
    }

    /** Named and intermediary members are checked explicitly; an incompatible game fails closed. */
    private static final class Access {
        private static final Field PIPELINES = field(GlBackend.class, Map.class, "pipelineCompileCache", "field_57860");
        private static final Field SHADERS = field(GlBackend.class, Map.class, "shaderCompileCache", "field_57861");
        private static final Class<?> KEY = shaderKey();
        private static final Field ID = field(KEY, Identifier.class, "id", "comp_3108");
        private static final Field TYPE = field(KEY, ShaderType.class, "type", "comp_3109");
        private static final Field DEFINES = field(KEY, net.minecraft.client.gl.Defines.class, "defines", "comp_3110");
        @SuppressWarnings("unchecked") static Map<RenderPipeline,CompiledShaderPipeline> pipelines(GlBackend backend) {
            return (Map<RenderPipeline,CompiledShaderPipeline>) get(PIPELINES, backend);
        }
        @SuppressWarnings("unchecked") static Map<Object,CompiledShader> shaders(GlBackend backend) {
            return (Map<Object,CompiledShader>) get(SHADERS, backend);
        }
        static Identifier shaderId(Object key) { return (Identifier)get(ID,key); }
        static ShaderType shaderType(Object key) { return (ShaderType)get(TYPE,key); }
        static Object shaderDefines(Object key) { return get(DEFINES,key); }
        private static Object get(Field field, Object instance) {
            try { return field.get(instance); }
            catch (IllegalAccessException failure) { throw new IllegalStateException("Module GPU cache is inaccessible",failure); }
        }
        private static Class<?> shaderKey() {
            for (Class<?> nested : GlBackend.class.getDeclaredClasses()) {
                if (nested.getSimpleName().equals("ShaderKey") || nested.getSimpleName().equals("class_10154")) return nested;
            }
            throw new IllegalStateException("Missing supported shader-cache key");
        }
        private static Field field(Class<?> owner,Class<?> expected,String...names) {
            Field result=null;
            for(String name:names) try {
                Field candidate=owner.getDeclaredField(name);
                if(candidate.getType()!=expected || Modifier.isStatic(candidate.getModifiers()) || result!=null)
                    throw new IllegalStateException("Unexpected supported shader-cache field");
                candidate.setAccessible(true);result=candidate;
            } catch(NoSuchFieldException absent) { }
            if(result==null)throw new IllegalStateException("Missing supported shader-cache field on "+owner.getName());
            return result;
        }
    }

    private static final class SourceCache {
        private static final Field CACHE=Access.field(net.minecraft.client.gl.ShaderLoader.class,
            nested(net.minecraft.client.gl.ShaderLoader.class,"Cache","class_10170"),"cache","field_54020");
        private static final Field DEFINITIONS=Access.field(CACHE.getType(),net.minecraft.client.gl.ShaderLoader.Definitions.class,
            "definitions","field_54022");
        private static final Class<?> KEY=nested(net.minecraft.client.gl.ShaderLoader.class,"ShaderSourceKey","class_10155");
        private static final Field ID=Access.field(KEY,Identifier.class,"id","comp_3111");
        private static final Field TYPE=Access.field(KEY,ShaderType.class,"type","comp_3112");
        private static Class<?> nested(Class<?> parent,String...names) {
            for(Class<?> child:parent.getDeclaredClasses())for(String name:names)
                if(child.getSimpleName().equals(name))return child;
            throw new IllegalStateException("Missing supported shader source cache class");
        }
        private static Object get(Field field,Object instance) { return Access.get(field,instance); }
    }
}
