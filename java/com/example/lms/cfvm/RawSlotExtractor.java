
package com.example.lms.cfvm;


public class RawSlotExtractor {
    public long patternId(String text) {
        return SimHash64.hash(text);
    }
}