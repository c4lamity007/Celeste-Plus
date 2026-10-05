package dev.c4lam.celeste.module.impl.combat;

import dev.c4lam.celeste.action.ActionScheduler;
import dev.c4lam.celeste.action.UseInputLease;
import dev.c4lam.celeste.action.VanillaInputGuard;
import dev.c4lam.celeste.gui.model.GuiCatalog;
import dev.c4lam.celeste.gui.model.GuiModule;
import dev.c4lam.celeste.module.ModuleContext;
import dev.c4lam.celeste.rotation.MoveFixMode;
import dev.c4lam.celeste.rotation.Rotation;
import dev.c4lam.celeste.rotation.RotationManager;
import dev.c4lam.celeste.rotation.RotationTask;
import dev.c4lam.celeste.silent.SilentSlotManager;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerInteractionManager.Event;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import java.util.List;

/** Drives the real coordinator/scheduler; aim math and placement search have separate tests. */
public final class IndependentSafeAnchorCheck {
    private static int checks;

    public static void main(String[] args) {
        for(boolean silent:new boolean[]{true,false}) for(double chance:new double[]{0,100}) flow(silent,chance);
        visibilityFailure();frameOnly();interruptions();queuedCancellation();materials();failures();
        deadline();duplicateCallbacks();moduleArbitration();environment();existingAnchors();resourceStacks();ownership();chargedStartProtection();firePlacement();placementEntities();
        System.out.println("PASS Safe Anchor: "+checks+" actual coordinator/scheduler behavior checks");
    }

    private static void flow(boolean silent,double chance) {
        for(boolean silentSwap:new boolean[]{false,true}) {
            Fixture f=new Fixture(silent,chance,silentSwap);f.tick();
            check(RotationManager.getInstance().moveFix==(silent?MoveFixMode.STRICT:MoveFixMode.OFF),"correct default movefix for silent="+silent);
            f.ticks(9);actions(f,"place","charge","cover","explode");
            check(f.events().stream().map(Event::slot).toList().equals(List.of(0,1,1,2)),"anchor/glow/glow/totem item flow");
            long first=f.events().getFirst().tick();
            check(f.events().stream().map(event->event.tick()-first).toList().equals(chance==0?List.of(0L,1L,2L,3L):List.of(0L,2L,3L,5L)),"four actions use expected tick gaps at chance="+chance);
            check(f.events().get(2).hit().equals(SafeAnchorPlacement.SUPPORT),"third click targets cover support");
            check(f.events().get(3).hit().equals(f.anchor),"final click targets anchor");
            check(f.client.world.getBlockState(SafeAnchorPlacement.SHIELD).isOf(Blocks.GLOWSTONE),"cover is placed before detonation");
            check(f.client.player.inventory.selected==(silentSwap?0:2),"selected slot follows Silent swap setting");
            check(!RotationManager.getInstance().isOwnedBy(f.coordinator)&&!SilentSlotManager.getInstance().isOwnedBy(f.coordinator),"end tick releases completed aim and slot");
            check(silent?f.client.player.pitch==0:f.client.player.pitch!=0,"visible rotations only in visible variant");
            f.ticks(5);check(f.events().size()==4,"held Use does not restart non-loop flow");
            f.client.useHeld=false;f.tick();check(!UseInputLease.getInstance().owns(f.coordinator),"Use release ends input lease");
        }
    }

    private static void visibilityFailure() {
        for(boolean silent:new boolean[]{true,false}) {
            Fixture f=new Fixture(silent,0,true);f.until(3);
            f.client.world.hiddenAnchor=true;SafeAnchorPlacement.hidden=true;f.tick();
            check(f.events().size()==3,"anchor hidden after cover is never detonated");
            check(!RotationManager.getInstance().isOwnedBy(f.coordinator),"hidden anchor immediately releases aim");
            f.client.world.hiddenAnchor=false;SafeAnchorPlacement.hidden=false;f.ticks(4);
            check(f.events().size()==3,"visibility recovery does not replay abandoned explosion");
        }
        Fixture f=new Fixture(true,0,true);f.until(3);f.client.world.hiddenAnchor=true;SafeAnchorPlacement.hidden=true;
        f.coordinator.onRenderFrame(f.context);
        check(!RotationManager.getInstance().isOwnedBy(f.coordinator),"occlusion found during a render frame releases aim immediately");
        f.tick();check(f.events().size()==3,"frame-detected occlusion prevents next tick explosion");
    }

