package com.example.lms.assist;

/** Internal adapter: never exposed as a client-supplied history/model request. */
public interface NovaFocusAnswer {
    String answer(Long chatSessionId,String question,NovaFocusHistoryService.Context context);
    default String answer(Long room,String question,NovaFocusHistoryService.Context context,java.util.function.BooleanSupplier current){
        if(!current.getAsBoolean())throw new java.util.concurrent.CancellationException("focus_closed");
        return answer(room,question,context);
    }
    default void cancel(Long chatSessionId) {}
    default String answer(Long room,String question,NovaFocusHistoryService.Context context,FocusMemoryScope scope,java.util.function.BooleanSupplier current){
        return answer(room,question,context,current);
    }
    /** 이미지를 지원하지 않는 구현체가 이미지를 조용히 버리지 않도록 명시적으로 실패시킨다. */
    default String answer(Long room,String question,String imageBase64,String imageMediaType,NovaFocusHistoryService.Context context,FocusMemoryScope scope,java.util.function.BooleanSupplier current){
        if(imageBase64!=null&&!imageBase64.isBlank())throw new IllegalStateException("focus_image_unsupported");
        return answer(room,question,context,scope,current);
    }
}
