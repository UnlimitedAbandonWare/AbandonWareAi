
package com.abandonware.ai.agent.integrations;

import java.io.*;
import java.nio.file.*;
import java.util.*;



/**
 * shim on-disk ColBERT index meta (not used by default).
 */
public class ColbertIndex {
    public final Path dir;
    public ColbertIndex(Path dir) { this.dir = dir; }
}