    private static void frameOnly() {
        Fixture f=new Fixture(true,0,true);f.until(2);int before=f.events().size();int frames=SafeAnchorAim.frames;
        for(int i=0;i<20;i++)f.coordinator.onRenderFrame(f.context);
        check(SafeAnchorAim.frames>frames,"frames update aim during inter-tick cover turn");
        check(f.events().size()==before,"frames never perform primary actions");
        f.tick();check(f.events().size()==3,"next tick performs one cover action");
        for(int i=0;i<20;i++)f.coordinator.onRenderFrame(f.context);
        check(f.events().size()==3,"frames never perform final explosion");
    }

    private static void interruptions() {
        for(String change:List.of("use","screen","overlay","focus","death","disable","slot","sneak","offhand","using-item","mounted","attack","breaking","world")) {
            Fixture f=new Fixture(true,0,true);f.until(2);change(f,change);f.tick();
            check(f.events().size()==2,change+" interrupts before shield");
            check(!RotationManager.getInstance().isOwnedBy(f.coordinator),change+" releases look");
            check(!SilentSlotManager.getInstance().isOwnedBy(f.coordinator),change+" releases silent slot");
            if(change.equals("slot"))check(f.client.player.inventory.selected==4,"manual selection is preserved");
        }
    }

    private static void change(Fixture f,String change) {
        switch(change) {
            case "use"->f.client.useHeld=false;
            case "screen"->f.client.currentScreen=new Object();
            case "overlay"->f.client.overlay=new Object();
            case "focus"->f.client.focused=false;
            case "death"->f.client.player.alive=false;
            case "disable"->{f.module.enabled=false;f.coordinator.onDisable(f.module.name(),f.client);}
            case "slot"->f.client.player.inventory.selected=4;
            case "sneak"->f.client.player.crouching=true;
            case "offhand"->f.client.player.offhand=new ItemStack(Items.GLOWSTONE);
            case "using-item"->f.client.player.usingItem=true;
            case "mounted"->f.client.player.mounted=true;
            case "attack"->f.client.options.attackKey.pressed=true;
            case "breaking"->f.client.interactionManager.breaking=true;
            case "world"->{f.coordinator.onWorldChanged(f.client);f.module.enabled=false;}
            default->throw new AssertionError(change);
        }
    }

    private static void queuedCancellation() {
        for(String change:List.of("use","screen","focus","death","disable","slot","sneak","offhand","attack")) {
            Fixture f=new Fixture(true,0,true);f.until(2);f.plan();change(f,change);f.flush();
            check(f.events().size()==2,change+" rechecked at queued action time");
        }
        Fixture f=new Fixture(true,0,true);f.until(2);f.plan();
        Object foreign=new Object();RotationManager.getInstance().submit(RotationTask.builder(foreign,new Rotation(30,20)).moveFix(MoveFixMode.OFF).build());
        f.coordinator.onRenderFrame(f.context);f.flush();
        check(f.events().size()==2,"foreign rotation owner prevents cover click");
        check(!SilentSlotManager.getInstance().isOwnedBy(f.coordinator),"rotation preemption releases slot");
    }

