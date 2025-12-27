package com.abandonware.ai.vision.mpc;

import java.util.*;
public class VoxelGrid {
  private final List<Point3D> pts;
  public VoxelGrid(List<Point3D> pts){ this.pts = pts; }
  public static VoxelGrid empty(){ return new VoxelGrid(new ArrayList<>()); }
  public List<Point3D> points(){ return pts; }
}