import dev.c4lam.celeste.module.impl.visual.expansion.GroundShadersMath;
import dev.c4lam.celeste.module.impl.visual.expansion.VisualAtmosphere;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;
import org.objectweb.asm.*;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;

/** Executes production feature and renderer lifecycle bytecode with external boundaries substituted. */
public final class GroundShadersLifecycleCheck {
    static final String ROOT="dev/c4lam/celeste/module/impl/visual/expansion/";
    static final String FEATURE=ROOT+"GroundShadersFeature",RENDERER=ROOT+"GroundShadersRenderer";
    static int checks;
    static void require(boolean ok,String message){checks++;if(!ok)throw new AssertionError(message);}
    public interface ProbeFeature { }
    public static final class ProbeWorld { }
    public static final class ProbeModule { public boolean enabled=true; public boolean enabled(){return enabled;} }
    public static final class ProbeClient {
        public static final ProbeClient INSTANCE=new ProbeClient();
        public ProbeWorld world; public Object player=new Object(); public final List<Runnable> pending=new ArrayList<>();
        public static ProbeClient getInstance(){return INSTANCE;}
        public void execute(Runnable action){pending.add(action);}
    }
    public static final class ProbeContext {
        public final ProbeClient client=new ProbeClient();public final ProbeModule module=new ProbeModule();
        public final Map<String,String> modes=new HashMap<>();public final Map<String,Double> sliders=new HashMap<>();
        public int color=0x2EAFD6;
        public ProbeClient client(){return client;} public ProbeModule module(){return module;}
        public String mode(String key,String fallback){return modes.getOrDefault(key,fallback);}
        public double slider(String key,double fallback){return sliders.getOrDefault(key,fallback);}
        public int colorPick(String key,int fallback){return color;}
    }
    public record ProbeMask(ProbeWorld world,int revision) { }
    public static final class ProbeReachability {
        static int ticks,resets;static ProbeMask mask;
        public static void tick(ProbeWorld world,Object player){ticks++;mask=new ProbeMask(world,ticks);}
        public static ProbeMask snapshot(){return mask;}
        public static void reset(){resets++;mask=null;}
        public static void invalidate(){reset();}
    }
    public record ProbeOptions(Object worldToken,GroundShadersMath.Settings settings,ProbeMask reachable) { }
    public static final class ProbeActiveFeature {static ProbeWorld world;static ProbeOptions active;public static ProbeOptions options(ProbeWorld candidate){return candidate==world?active:null;}}
    public static final class ProbeMatrix { }
    public static final class ProbeVector { }
    public static final class ProbeReset { static int calls; public static void reset(){calls++;} }
    public static final class ProbeRenderSystem {
        public static boolean renderThread=true;
        public static boolean isOnRenderThread(){return renderThread;}
        public static void assertOnRenderThread(){require(renderThread,"GPU resource operation off render thread");}
    }
    public static class ProbeTexture { public int closes; public boolean isClosed(){return closes>0;} public void close(){closes++;} }
    public static final class ProbeView { public int closes;public boolean fail;public boolean isClosed(){return closes>0;}public void close(){closes++;if(fail)throw new IllegalStateException("fixture close failure");} }
    public static final class ProbeEvent<T> { public final List<T> listeners=new ArrayList<>();public void register(T listener){listeners.add(listener);} }
    public interface ProbeExtraction { }
    public interface ProbeRenderContext { }
    public static final class ProbeRenderEvents {
        public interface EndExtraction { void endExtraction(ProbeExtraction context); }
        public interface BeforeEntities { void beforeEntities(ProbeRenderContext context); }
        public static final ProbeEvent<EndExtraction> END_EXTRACTION=new ProbeEvent<>();
        public static final ProbeEvent<BeforeEntities> BEFORE_ENTITIES=new ProbeEvent<>();
    }
    public static final class ProbeLifecycle {
        public interface ClientStopping { void onClientStopping(ProbeClient client); }
        public static final ProbeEvent<ClientStopping> CLIENT_STOPPING=new ProbeEvent<>();
    }
    public static final class ProbeHandler { }
    public static final class ProbeConnections {
        public interface Disconnect { void onPlayDisconnect(ProbeHandler handler,ProbeClient client); }
        public static final ProbeEvent<Disconnect> DISCONNECT=new ProbeEvent<>();
    }
    public static final class ProbeManager { }
    public interface ProbeReloader { void reload(ProbeManager manager); }
    public enum ProbeResourceType { CLIENT_RESOURCES }
    public record ProbeId(String namespace,String path){public static ProbeId of(String n,String p){return new ProbeId(n,p);} }
    public interface ProbeResources {
        ProbeResourcesImpl INSTANCE=new ProbeResourcesImpl();
        static ProbeResources get(ProbeResourceType type){return INSTANCE;}
        void registerReloader(ProbeId id,ProbeReloader listener);
    }
    public static final class ProbeResourcesImpl implements ProbeResources {
        public final List<ProbeReloader> listeners=new ArrayList<>();
        public void registerReloader(ProbeId id,ProbeReloader listener){listeners.add(listener);}
    }
    public static final class ProbeSubscriptions {
        public static final Set<String> names=new HashSet<>();
        public static <T> void register(Object owner,String name,Class<T> type,T listener,Consumer<? super T> registrar){require(names.add(name),"duplicate subscription: "+name);registrar.accept(listener);}
    }
    static final class FixtureLoader extends ClassLoader {
        final Path classes;final boolean renderer;
        FixtureLoader(Path classes,boolean renderer){super(GroundShadersLifecycleCheck.class.getClassLoader());this.classes=classes;this.renderer=renderer;}
        @Override protected Class<?> findClass(String name)throws ClassNotFoundException{
            String internal=name.replace('.','/'),target=renderer?RENDERER:FEATURE;
            if(!internal.equals(target)&&!internal.startsWith(target+"$"))throw new ClassNotFoundException(name);
            try{
                Map<String,String> map=new HashMap<>();
                map.put(ROOT+"GroundReachability","GroundShadersLifecycleCheck$ProbeReachability");
                map.put(ROOT+"GroundReachability$Mask","GroundShadersLifecycleCheck$ProbeMask");
                map.put("dev/c4lam/celeste/module/ModuleFeature","GroundShadersLifecycleCheck$ProbeFeature");
                map.put("dev/c4lam/celeste/module/ModuleContext","GroundShadersLifecycleCheck$ProbeContext");
                map.put("dev/c4lam/celeste/gui/model/GuiModule","GroundShadersLifecycleCheck$ProbeModule");
                map.put("net/minecraft/client/MinecraftClient","GroundShadersLifecycleCheck$ProbeClient");
                map.put("net/minecraft/client/world/ClientWorld","GroundShadersLifecycleCheck$ProbeWorld");
                map.put("net/minecraft/client/network/ClientPlayerEntity","java/lang/Object");
                if(!renderer)map.put(RENDERER,"GroundShadersLifecycleCheck$ProbeReset");
                else {map.put(FEATURE,"GroundShadersLifecycleCheck$ProbeActiveFeature");map.put(FEATURE+"$Options","GroundShadersLifecycleCheck$ProbeOptions");map.put("org/joml/Matrix4f","GroundShadersLifecycleCheck$ProbeMatrix");map.put("org/joml/Vector3f","GroundShadersLifecycleCheck$ProbeVector");}
                map.put("com/mojang/blaze3d/systems/RenderSystem","GroundShadersLifecycleCheck$ProbeRenderSystem");
                map.put("com/mojang/blaze3d/textures/GpuTexture","GroundShadersLifecycleCheck$ProbeTexture");
                map.put("com/mojang/blaze3d/textures/GpuTextureView","GroundShadersLifecycleCheck$ProbeView");
                map.put("dev/c4lam/celeste/beta/lifecycle/BetaSubscriptions","GroundShadersLifecycleCheck$ProbeSubscriptions");
                map.put("net/fabricmc/fabric/api/event/Event","GroundShadersLifecycleCheck$ProbeEvent");
                String events="net/fabricmc/fabric/api/client/rendering/v1/world/WorldRenderEvents";
                map.put(events,"GroundShadersLifecycleCheck$ProbeRenderEvents");
                map.put(events+"$EndExtraction","GroundShadersLifecycleCheck$ProbeRenderEvents$EndExtraction");
                map.put(events+"$BeforeEntities","GroundShadersLifecycleCheck$ProbeRenderEvents$BeforeEntities");
                map.put("net/fabricmc/fabric/api/client/rendering/v1/world/WorldExtractionContext","GroundShadersLifecycleCheck$ProbeExtraction");
                map.put("net/fabricmc/fabric/api/client/rendering/v1/world/WorldRenderContext","GroundShadersLifecycleCheck$ProbeRenderContext");
                String lifecycle="net/fabricmc/fabric/api/client/event/lifecycle/v1/ClientLifecycleEvents";
                map.put(lifecycle,"GroundShadersLifecycleCheck$ProbeLifecycle");map.put(lifecycle+"$ClientStopping","GroundShadersLifecycleCheck$ProbeLifecycle$ClientStopping");
                String connection="net/fabricmc/fabric/api/client/networking/v1/ClientPlayConnectionEvents";
                map.put(connection,"GroundShadersLifecycleCheck$ProbeConnections");map.put(connection+"$Disconnect","GroundShadersLifecycleCheck$ProbeConnections$Disconnect");
                map.put("net/minecraft/client/network/ClientPlayNetworkHandler","GroundShadersLifecycleCheck$ProbeHandler");
                map.put("net/fabricmc/fabric/api/resource/v1/ResourceLoader","GroundShadersLifecycleCheck$ProbeResources");
                map.put("net/minecraft/resource/ResourceType","GroundShadersLifecycleCheck$ProbeResourceType");
                map.put("net/minecraft/resource/SynchronousResourceReloader","GroundShadersLifecycleCheck$ProbeReloader");
                map.put("net/minecraft/resource/ResourceReloader","GroundShadersLifecycleCheck$ProbeReloader");
                map.put("net/minecraft/resource/ResourceManager","GroundShadersLifecycleCheck$ProbeManager");
                map.put("net/minecraft/util/Identifier","GroundShadersLifecycleCheck$ProbeId");
                ClassWriter writer=new ClassWriter(0);ClassVisitor remap=new ClassRemapper(writer,new Remapper(){@Override public String map(String value){return map.getOrDefault(value,value);}});
                ClassVisitor select=renderer&&internal.equals(RENDERER)?new ClassVisitor(Opcodes.ASM9,remap){
                    @Override public FieldVisitor visitField(int access,String n,String d,String s,Object v){return Set.of("depth","depthView","generation","initialized","reachableTexture","reachableView","uploadedMask").contains(n)?super.visitField(access,n,d,s,v):null;}
                    @Override public MethodVisitor visitMethod(int access,String n,String d,String s,String[] exceptions){
                        if(n.equals("<clinit>"))return null;
                        if(n.equals("draw")||n.equals("extract")){
                            MethodVisitor mv=super.visitMethod(access,n,d,s,exceptions);mv.visitCode();mv.visitInsn(Opcodes.RETURN);mv.visitMaxs(0,1);mv.visitEnd();return null;
                        }
                        return Set.of("initialize","reset","releaseResources","releaseReachability","isCurrent","id").contains(n)||n.startsWith("lambda$initialize$")?super.visitMethod(access,n,d,s,exceptions):null;
                    }
                }:remap;
                new ClassReader(Files.readAllBytes(classes.resolve(internal+".class"))).accept(select,0);
                byte[] bytes=writer.toByteArray();return defineClass(name,bytes,0,bytes.length);
            }catch(Exception e){throw new ClassNotFoundException(name,e);}
        }
    }
    static Object call(Method m,Object owner,Object...args)throws Exception{return m.invoke(owner,args);}
    static GroundShadersMath.Settings settings(Method method,ProbeWorld world)throws Exception{return (GroundShadersMath.Settings)call(method,null,world);}
    static Object token(Method method,ProbeWorld world)throws Exception{Object options=call(method,null,world);if(options==null)return null;Method token=options.getClass().getDeclaredMethod("worldToken");token.setAccessible(true);return token.invoke(options);}
    static ProbeMask mask(Method method,ProbeWorld world)throws Exception{Object options=call(method,null,world);if(options==null)return null;Method member=options.getClass().getDeclaredMethod("reachable");member.setAccessible(true);return (ProbeMask)member.invoke(options);}
    static void feature(Path classes)throws Exception{
        Class<?> f=new FixtureLoader(classes,false).loadClass(FEATURE.replace('/','.'));Object module=f.getConstructor().newInstance();
        Method enable=f.getMethod("onEnable",ProbeContext.class),tick=f.getMethod("onStartTick",ProbeContext.class),render=f.getMethod("onRenderFrame",ProbeContext.class),disable=f.getMethod("onDisable",ProbeContext.class),changed=f.getMethod("onWorldChanged",ProbeContext.class);
        Method setting=f.getDeclaredMethod("settings",ProbeWorld.class),options=f.getDeclaredMethod("options",ProbeWorld.class);setting.setAccessible(true);options.setAccessible(true);
        ProbeContext context=new ProbeContext();ProbeWorld first=new ProbeWorld(),second=new ProbeWorld();
        require(settings(setting,first)==null,"initial settings active");context.client.world=first;context.module.enabled=false;call(tick,module,context);require(settings(setting,first)==null,"disabled tick reactivates effect");
        context.module.enabled=true;int resets=ProbeReset.calls;call(enable,module,context);var initial=settings(setting,first);Object firstToken=token(options,first);
        require(initial!=null&&initial.intensity()==.35f&&initial.color()==0x2EAFD6,"default enable settings");require(ProbeReset.calls==resets+1&&firstToken!=null,"first world token/reset missing");
        require(ProbeReachability.ticks==1&&mask(options,first).world()==first,"enable did not sample current-world reachability");
        call(tick,module,context);int graphTicks=ProbeReachability.ticks;ProbeMask graphMask=mask(options,first);for(int i=0;i<20;i++)call(render,module,context);require(ProbeReachability.ticks==graphTicks&&mask(options,first)==graphMask,"render frame queries/rebuilds walkability");require(token(options,first)==firstToken&&ProbeReset.calls==resets+1,"steady frames reset resources/token");require(settings(setting,second)==null,"world identity leaked");
        context.modes.put("Mode","Veins");context.sliders.put("Range",0.0);context.sliders.put("Scale",Double.NaN);context.sliders.put("Speed",Double.POSITIVE_INFINITY);
        context.sliders.put("Intensity",.7);context.color=0xAA123456;call(render,module,context);var s=settings(setting,first);
        require(s.intensity()==.7f&&s.color()==0x123456,"render-frame settings not updated/sanitized; legacy settings must be ignored");
        context.sliders.put("Intensity",Double.NaN);call(tick,module,context);require(settings(setting,first).intensity()==.35f,"NaN intensity not defaulted");
        context.sliders.put("Intensity",Double.POSITIVE_INFINITY);call(tick,module,context);require(settings(setting,first).intensity()==.35f,"infinite intensity not defaulted");
        context.sliders.put("Intensity",2.0);call(tick,module,context);require(settings(setting,first).intensity()==1,"upper intensity not clamped");
        context.sliders.put("Intensity",-1.0);call(tick,module,context);require(settings(setting,first).intensity()==0,"negative intensity not clamped");
        context.sliders.put("Intensity",.7);
        Method palette=VisualAtmosphere.class.getDeclaredMethod("configure",boolean.class,String.class,boolean.class);palette.setAccessible(true);
        for(VisualAtmosphere.Preset preset:VisualAtmosphere.Preset.values()){
            palette.invoke(null,true,preset.sky(),false);call(render,module,context);
            require(settings(setting,first).color()==VisualAtmosphere.sample(preset,0,0),"active palette did not reach terrain: "+preset);
            require(context.color==0xAA123456&&context.sliders.get("Intensity")==.7,"palette rewrote saved settings");
        }
        palette.invoke(null,true,"Abyss",true);call(render,module,context);require(settings(setting,first).color()==0x123456,"Advanced failed to restore saved tint");
        palette.invoke(null,false,"Abyss",false);call(render,module,context);require(settings(setting,first).color()==0x123456,"inactive palette failed to restore saved tint");
        resets=ProbeReset.calls;graphTicks=ProbeReachability.ticks;context.client.world=second;call(render,module,context);require(ProbeReachability.ticks==graphTicks&&mask(options,second)==null,"world switch retained prior reachability or queried on render");Object renderToken=token(options,second);resets=ProbeReset.calls;call(tick,module,context);Object secondToken=token(options,second);require(settings(setting,first)==null&&secondToken!=firstToken&&secondToken==renderToken&&ProbeReset.calls==resets&&mask(options,second).world()==second,"implicit world change retains stale token/mask");
        call(changed,module,context);require(settings(setting,second)==null&&ProbeReachability.mask==null,"world-change hook retains settings/reachable mask");call(tick,module,context);require(token(options,second)!=secondToken,"world-change hook reuses token");
        resets=ProbeReset.calls;context.client.player=null;call(tick,module,context);require(settings(setting,second)==null&&ProbeReset.calls==resets+1,"missing player retains settings/resources");call(tick,module,context);require(ProbeReset.calls==resets+1,"empty context thrashes resources");
        context.client.player=new Object();call(tick,module,context);context.client.world=null;resets=ProbeReset.calls;call(render,module,context);require(settings(setting,second)==null&&ProbeReset.calls==resets+1,"disconnect retains settings/resources");
        context.client.world=first;for(int i=0;i<30;i++){context.module.enabled=true;call(enable,module,context);require(settings(setting,first)!=null,"repeat enable failed");resets=ProbeReset.calls;context.module.enabled=false;call(disable,module,context);require(settings(setting,first)==null&&ProbeReset.calls==resets+1&&ProbeReachability.mask==null,"disable failed to invalidate settings/mask");graphTicks=ProbeReachability.ticks;call(tick,module,context);call(render,module,context);require(settings(setting,first)==null&&ProbeReachability.ticks==graphTicks,"disabled callbacks reactivated or queried terrain");}
    }
    static Field field(Class<?> c,String name)throws Exception{Field f=c.getDeclaredField(name);f.setAccessible(true);return f;}
    static void renderer(Path classes)throws Exception{
        Class<?> r=new FixtureLoader(classes,true).loadClass(RENDERER.replace('/','.'));Method init=r.getMethod("initialize"),reset=r.getMethod("reset"),release=r.getMethod("releaseResources");
        Field generation=field(r,"generation"),depth=field(r,"depth"),view=field(r,"depthView"),reachableTexture=field(r,"reachableTexture"),reachableView=field(r,"reachableView"),uploaded=field(r,"uploadedMask");
        call(init,null);call(init,null);require(ProbeSubscriptions.names.size()==5,"initialize should register exactly five lifecycle listeners once");
        require(ProbeRenderEvents.END_EXTRACTION.listeners.size()==1&&ProbeRenderEvents.BEFORE_ENTITIES.listeners.size()==1,"wrong/repeated render phase registration");
        var active=GroundShadersMath.settings(.35,0x2EAFD6);Object token=new Object();long gen=generation.getLong(null);
        require(GroundShadersMath.currentFrame(gen,gen,token,token,active),"current frame rejected");
        ProbeWorld world=new ProbeWorld();ProbeClient.INSTANCE.world=world;ProbeActiveFeature.world=world;ProbeMask capturedMask=new ProbeMask(world,1);ProbeActiveFeature.active=new ProbeOptions(token,active,capturedMask);
        Class<?> frameClass=r.getClassLoader().loadClass(RENDERER.replace('/','.')+"$Frame");Constructor<?> frameConstructor=frameClass.getDeclaredConstructor(Object.class,long.class,GroundShadersMath.Settings.class,ProbeMatrix.class,ProbeMask.class,ProbeVector.class,float.class);frameConstructor.setAccessible(true);
        Object frame=frameConstructor.newInstance(token,gen,active,new ProbeMatrix(),capturedMask,new ProbeVector(),0f);Method current=r.getDeclaredMethod("isCurrent",frameClass);current.setAccessible(true);
        require((boolean)call(current,null,frame),"actual current frame rejected");ProbeActiveFeature.active=new ProbeOptions(token,active,new ProbeMask(world,2));require(!(boolean)call(current,null,frame),"actual frame accepts superseded positive mask");
        ProbeActiveFeature.active=new ProbeOptions(token,active,null);require(!(boolean)call(current,null,frame),"actual frame accepts invalidated reachable mask");ProbeActiveFeature.active=new ProbeOptions(token,active,capturedMask);
        ProbeClient.INSTANCE.player=null;require(!(boolean)call(current,null,frame),"actual frame accepts missing player");ProbeClient.INSTANCE.player=new Object();ProbeClient.INSTANCE.world=new ProbeWorld();require(!(boolean)call(current,null,frame),"actual frame accepts other world");ProbeClient.INSTANCE.world=world;
        ProbeTexture texture=new ProbeTexture(),maskTexture=new ProbeTexture();ProbeView textureView=new ProbeView(),maskView=new ProbeView();depth.set(null,texture);view.set(null,textureView);reachableTexture.set(null,maskTexture);reachableView.set(null,maskView);uploaded.set(null,new ProbeMask(new ProbeWorld(),1));ProbeRenderSystem.renderThread=false;call(reset,null);
        require(!(boolean)call(current,null,frame),"actual captured frame survives generation reset");require(generation.getLong(null)==gen+1&&!GroundShadersMath.currentFrame(gen,generation.getLong(null),token,token,active),"reset leaves captured generation valid");require(texture.closes==0&&textureView.closes==0&&maskTexture.closes==0&&maskView.closes==0&&ProbeClient.INSTANCE.pending.size()==1,"off-thread reset closes GPU immediately or loses dispatch");
        ProbeRenderSystem.renderThread=true;ProbeClient.INSTANCE.pending.remove(0).run();require(texture.closes==1&&textureView.closes==1&&depth.get(null)==null&&view.get(null)==null&&maskTexture.closes==1&&maskView.closes==1&&reachableTexture.get(null)==null&&reachableView.get(null)==null&&uploaded.get(null)==null,"queued cleanup failed or retained uploaded mask");call(release,null);require(texture.closes==1&&textureView.closes==1&&maskTexture.closes==1&&maskView.closes==1,"cleanup not idempotent");
        for(int failure=0;failure<2;failure++){
            texture=new ProbeTexture();textureView=new ProbeView();maskTexture=new ProbeTexture();maskView=new ProbeView();textureView.fail=failure==0;maskView.fail=failure==1;
            depth.set(null,texture);view.set(null,textureView);reachableTexture.set(null,maskTexture);reachableView.set(null,maskView);uploaded.set(null,new ProbeMask(new ProbeWorld(),2));
            boolean failed=false;try{call(release,null);}catch(InvocationTargetException expected){failed=expected.getCause() instanceof IllegalStateException;}
            require(failed&&texture.closes==1&&textureView.closes==1&&maskTexture.closes==1&&maskView.closes==1&&depth.get(null)==null&&view.get(null)==null&&reachableTexture.get(null)==null&&reachableView.get(null)==null&&uploaded.get(null)==null,"view-close failure leaks depth/mask or retained handles");
        }
        gen=generation.getLong(null);ProbeConnections.DISCONNECT.listeners.get(0).onPlayDisconnect(new ProbeHandler(),ProbeClient.INSTANCE);require(generation.getLong(null)==gen+1,"disconnect listener does not reset generation");gen=generation.getLong(null);ProbeResources.INSTANCE.listeners.get(0).reload(new ProbeManager());require(generation.getLong(null)==gen+1,"reload listener does not reset generation");gen=generation.getLong(null);ProbeLifecycle.CLIENT_STOPPING.listeners.get(0).onClientStopping(ProbeClient.INSTANCE);require(generation.getLong(null)==gen+1,"shutdown listener does not reset generation");
        require(!GroundShadersMath.currentFrame(gen,gen,token,new Object(),active)&&!GroundShadersMath.currentFrame(gen,gen,null,null,active)&&!GroundShadersMath.currentFrame(gen,gen,token,token,null),"world/inactive current-frame guard");require(!GroundShadersMath.currentFrame(gen,gen,token,token,GroundShadersMath.settings(0,0)),"zero intensity frame admitted");
    }
    public static void main(String[] args)throws Exception{
        Path classes=Path.of(args[0]);feature(classes);renderer(classes);
        System.out.println("PASS GroundShadersLifecycle: "+checks+" actual feature/lifecycle assertions; settings, disabled callbacks, world tokens, generation, repeat init, disconnect/reload/shutdown, render-thread dispatch and exception-safe cleanup. GPU rendering is a separate fixture.");
    }
}