    private static void materials() {
        for(int slot:new int[]{0,1,2}) {
            Fixture f=new Fixture(true,0,true);f.client.player.inventory.stacks[slot]=ItemStack.EMPTY;f.ticks(5);
            check(f.events().isEmpty(),"missing starting item "+slot+" prevents placement");
        }
        Fixture f=new Fixture(true,0,true);f.client.player.inventory.stacks[1]=new ItemStack(Items.GLOWSTONE,1);f.ticks(4);
        check(f.events().isEmpty(),"only one glowstone cannot begin four-stage flow");
        for(int step:new int[]{1,2,3}) {
            f=new Fixture(true,0,true);f.until(step);f.plan();f.client.player.inventory.stacks[step==3?2:1]=ItemStack.EMPTY;f.flush();
            check(f.events().size()==step,"queued stage "+step+" rechecks item");
        }
    }

    private static void failures() {
        for(int failed:new int[]{1,2,3,4}) {
            Fixture f=new Fixture(true,0,true);f.client.interactionManager.failAttempts.add(failed);f.ticks(9);
            check(f.events().size()==failed,"failed interaction "+failed+" stops later actions");
            check(!RotationManager.getInstance().isOwnedBy(f.coordinator),"failed interaction releases look");
        }
        Fixture f=new Fixture(true,0,true);SafeAnchorPlacement.noPlan=true;f.ticks(6);
        check(f.events().size()==2,"no cover placement prevents shield/explosion");
        f=new Fixture(true,0,true);f.until(2);SafeAnchorPlacement.invalid=true;f.ticks(4);
        check(f.events().size()==2,"invalidated cover cannot be placed");
        f=new Fixture(true,0,true);f.until(3);f.client.world.blocks.put(SafeAnchorPlacement.SHIELD,Blocks.AIR.getDefaultState());f.ticks(4);
        check(f.events().size()==3,"removed cover cancels detonation");
    }

    private static void deadline() {
        Fixture f=new Fixture(true,0,true);f.until(2);f.plan();VanillaInputGuard.conflict=true;f.flush();VanillaInputGuard.conflict=false;f.ticks(6);
        check(f.events().size()==2,"missed cover tick is never replayed late");
        check(!RotationManager.getInstance().isOwnedBy(f.coordinator),"missed deadline releases aim");
    }

    private static void duplicateCallbacks() {
        Fixture f=new Fixture(true,0,true);
        for(int tick=0;tick<8;tick++) {
            f.plan();int before=f.events().size();
            for(int duplicate=0;duplicate<4;duplicate++){f.coordinator.onStartTick(f.context);f.coordinator.onActionTick(f.context);ActionScheduler.getInstance().flush(f.client);}
            f.coordinator.onEndTick(f.context);
            RotationManager.getInstance().endTick();
            check(f.events().size()-before<=1,"duplicate callbacks cannot duplicate tick action");
        }
        actions(f,"place","charge","cover","explode");
    }

    private static void moduleArbitration() {
        Fixture f=new Fixture(false,0,true);GuiModule other=new GuiModule("Silent Safe Anchor",true);other.sliders.put("2 tick swap chance",0d);GuiCatalog.modules.add(other);
        f.tick();check(RotationManager.getInstance().moveFix==MoveFixMode.STRICT,"both enabled consistently prefer silent variant");
        f.ticks(7);actions(f,"place","charge","cover","explode");
        f=new Fixture(false,0,true);f.tick();other=new GuiModule("Silent Safe Anchor",true);GuiCatalog.modules.add(other);f.tick();
        check(RotationManager.getInstance().moveFix==MoveFixMode.OFF,"enabling silent midway does not replace active visible cycle");
    }

    private static void environment() {
        for(String invalid:List.of("dimension","destination-dimension","unloaded","border","collision","build-limit","reach","entity","sneak","offhand")) {
            Fixture f=new Fixture(true,0,true);
            switch(invalid){
                case "dimension"->f.client.world.anchorWorks=true;
                case "destination-dimension"->f.client.world.anchorWorksAt.put(f.anchor,true);
                case "unloaded"->f.client.world.loaded=false;
                case "border"->f.client.world.insideBorder=false;
                case "collision"->f.client.world.collisionFree=false;
                case "build-limit"->f.client.world.buildLimit=false;
                case "reach"->f.client.player.reach=2;
                case "entity"->{var entity=new net.minecraft.entity.player.PlayerEntity();entity.position=new Vec3d(.5,1,2.5);f.client.world.otherEntities.add(entity);}
                default->change(f,invalid);
            }
            f.ticks(5);check(f.events().isEmpty(),invalid+" prevents unsafe start");
        }
    }

