package strategy.plan;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
/** Lightweight YAML-like loader placeholder. Replace with SnakeYAML in integration. */
public class PlanDslLoader {
  private static String active = "safe_autorun.v1";
  public static String activePlanId() { return active; }
  public static void setActive(String id) { active = id; }
}