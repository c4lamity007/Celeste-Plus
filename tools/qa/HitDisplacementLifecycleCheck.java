import dev.c4lam.celeste.module.impl.visual.expansion.HitDisplacementWorld;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;
import org.objectweb.asm.*;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;

/** Executes actual renderer subscription/pulse/reset/resource lifecycle bytecode; only external boundaries are substituted. GPU draw is checked separately. */
public final class HitDisplacementLifecycleCheck {
    static final String TARGET="dev/c4lam/celeste/module/impl/visual/expansion/HitDisplacementRenderer";
    static final String ANIMATION="dev/c4lam/celeste/module/impl/visual/expansion/HitDisplacementWorld";
    static final HitDisplacementWorld.Anchor ANCHOR=new HitDisplacementWorld.Anchor(4,new HitDisplacementWorld.Point(0,1,.3),new HitDisplacementWorld.Point(0,1,.3));
    static int checks;
    static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
    public static final class World { }
    public static final class Player {boolean alive=true,spectator;public boolean isAlive(){return alive;}public boolean isSpectator(){return spectator;}}
    public static final class Screen { }
    public static final class Client {static final Client INSTANCE=new Client();public World world=new World();public Player player=new Player();public Screen currentScreen;boolean paused;final List<Runnable> pending=new ArrayList<>();
        public static Client getInstance(){return INSTANCE;}public boolean isPaused(){return paused;}public void execute(Runnable r){pending.add(r);}}
    public static final class Compatibility {static boolean shaders,shadow;public static boolean isShaderPackActive(){return shaders;}public static boolean isShadowPass(){return shadow;}}
    public static final class Auth {static boolean authorized=true;public static boolean authorized(){return authorized;}}
    public static final class RenderSystem {static boolean renderThread=true;public static boolean isOnRenderThread(){return renderThread;}public static void assertOnRenderThread(){check(renderThread,"GPU close off render thread");}}
    public static class Texture {int closes;public boolean isClosed(){return closes>0;}public void close(){closes++;}}
    public static final class View {int closes;boolean fail;public boolean isClosed(){return closes>0;}public void close(){closes++;if(fail)throw new IllegalStateException("fixture view close failure");}}
    public record Id(String namespace,String path){public static Id of(String a,String b){return new Id(a,b);}}
    public static final class Event<T> {public static final Id DEFAULT_PHASE=Id.of("fabric","default");final List<T> listeners=new ArrayList<>();final List<String> phases=new ArrayList<>();public void register(T l){listeners.add(l);}public void register(Id phase,T l){listeners.add(l);}public void addPhaseOrdering(Id before,Id after){phases.add(before.path+">"+after.path);}}
    public interface Context { }
    public static final class RenderEvents {public interface EndMain{void endMain(Context context);}public static final Event<EndMain> END_MAIN=new Event<>();}
    public static final class Lifecycle {public interface ClientStopping{void onClientStopping(Client c);}public static final Event<ClientStopping> CLIENT_STOPPING=new Event<>();}
    public static final class Handler { }
    public static final class Connections {public interface Disconnect{void onPlayDisconnect(Handler h,Client c);}public static final Event<Disconnect> DISCONNECT=new Event<>();}
    public static final class Manager { }
    public interface Reloader{void reload(Manager m);}
    public enum ResourceType{CLIENT_RESOURCES}
    public interface Resources {ResourcesImpl INSTANCE=new ResourcesImpl();static Resources get(ResourceType t){return INSTANCE;}void registerReloader(Id id,Reloader r);}
    public static final class ResourcesImpl implements Resources{final List<Reloader> listeners=new ArrayList<>();public void registerReloader(Id id,Reloader r){listeners.add(r);}}
    public static final class Subscriptions{static final Set<String> names=new HashSet<>();public static <T>void register(Object o,String n,Class<T> t,T listener,Consumer<? super T> registrar){check(names.add(n),"duplicate event "+n);registrar.accept(listener);}}
    static class Loader extends ClassLoader{
        final Path classes;Loader(Path classes){super(HitDisplacementLifecycleCheck.class.getClassLoader());this.classes=classes;}
        @Override protected Class<?>findClass(String name)throws ClassNotFoundException{if(!name.equals(TARGET.replace('/','.')))throw new ClassNotFoundException(name);
            try{Map<String,String> map=new HashMap<>();String prefix="HitDisplacementLifecycleCheck$";
                String[] sources={"net/minecraft/client/MinecraftClient","net/minecraft/client/world/ClientWorld","net/minecraft/client/network/ClientPlayerEntity","net/minecraft/client/gui/screen/Screen","dev/c4lam/celeste/compat/RenderCompatibility","dev/c4lam/celeste/auth/AuthBootstrap","com/mojang/blaze3d/systems/RenderSystem","com/mojang/blaze3d/textures/GpuTexture","com/mojang/blaze3d/textures/GpuTextureView","net/minecraft/util/Identifier","net/fabricmc/fabric/api/event/Event","net/fabricmc/fabric/api/client/rendering/v1/world/WorldRenderContext","net/fabricmc/fabric/api/client/rendering/v1/world/WorldRenderEvents","net/fabricmc/fabric/api/client/rendering/v1/world/WorldRenderEvents$EndMain","net/fabricmc/fabric/api/client/event/lifecycle/v1/ClientLifecycleEvents","net/fabricmc/fabric/api/client/event/lifecycle/v1/ClientLifecycleEvents$ClientStopping","net/minecraft/client/network/ClientPlayNetworkHandler","net/fabricmc/fabric/api/client/networking/v1/ClientPlayConnectionEvents","net/fabricmc/fabric/api/client/networking/v1/ClientPlayConnectionEvents$Disconnect","net/minecraft/resource/ResourceManager","net/minecraft/resource/ResourceType","net/minecraft/resource/SynchronousResourceReloader","net/minecraft/resource/ResourceReloader","net/fabricmc/fabric/api/resource/v1/ResourceLoader","dev/c4lam/celeste/beta/lifecycle/BetaSubscriptions"};
                String[] targets={"Client","World","Player","Screen","Compatibility","Auth","RenderSystem","Texture","View","Id","Event","Context","RenderEvents","RenderEvents$EndMain","Lifecycle","Lifecycle$ClientStopping","Handler","Connections","Connections$Disconnect","Manager","ResourceType","Reloader","Reloader","Resources","Subscriptions"};
                for(int i=0;i<sources.length;i++)map.put(sources[i],prefix+targets[i]);
                ClassWriter writer=new ClassWriter(0);ClassVisitor remap=new ClassRemapper(writer,new Remapper(){public String map(String s){return map.getOrDefault(s,s);}});
                ClassVisitor select=new ClassVisitor(Opcodes.ASM9,remap){
                    @Override public FieldVisitor visitField(int access,String n,String d,String s,Object v){return Set.of("ANIMATION","PHASE","scene","sceneView","world","player","initialized").contains(n)?super.visitField(access,n,d,s,v):null;}
                    @Override public MethodVisitor visitMethod(int access,String n,String d,String s,String[] e){
                        if(n.equals("<clinit>")){MethodVisitor mv=super.visitMethod(access,n,d,s,e);mv.visitCode();mv.visitTypeInsn(Opcodes.NEW,ANIMATION);mv.visitInsn(Opcodes.DUP);mv.visitMethodInsn(Opcodes.INVOKESPECIAL,ANIMATION,"<init>","()V",false);mv.visitFieldInsn(Opcodes.PUTSTATIC,TARGET,"ANIMATION","L"+ANIMATION+";");mv.visitLdcInsn("hit_displacement/after_world");mv.visitMethodInsn(Opcodes.INVOKESTATIC,TARGET,"id","(Ljava/lang/String;)Lnet/minecraft/util/Identifier;",false);mv.visitFieldInsn(Opcodes.PUTSTATIC,TARGET,"PHASE","Lnet/minecraft/util/Identifier;");mv.visitInsn(Opcodes.RETURN);mv.visitMaxs(2,0);mv.visitEnd();return null;}
                        if(n.equals("draw")){MethodVisitor mv=super.visitMethod(access,n,d,s,e);mv.visitCode();mv.visitInsn(Opcodes.RETURN);mv.visitMaxs(0,1);mv.visitEnd();return null;}
                        return Set.of("initialize","enqueue","allowed","reset","releaseResources","id").contains(n)||n.startsWith("lambda$initialize$")?super.visitMethod(access,n,d,s,e):null;
                    }
                };new ClassReader(Files.readAllBytes(classes.resolve(TARGET+".class"))).accept(select,0);byte[] bytes=writer.toByteArray();return defineClass(name,bytes,0,bytes.length);
            }catch(Exception e){throw new ClassNotFoundException(name,e);}}
    }
    static Field field(Class<?>c,String n)throws Exception{Field f=c.getDeclaredField(n);f.setAccessible(true);return f;}
    public static void main(String[]args)throws Exception{Class<?>r=new Loader(Path.of(args[0])).loadClass(TARGET.replace('/','.'));Method init=r.getMethod("initialize"),reset=r.getMethod("reset"),release=r.getMethod("releaseResources"),pulse=r.getDeclaredMethod("enqueue",Client.class,HitDisplacementWorld.Anchor.class,int.class,double.class);Method allowed=r.getDeclaredMethod("allowed",Client.class);allowed.setAccessible(true);pulse.setAccessible(true);
        init.invoke(null);init.invoke(null);check(Subscriptions.names.size()==4&&RenderEvents.END_MAIN.listeners.size()==1,"initialize duplicates/misses listeners");check(RenderEvents.END_MAIN.phases.contains("world_overlays/after_fog>hit_displacement/after_world")&&RenderEvents.END_MAIN.phases.contains("hit_displacement/after_world>sky_lyrics/after_fog"),"wrong fog/overlay/label phase order");
        Client c=Client.INSTANCE;HitDisplacementWorld a=(HitDisplacementWorld)field(r,"ANIMATION").get(null);pulse.invoke(null,c,ANCHOR,0x22CCFF,1d);check(a.size()==1,"eligible pulse missing");Object oldWorld=field(r,"world").get(null);c.world=new World();pulse.invoke(null,c,ANCHOR,0x22CCFF,1d);check(a.size()==1&&field(r,"world").get(null)!=oldWorld,"new world retains old pulses");
        c.player=new Player();pulse.invoke(null,c,ANCHOR,0x22CCFF,1d);check(a.size()==1,"new player retains old pulses");
        for(int guard=0;guard<8;guard++){reset.invoke(null);switch(guard){case 0->c.currentScreen=new Screen();case 1->c.paused=true;case 2->Compatibility.shaders=true;case 3->Compatibility.shadow=true;case 4->Auth.authorized=false;case 5->c.player.spectator=true;case 6->c.player.alive=false;case 7->c.world=null;}
            check(!(boolean)allowed.invoke(null,c),"ineligible guard "+guard);pulse.invoke(null,c,ANCHOR,0,1d);check(a.size()==0,"ineligible pulse accepted "+guard);c.currentScreen=null;c.paused=false;Compatibility.shaders=false;Compatibility.shadow=false;Auth.authorized=true;c.player.spectator=false;c.player.alive=true;if(c.world==null)c.world=new World();}
        check(!(boolean)allowed.invoke(null,new Object[]{null}),"null client eligible");Player saved=c.player;c.player=null;check(!(boolean)allowed.invoke(null,c),"missing player eligible");c.player=saved;
        // A first pulse establishes its world and intentionally clears any stale
        // targets. Seed simulated draw resources AFTER that transition, as the
        // real renderer does, so these checks actually exercise later reset.
        Field scene=field(r,"scene"),view=field(r,"sceneView");for(int i=0;i<20;i++){pulse.invoke(null,c,ANCHOR,0,1d);Texture t=new Texture();View v=new View();scene.set(null,t);view.set(null,v);check(t.closes==0&&v.closes==0,"fixture resources already retired");reset.invoke(null);reset.invoke(null);check(t.closes==1&&v.closes==1&&a.size()==0&&scene.get(null)==null&&view.get(null)==null,"reset leaks or double-closes");}
        pulse.invoke(null,c,ANCHOR,0,1d);Texture t=new Texture();View v=new View();scene.set(null,t);view.set(null,v);check(t.closes==0&&v.closes==0&&c.pending.isEmpty(),"off-thread fixture must start with live targets and no queued jobs");RenderSystem.renderThread=false;reset.invoke(null);check(a.size()==0&&field(r,"world").get(null)==null&&t.closes==0&&v.closes==0&&c.pending.size()==1,"off-thread reset touches GPU or keeps pulses");RenderSystem.renderThread=true;c.pending.removeFirst().run();check(t.closes==1&&v.closes==1,"deferred close missing");
        t=new Texture();v=new View();v.fail=true;scene.set(null,t);view.set(null,v);try{release.invoke(null);throw new AssertionError("fixture close should fail");}catch(InvocationTargetException e){check(e.getCause()instanceof IllegalStateException,"wrong close failure");}check(t.closes==1&&scene.get(null)==null&&view.get(null)==null,"view-close failure leaks texture");
        for(int callback=0;callback<3;callback++){pulse.invoke(null,c,ANCHOR,0,1d);check(a.size()>0,"pulse missing before lifecycle callback");switch(callback){case 0->Lifecycle.CLIENT_STOPPING.listeners.getFirst().onClientStopping(c);case 1->Connections.DISCONNECT.listeners.getFirst().onPlayDisconnect(new Handler(),c);case 2->Resources.INSTANCE.listeners.getFirst().reload(new Manager());}check(a.size()==0&&field(r,"world").get(null)==null,"lifecycle callback leaves state");}
        System.out.println("PASS actual HitDisplacementRenderer lifecycle: "+checks+" checks; subscriptions, phase order, accepted hit guards, world/player reset, render-thread disposal, exception cleanup and unload callbacks. GPU draw is tested separately.");
    }
}