    private static void existingAnchors() {
        Fixture f=new Fixture(true,0,true);f.module.toggles.put("Auto swap anchor",false);f.nativeUse();f.tick();
        check(f.events().isEmpty(),"Auto swap anchor off leaves initial placement to physical Use");
        check(!UseInputLease.getInstance().owns(f.coordinator)&&f.client.options.useKey.pressed&&f.client.options.useKey.queued==3,"manual first placement retains native held/queued input");
        f.client.world.blocks.put(f.anchor,Blocks.RESPAWN_ANCHOR.getDefaultState());
        f.client.crosshairTarget=SafeAnchorPlacement.visibleAnchor(f.client,f.anchor,new java.util.Random(0));
        f.ticks(5);actions(f,"charge","cover","explode");
        check(f.events().get(2).tick()-f.events().get(0).tick()==2,"manual placement finishes remaining three clicks on consecutive ticks");
        for(int selected:new int[]{1,2,4}) {
            f=new Fixture(true,0,true);f.module.toggles.put("Auto swap anchor",false);f.client.player.inventory.selected=selected;
            f.nativeUse();f.tick();
            check(f.events().isEmpty()&&!UseInputLease.getInstance().owns(f.coordinator),"auto swap off does not force placement from selected slot "+selected);
            check(f.client.options.useKey.pressed&&f.client.options.useKey.queued==3,"auto swap off preserves unrelated held/queued Use from slot "+selected);
        }
        for(int charges:new int[]{0,1,4}) {
            f=new Fixture(true,0,true);f.client.world.blocks.put(f.anchor,Blocks.RESPAWN_ANCHOR.getDefaultState().with(net.minecraft.block.RespawnAnchorBlock.CHARGES,charges));
            if(charges==4)f.client.player.inventory.selected=1;
            f.client.crosshairTarget=SafeAnchorPlacement.visibleAnchor(f.client,f.anchor,new java.util.Random(0));f.ticks(3);
            check(f.events().isEmpty(),"Only own anchors rejects unowned charge="+charges);
            check(!UseInputLease.getInstance().owns(f.coordinator),"unowned anchor retains original input ownership behavior");
            f.module.toggles.put("Only own anchors",false);f.ticks(6);
            if(charges==0)actions(f,"charge","cover","explode");else actions(f,"cover","explode");
        }
        f=new Fixture(true,0,true);f.until(3);f.client.world.hiddenAnchor=true;SafeAnchorPlacement.hidden=true;f.tick();
        f.client.useHeld=false;f.tick();f.client.useHeld=true;f.client.world.hiddenAnchor=false;SafeAnchorPlacement.hidden=false;
        f.module.toggles.put("Only own anchors",false);f.client.crosshairTarget=SafeAnchorPlacement.visibleAnchor(f.client,f.anchor,new java.util.Random(0));f.ticks(5);
        check(f.events().size()==3,"releasing Use never revives abandoned anchor even with Only own anchors off");
    }

