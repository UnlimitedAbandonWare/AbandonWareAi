package com.example.lms.assist;

/** Internal adapter: never exposed as a client-supplied history/model request. */
public interface NovaFocusAnswer {
    String answer(Long chatSessionId,String question,NovaFocusHistoryService.Context context);
    default String answer(Long room,String question,NovaFocusHistoryService.Context context,java.util.function.BooleanSupplier current){
        if(!current.getAsBoolean())throw new java.util.concurrent.CancellationException("focus_closed");
        return answer(room,question,context);
    }
    default void cancel(Long chatSessionId) {}
}
