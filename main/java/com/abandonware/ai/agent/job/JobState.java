package com.abandonware.ai.agent.job;


/**
 * Enumeration of all possible job states.  Jobs transition from PENDING to
 * RUNNING when they are dequeued and executed.  Upon completion they move
 * to either SUCCEEDED or FAILED; failed jobs may optionally be sent to a
 * dead letter queue (DLQ).
 */
public enum JobState {
    PENDING,
    RUNNING,
    SUCCEEDED,
    FAILED,
    DLQ
}