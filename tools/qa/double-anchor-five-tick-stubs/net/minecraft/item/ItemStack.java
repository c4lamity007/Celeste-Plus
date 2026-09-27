package net.minecraft.item;
public final class ItemStack {
 public static final ItemStack EMPTY=new ItemStack(Items.AIR,0);private final Item item;public int count;
 public ItemStack(Item item){this(item,1);}public ItemStack(Item item,int count){this.item=item;this.count=count;}
 public boolean isOf(Item other){return count>0&&item==other;}public boolean isEmpty(){return count<=0;}public boolean isIn(Object tag){return item==Items.SWORD;}
 public Item getItem(){return item;}public int getCount(){return count;}public void decrement(int n){count-=n;}public ItemStack copy(){return new ItemStack(item,count);}
}