    private static void resourceStacks() {
        Fixture f=new Fixture(true,0,true);f.client.player.inventory.stacks[1]=new ItemStack(Items.GLOWSTONE,1);f.client.player.inventory.stacks[3]=new ItemStack(Items.GLOWSTONE,1);f.ticks(8);
        actions(f,"place","charge","cover","explode");
        check(f.events().get(2).slot()==3,"cover can use a second glowstone stack after first is consumed");
        f=new Fixture(true,100,true);f.client.player.inventory.stacks[1]=new ItemStack(Items.GLOWSTONE,1);f.client.player.inventory.stacks[3]=new ItemStack(Items.GLOWSTONE,1);f.ticks(10);
        actions(f,"place","charge","cover","explode");
        check(f.events().get(2).tick()-f.events().get(1).tick()==2,"changing glowstone stack also obeys swap delay chance");
        f=new Fixture(true,100,true);f.tick();check(SafeAnchorAim.lastDuration==2,"charge turn receives two ticks when its swap is delayed");
        f.until(2);check(SafeAnchorAim.lastDuration==1,"same-slot cover turn receives one tick");
        f.until(3);check(SafeAnchorAim.lastDuration==2,"totem turn receives two ticks when its swap is delayed");
    }

    private static void ownership() {
        Fixture f=new Fixture(true,0,true);f.until(2);Object foreign=new Object();SilentSlotManager.getInstance().acquire(foreign,4,500,2);f.tick();
        check(f.events().size()==2,"foreign hotbar ownership interrupts safely");
        check(SilentSlotManager.getInstance().isOwnedBy(foreign),"cancellation preserves foreign hotbar lease");
        f=new Fixture(false,0,true);f.until(2);float previous=f.client.player.pitch;RotationManager.getInstance().blocked=true;f.tick();
        check(f.events().size()==2,"failed rotation acquisition prevents action");
        check(f.client.player.pitch==previous,"failed rotation acquisition does not move visible camera");
        f=new Fixture(true,0,true);f.until(2);SilentSlotManager.getInstance().blocked=true;f.tick();
        check(f.events().size()==2,"failed silent slot renewal stops action");
    }

    private static void chargedStartProtection() {
        for(boolean own:new boolean[]{false,true}) for(String missing:List.of("no-plan","no-glowstone","no-totem")) {
            Fixture f=new Fixture(true,0,true);
            if(own) {
                f.module.toggles.put("Auto swap anchor",false);
                f.tick(); // The original flow observes the next native placement.
            } else f.module.toggles.put("Only own anchors",false);
            int before=f.events().size();
            f.client.world.blocks.put(f.anchor,Blocks.RESPAWN_ANCHOR.getDefaultState().with(net.minecraft.block.RespawnAnchorBlock.CHARGES,1));
            f.client.crosshairTarget=SafeAnchorPlacement.visibleAnchor(f.client,f.anchor,new java.util.Random(0));
            switch(missing) {
                case "no-plan"->SafeAnchorPlacement.noPlan=true;
                case "no-glowstone"->f.client.player.inventory.stacks[1]=ItemStack.EMPTY;
                case "no-totem"->f.client.player.inventory.stacks[2]=ItemStack.EMPTY;
                default->throw new AssertionError(missing);
            }
            f.nativeUse();f.tick();
            check(UseInputLease.getInstance().owns(f.coordinator),"eligible charged start claims Use when "+missing+", owned="+own);
            check(!f.client.options.useKey.pressed,"eligible charged start suppresses vanilla detonation when "+missing+", owned="+own);
            check(f.events().size()==before,"unsafe charged start sends no action when "+missing+", owned="+own);
            blockedUse(f,"unsafe charged start "+missing+", owned="+own);
            check(!RotationManager.getInstance().isOwnedBy(f.coordinator),"unsafe charged start keeps silent aim released when "+missing+", owned="+own);
            SafeAnchorPlacement.noPlan=false;
            f.client.player.inventory.stacks[1]=new ItemStack(Items.GLOWSTONE,8);
            f.client.player.inventory.stacks[2]=new ItemStack(Items.TOTEM_OF_UNDYING);
            f.nativeUse();f.tick();
            check(UseInputLease.getInstance().owns(f.coordinator)&&!f.client.options.useKey.pressed&&f.events().size()==before,"restoring resources does not revive abandoned charged start");
            blockedUse(f,"restored resources on abandoned charged start");
        }
        Fixture f=new Fixture(true,0,true);f.until(2);SafeAnchorPlacement.invalid=true;f.tick();
        f.client.useHeld=false;f.tick();SafeAnchorPlacement.invalid=false;f.client.useHeld=true;
        f.client.crosshairTarget=SafeAnchorPlacement.visibleAnchor(f.client,f.anchor,new java.util.Random(0));
        f.client.options.useKey.pressed=true;f.tick();
        check(UseInputLease.getInstance().owns(f.coordinator),"repressing Use on abandoned charged anchor remains protected");
        check(!f.client.options.useKey.pressed&&f.events().size()==2,"abandoned charged anchor never falls through to vanilla Use");
        check(!RotationManager.getInstance().isOwnedBy(f.coordinator),"protected abandoned anchor has no aim ownership");
        for(String unrelated:List.of("sword","no-target","disabled")) {
            f=new Fixture(true,0,true);
            switch(unrelated) {
                case "sword"->{f.client.player.inventory.stacks[4]=new ItemStack(Items.SWORD);f.client.player.inventory.selected=4;}
                case "no-target"->f.client.crosshairTarget=null;
                case "disabled"->f.module.enabled=false;
                default->throw new AssertionError(unrelated);
            }
            f.client.options.useKey.pressed=true;f.tick();
            check(!UseInputLease.getInstance().owns(f.coordinator)&&f.client.options.useKey.pressed,"unrelated "+unrelated+" input still passes through");
            check(f.events().isEmpty()&&!RotationManager.getInstance().isOwnedBy(f.coordinator),"unrelated "+unrelated+" input has no automated action or aim");
        }
    }

