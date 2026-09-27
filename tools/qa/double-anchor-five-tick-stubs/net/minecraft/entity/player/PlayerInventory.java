package net.minecraft.entity.player;
import java.util.Arrays;import net.minecraft.item.ItemStack;
public final class PlayerInventory {public final ItemStack[] stacks=new ItemStack[41];public int selected;
 public PlayerInventory(){Arrays.fill(stacks,ItemStack.EMPTY);}public ItemStack getStack(int slot){return stacks[slot];}
 public ItemStack getSelectedStack(){int projected=dev.c4lam.celeste.silent.SilentSlotManager.getInstance().localProjectionSlot(this);return getStack(projected<0?selected:projected);}
 public int getSelectedSlot(){return selected;}public void setSelectedSlot(int slot){selected=slot;}
}
