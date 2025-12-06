
package com.abandonware.ai.agent.integrations;

import java.io.*;
import java.nio.file.*;
import java.util.*;



/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.abandonware.ai.agent.integrations.AnnIndex
 * Role: config
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.abandonware.ai.agent.integrations.AnnIndex
role: config
*/
public interface AnnIndex {
    List<AnnHit> search(float[] query, int k, int efOrNprobe) throws IOException;

    record AnnHit(String docId, double score) {}
}