    private static void blockedUse(Fixture f,String label) {
        check(UseInputLease.getInstance().owns(f.coordinator),label+" owns Use");
        check(!f.client.options.useKey.pressed&&f.client.options.useKey.queued==0,label+" blocks both held and queued native Use");
        check(UseInputLease.physicalDown(f.client),label+" still sees physical Use");
    }

    private static void firePlacement() {
        for(boolean silent:new boolean[]{false,true}) for(var fire:List.of(Blocks.FIRE,Blocks.SOUL_FIRE)) for(boolean directTarget:new boolean[]{false,true}) {
            Fixture f=new Fixture(silent,0,true);
            f.client.world.blocks.put(f.anchor,fire.getDefaultState());
            if(directTarget)f.client.crosshairTarget=new BlockHitResult(new Vec3d(.5,1.5,2),Direction.NORTH,f.anchor,false);
            else f.client.world.obstruction=(BlockHitResult)f.client.crosshairTarget; // Engine adapter supplies the selected support face; native fire shapes have separate geometry tests.
            f.nativeUse();
            f.until(1);
            f.client.world.obstruction=null;
            check(f.client.world.getBlockState(f.anchor).isOf(Blocks.RESPAWN_ANCHOR),"fire target is replaced at its own block position, silent="+silent);
            check(f.events().getFirst().hit().equals(directTarget?f.anchor:f.support),"fire replacement retains native target position and face");
            check(f.client.world.getBlockState(f.anchor.offset(Direction.NORTH)).isAir(),"fire-target placement never shifts into neighboring block");
            blockedUse(f,"scheduled fire replacement");
            f.ticks(5);actions(f,"place","charge","cover","explode");
        }
        for(boolean silent:new boolean[]{false,true}) {
            Fixture f=new Fixture(silent,0,true);f.client.world.blocks.put(f.support,Blocks.OBSIDIAN.getDefaultState());
            f.until(1);
            check(f.client.world.getBlockState(f.anchor).isOf(Blocks.RESPAWN_ANCHOR),"ordinary obsidian support still places an anchor above it");
            check(f.events().getFirst().hit().equals(f.support),"ordinary support retains support-face interaction");
            f.ticks(5);actions(f,"place","charge","cover","explode");
        }
    }

