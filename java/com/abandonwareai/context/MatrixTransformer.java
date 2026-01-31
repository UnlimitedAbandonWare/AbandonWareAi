package com.abandonwareai.context;

import org.springframework.stereotype.Component;

@Component("contextMatrixTransformer")public class MatrixTransformer {
    public String buildContext(java.util.List<String> slices){ return String.join("\n", slices); }

}