
package com.abandonware.ai.agent.integrations;

import java.io.*;
import java.nio.file.*;
import java.util.*;



/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.abandonware.ai.agent.integrations.ColbertIndex
 * Role: config
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.abandonware.ai.agent.integrations.ColbertIndex
role: config
*/
public class ColbertIndex {
    public final Path dir;
    public ColbertIndex(Path dir) { this.dir = dir; }
}