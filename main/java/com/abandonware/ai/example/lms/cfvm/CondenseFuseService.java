package com.abandonware.ai.example.lms.cfvm;

import java.util.ArrayList;
import java.util.List;



/** Condense & Fuse: build RawTiles from a matrix buffer using simple grouping. */
public class CondenseFuseService {

    public List<CfvmRawTile> buildTiles(RawMatrixBuffer buf, double[] weights) {
        var seg = buf.segments9();
        List<CfvmRawTile> out = new ArrayList<>();
        for (int i=0;i<seg.size();i++) {
            var members = seg.get(i);
            if (!members.isEmpty()) {
                out.add(new CfvmRawTile("seg-"+i, members, i < weights.length ? weights[i] : 0.0));
            }
        }
        return out;
    }
}