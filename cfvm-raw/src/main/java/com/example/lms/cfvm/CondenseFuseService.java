package com.example.lms.cfvm;

import java.util.ArrayList;
import java.util.List;



/**
 * [GPT-PRO-AGENT v2] — concise navigation header (no runtime effect).
 * Module: com.example.lms.cfvm.CondenseFuseService
 * Role: config
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.example.lms.cfvm.CondenseFuseService
role: config
*/
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