package com.example.lms.plugin.image;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;



/**
 * Request payload for the image generation plugin.  This DTO captures
 * the minimum information required to generate an image: the textual
 * prompt.  Optional parameters such as the number of images to
 * generate or the desired size may be added in future revisions if
 * needed.  Validation annotations ensure that a blank prompt is
 * rejected early by the controller.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ImageGenerationPluginRequest {

    /**
     * The natural language description of the image to generate.  This
     * field must not be blank; otherwise the API will return a 400
     * response.
     */
    @NotBlank
    private String prompt;

    /**
     * Optional desired size for the generated image in the format
     * "WIDTHxHEIGHT" (e.g. 256x256, 512x512, 1024x1024).  Defaults
     * to 1024x1024 when unspecified.  Validation ensures only the
     * supported sizes are accepted.
     */
    @Pattern(regexp = "^(256x256|512x512|1024x1024)$")
    private String size = "1024x1024";

    /**
     * Optional count specifying how many images to generate.  The API
     * supports generating between 1 and 4 images in a single request.
     * Defaults to 1 when unspecified.  Validation ensures the count
     * remains within the supported range.
     */
    @Min(1)
    @Max(4)
    private int count = 1;

    public String getPrompt() {
        return prompt;
    }

    public void setPrompt(String prompt) {
        this.prompt = prompt;
    }

    public String getSize() {
        return size;
    }

    public void setSize(String size) {
        this.size = size;
    }

    public int getCount() {
        return count;
    }

    public void setCount(int count) {
        this.count = count;
    }
}