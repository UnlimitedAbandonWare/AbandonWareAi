package com.abandonware.ai.vision.mpc;

import java.util.*;
/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.abandonware.ai.vision.mpc.VoxelGrid
 * Role: config
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.abandonware.ai.vision.mpc.VoxelGrid
role: config
*/
public class VoxelGrid {
  private final List<Point3D> pts;
  public VoxelGrid(List<Point3D> pts){ this.pts = pts; }
  public static VoxelGrid empty(){ return new VoxelGrid(new ArrayList<>()); }
  public List<Point3D> points(){ return pts; }
}