    private static void placementEntities() {
        for(boolean silent:new boolean[]{false,true}) for(String occupant:List.of("dropped-item","dead-player","spectator-player")) {
            Fixture f=new Fixture(silent,0,true);
            net.minecraft.entity.Entity entity=switch(occupant) {
                case "dropped-item"->new net.minecraft.entity.ItemEntity();
                default->new net.minecraft.entity.player.PlayerEntity();
            };
            if(occupant.equals("dead-player"))entity.alive=false;
            if(occupant.equals("spectator-player"))((net.minecraft.entity.player.PlayerEntity)entity).spectator=true;
            entity.position=new Vec3d(.5,1,2.5);f.client.world.otherEntities.add(entity);
            f.until(1);
            check(f.client.world.getBlockState(f.anchor).isOf(Blocks.RESPAWN_ANCHOR),occupant+" does not block anchor placement, silent="+silent);
            entity.position=new Vec3d(.5,0,1.5);f.ticks(5);
            actions(f,"place","charge","cover","explode");
            check(f.client.world.getBlockState(SafeAnchorPlacement.SHIELD).isOf(Blocks.GLOWSTONE),occupant+" does not block shield placement");
        }
        for(boolean silent:new boolean[]{false,true}) for(boolean queued:new boolean[]{false,true}) for(boolean shield:new boolean[]{false,true}) {
            Fixture f=new Fixture(silent,0,true);
            if(shield)f.until(2);
            f.nativeUse();if(queued)f.plan();
            var item=new net.minecraft.entity.ItemEntity();
            item.position=shield?new Vec3d(.5,0,1.5):new Vec3d(.5,1,2.5);f.client.world.otherEntities.add(item);
            if(queued)f.flush();else f.tick();
            check(f.events().size()==(shield?3:1),"dropped item permits "+(shield?"shield":"anchor")+" placement, silent="+silent+", queued="+queued);
            check(f.client.world.getBlockState(shield?SafeAnchorPlacement.SHIELD:f.anchor).isOf(shield?Blocks.GLOWSTONE:Blocks.RESPAWN_ANCHOR),"item exemption keeps the original placement destination");
            blockedUse(f,"item-exempt placement");
            f.ticks(5);actions(f,"place","charge","cover","explode");
        }
        for(boolean silent:new boolean[]{false,true}) for(boolean queued:new boolean[]{false,true}) for(String occupant:List.of("player","generic-nonblocking")) {
            Fixture f=new Fixture(silent,0,true);f.nativeUse();
            if(queued)f.plan();
            net.minecraft.entity.Entity entity=occupant.equals("player")?new net.minecraft.entity.player.PlayerEntity():new net.minecraft.entity.Entity();
            entity.position=new Vec3d(.5,1,2.5);f.client.world.otherEntities.add(entity);
            if(queued)f.flush();else f.tick();
            check(f.events().isEmpty(),"restored occupancy blocks "+occupant+" at anchor destination, silent="+silent+", queued="+queued);
            check(!f.client.world.getBlockState(f.anchor).isOf(Blocks.RESPAWN_ANCHOR),"occupied destination remains unchanged");
            if(queued)blockedUse(f,"occupied queued anchor destination");
            else check(!UseInputLease.getInstance().owns(f.coordinator),"blocked initial destination preserves original native input ownership");
            check(!RotationManager.getInstance().isOwnedBy(f.coordinator),"occupancy cancels aim immediately");
            f.client.world.otherEntities.clear();f.tick();
            check(f.events().size()==(queued?0:1),queued?"entity leaving does not replay canceled queued attempt":"cleared initial obstruction follows original held-start behavior");
        }
        for(boolean silent:new boolean[]{false,true}) for(boolean queued:new boolean[]{false,true}) for(String occupant:List.of("player","generic-nonblocking")) {
            Fixture f=new Fixture(silent,0,true);f.until(2);f.nativeUse();
            if(queued)f.plan();
            net.minecraft.entity.Entity entity=occupant.equals("player")?new net.minecraft.entity.player.PlayerEntity():new net.minecraft.entity.Entity();
            entity.position=new Vec3d(.5,0,1.5);f.client.world.otherEntities.add(entity);
            if(queued)f.flush();else f.tick();
            check(f.events().size()==2,"restored occupancy blocks "+occupant+" at shield destination, silent="+silent+", queued="+queued);
            blockedUse(f,"occupied shield destination");
            check(!RotationManager.getInstance().isOwnedBy(f.coordinator),"shield occupancy cancels aim immediately");
            f.client.world.otherEntities.clear();f.tick();
            check(f.events().size()==2,"entity leaving shield cannot replay canceled action");
        }
    }

