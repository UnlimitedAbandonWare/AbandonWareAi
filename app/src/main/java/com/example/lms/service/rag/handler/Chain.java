package com.example.lms.service.rag.handler;

public interface Chain {
  Context next(Context ctx);
}