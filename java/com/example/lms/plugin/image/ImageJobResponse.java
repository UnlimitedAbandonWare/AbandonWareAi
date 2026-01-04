package com.example.lms.plugin.image;

import com.example.lms.plugin.image.jobs.ImageJob;
import com.example.lms.plugin.image.jobs.ImageJobService;
import com.fasterxml.jackson.annotation.JsonInclude;



/**
 * API response payload for asynchronous image generation jobs.  When a job
 * is pending or in progress the {@code etaSeconds} and
 * {@code expectedReadyAt} fields provide an estimate of when the result
 * will be ready.  Once a job has succeeded or failed these fields are
 * omitted.  The {@code publicUrl} is populated only upon success.  The
 * {@code reason} field contains a machine friendly error code when the
 * job fails to start or complete.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ImageJobResponse(
        String id,
        String status,
        String publicUrl,
        Long etaSeconds,
        String expectedReadyAt,
        String reason
) {
    /**
     * Construct an API response from an {@link ImageJob} and an ETA.  The
     * ETA is applied only when the job has not yet completed.  The
     * reason is propagated from the job entity.
     */
    public static ImageJobResponse of(ImageJob job, ImageJobService.Eta eta) {
        Long etaSecs = null;
        String ready = null;
        // Only include ETA when the job is not finished
        if (eta != null && (job.getStatus() == ImageJob.Status.PENDING || job.getStatus() == ImageJob.Status.IN_PROGRESS)) {
            etaSecs = eta.etaSeconds();
            ready = eta.expectedReadyAt();
        }
        return new ImageJobResponse(
                job.getId(),
                job.getStatus().name(),
                job.getPublicUrl(),
                etaSecs,
                ready,
                job.getReason()
        );
    }

    /**
     * Construct an error response with the given reason.  All other
     * fields are left null or empty as appropriate.
     */
    public static ImageJobResponse error(String reason) {
        return new ImageJobResponse(null, null, null, null, null, reason);
    }
}