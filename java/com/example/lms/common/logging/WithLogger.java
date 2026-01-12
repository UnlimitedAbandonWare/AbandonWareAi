package com.example.lms.common.logging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;



/**
 * Lombok  * log() 메서드를 통해 Logger를 제공하는 헬퍼.
 *
 * 사용법:
 *   public class MyService implements WithLogger {
 *       public void run() { log().info("hello"); }
 *   }
 */
public interface WithLogger {
    default Logger log() {
        return LoggerFactory.getLogger(getClass());
    }
}