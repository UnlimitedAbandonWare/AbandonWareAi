package com.example.lms.api;

import com.example.lms.service.PkiValidationStorageService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class PkiValidationHttpFileServingTest {

    private static final String FILE_NAME = "D7FFB1D4F21CDD6AF7B0BF54E4969AB5.txt";
    private static final byte[] CHALLENGE_BYTES = String.join("\n",
            "9B6998245E4F1420B0440BA7363D1645F0EC29FE4091DC095D0A04644572D7CD",
            "comodoca.com",
            "f59d8feb0ff71bb").getBytes(StandardCharsets.US_ASCII);

    @TempDir
    Path tempDir;

    @Test
    void servesTheExactZeroSslChallengeBytesFromThePublicValidationUrl() throws Exception {
        Files.write(tempDir.resolve(FILE_NAME), CHALLENGE_BYTES);
        MockMvc mvc = mockMvc(tempDir);

        mvc.perform(get("/.well-known/pki-validation/{fileName}", FILE_NAME))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.TEXT_PLAIN))
                .andExpect(content().bytes(CHALLENGE_BYTES));
    }

    @Test
    void returnsNotFoundWhenTheNamedChallengeFileDoesNotExist() throws Exception {
        MockMvc mvc = mockMvc(tempDir);

        mvc.perform(get("/.well-known/pki-validation/{fileName}", FILE_NAME))
                .andExpect(status().isNotFound());
    }

    @Test
    void rejectsAFileOutsideTheHexadecimalChallengeNameContract() throws Exception {
        Files.write(tempDir.resolve("not-a-validation-token.txt"), CHALLENGE_BYTES);
        MockMvc mvc = mockMvc(tempDir);

        mvc.perform(get("/.well-known/pki-validation/{fileName}", "not-a-validation-token.txt"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsAnOversizedChallengeFileInsteadOfPublishingIt() throws Exception {
        Files.write(tempDir.resolve(FILE_NAME), new byte[1_000_001]);
        MockMvc mvc = mockMvc(tempDir);

        mvc.perform(get("/.well-known/pki-validation/{fileName}", FILE_NAME))
                .andExpect(status().isBadRequest());
    }

    private MockMvc mockMvc(Path root) {
        PkiValidationStorageService storage = new PkiValidationStorageService(root.toString());
        return standaloneSetup(new PkiValidationController(storage)).build();
    }
}
