package net.minecraft.client.option;
public final class GameOptions {public final KeyBinding attackKey=new KeyBinding(),useKey=new KeyBinding(),dropKey=new KeyBinding(),swapHandsKey=new KeyBinding();public final KeyBinding[] hotbarKeys=new KeyBinding[9];public GameOptions(){for(int i=0;i<9;i++)hotbarKeys[i]=new KeyBinding();}}
