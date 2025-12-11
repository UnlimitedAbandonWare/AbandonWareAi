package com.abandonware.ai.vision.mpc;

import java.util.*;
import org.springframework.stereotype.Component;
@Component
/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.abandonware.ai.vision.mpc.MirrorPerfectCube
 * Role: config
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.abandonware.ai.vision.mpc.MirrorPerfectCube
role: config
*/
public class MirrorPerfectCube {
  public VoxelGrid normalize(VoxelGrid in){
    if (in == null || in.points()==null || in.points().isEmpty()) return VoxelGrid.empty();
    var pts = in.points();
    double minX=+1e18, minY=+1e18, minZ=+1e18, maxX=-1e18, maxY=-1e18, maxZ=-1e18;
    for (var p: pts){
      if(Double.isFinite(p.x())){minX=Math.min(minX,p.x()); maxX=Math.max(maxX,p.x());}
      if(Double.isFinite(p.y())){minY=Math.min(minY,p.y()); maxY=Math.max(maxY,p.y());}
      if(Double.isFinite(p.z())){minZ=Math.min(minZ,p.z()); maxZ=Math.max(maxZ,p.z());}
    }
    double sx = Math.max(1e-9, maxX-minX), sy=Math.max(1e-9, maxY-minY), sz=Math.max(1e-9, maxZ-minZ);
    double s = Math.max(sx, Math.max(sy, sz));
    double cx = (minX+maxX)/2.0, cy=(minY+maxY)/2.0, cz=(minZ+maxZ)/2.0;
    List<Point3D> out = new ArrayList<>(pts.size());
    for (var p: pts){
      double nx = (p.x()-cx)/s, ny=(p.y()-cy)/s, nz=(p.z()-cz)/s;
      out.add(new Point3D(nx, ny, nz, p.weight()));
    }
    return new VoxelGrid(out);
  }
}