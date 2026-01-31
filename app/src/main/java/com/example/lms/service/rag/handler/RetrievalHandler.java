package com.example.lms.service.rag.handler;

public interface RetrievalHandler {
  Context handle(Context ctx, Chain chain);
}