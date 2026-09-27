package net.minecraft.util.math;
public enum Direction {UP(0,1,0),DOWN(0,-1,0),EAST(1,0,0),WEST(-1,0,0),NORTH(0,0,-1),SOUTH(0,0,1);
 public final int x,y,z;Direction(int x,int y,int z){this.x=x;this.y=y;this.z=z;}
 public int getOffsetX(){return x;}public int getOffsetY(){return y;}public int getOffsetZ(){return z;}
 public Direction getOpposite(){return switch(this){case UP->DOWN;case DOWN->UP;case NORTH->SOUTH;case SOUTH->NORTH;case WEST->EAST;case EAST->WEST;};}
}