    private static void actions(Fixture f,String... expected) {check(f.events().stream().map(Event::action).toList().equals(List.of(expected)),"action sequence: "+f.events());}
    private static void check(boolean value,String label) {checks++;if(!value)throw new AssertionError(label);}

    private static final class Fixture {
        final MinecraftClient client=new MinecraftClient();
        final GuiModule module;
        final ModuleContext context;
        final SafeAnchorCoordinator coordinator;
        SafeAnchorCoordinator secondary; ModuleContext secondaryContext;
        final BlockPos support=new BlockPos(0,0,2),anchor=support.up();
        Fixture(boolean silent,double chance,boolean silentSwap) {
            module=new GuiModule(silent?"Silent Safe Anchor":"Safe Anchor",true);context=new ModuleContext(client,module);
            coordinator=new SafeAnchorCoordinator(module.name());
            GuiCatalog.modules.clear();GuiCatalog.modules.add(module);GuiCatalog.modules.add(new GuiModule("Anchor Macro",false));
            module.toggles.put("Silent swap",silentSwap);module.sliders.put("2 tick swap chance",chance);
            client.player.position=new Vec3d(.5,0,-.5);
            client.player.inventory.stacks[0]=new ItemStack(Items.RESPAWN_ANCHOR,8);
            client.player.inventory.stacks[1]=new ItemStack(Items.GLOWSTONE,8);
            client.player.inventory.stacks[2]=new ItemStack(Items.TOTEM_OF_UNDYING);
            client.world.blocks.put(support,Blocks.STONE.getDefaultState());
            client.world.blocks.put(SafeAnchorPlacement.SUPPORT,Blocks.STONE.getDefaultState());
            client.crosshairTarget=new BlockHitResult(new Vec3d(.5,1,2.5),Direction.UP,support,false);
            ActionScheduler.getInstance().clear();UseInputLease.getInstance().clear(client);SilentSlotManager.getInstance().reset();RotationManager.getInstance().reset();
            SafeAnchorPlacement.reset();SafeAnchorAim.reset();VanillaInputGuard.conflict=VanillaInputGuard.rotationConflict=false;
            coordinator.onWorldChanged(client);
        }
        List<Event> events(){return client.interactionManager.events;}
        void nativeUse(){client.options.useKey.pressed=true;client.options.useKey.queued=3;}
        void plan(){
            ActionScheduler.getInstance().beginTick(client);
            for (GuiModule entry : GuiCatalog.modules) if (entry != module && entry.enabled && entry.name().equals("Silent Safe Anchor") && secondary == null) {
                secondary = new SafeAnchorCoordinator(entry.name()); secondaryContext = new ModuleContext(client,entry);
            }
            coordinator.onStartTick(context); if(secondary != null) secondary.onStartTick(secondaryContext);
            coordinator.onActionTick(context); if(secondary != null) secondary.onActionTick(secondaryContext);
        }
        void flush(){ActionScheduler.getInstance().flush(client);coordinator.onEndTick(context);if(secondary != null)secondary.onEndTick(secondaryContext);RotationManager.getInstance().endTick();}
        void tick(){plan();flush();}
        void ticks(int count){for(int i=0;i<count;i++)tick();}
        void until(int count){for(int i=0;i<15&&events().size()<count;i++)tick();check(events().size()==count,"reach "+count+" interactions: "+events());}
    }
}
