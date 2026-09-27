package com.example.lms.infra.selection;

public interface SelectionEntropy {

    SelectionEntropyMode mode();

    String algorithmVersion();

    double unitInterval(SelectionCoordinate coordinate);

    int boundedIndex(SelectionCoordinate coordinate, int bound);
}
