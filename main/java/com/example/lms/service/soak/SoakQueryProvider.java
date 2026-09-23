package com.example.lms.service.soak;

import java.util.List;

public interface SoakQueryProvider {
    List<String> queries(String topic);
}