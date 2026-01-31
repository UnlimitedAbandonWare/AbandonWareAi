
package com.abandonware.ai.agent.integrations;

import java.io.*;
import java.nio.file.*;
import java.util.*;



/**
 * Trivial ANN index interface and meta holder.
 */
public interface AnnIndex {
    List<AnnHit> search(float[] query, int k, int efOrNprobe) throws IOException;

    record AnnHit(String docId, double score) {}
}