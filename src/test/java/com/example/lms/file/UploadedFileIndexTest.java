package com.example.lms.file;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UploadedFileIndexTest {

    @Test
    void appendDoesNotPrefixFirstEntryWithNewline() {
        UploadedFileIndex index = new UploadedFileIndex();

        index.append("sid-1", "first");
        index.append("sid-1", "second");

        assertThat(index.get("sid-1")).isEqualTo("first\nsecond");
    }
}
