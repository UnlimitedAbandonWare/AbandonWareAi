package com.abandonware.ai.example.lms.cfvm;

import java.util.List;



/** A condensed "super token" that summarizes a cluster of RawSlots. */
public record CfvmRawTile(String key, List<RawSlot> members, double weight) { }