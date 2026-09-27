package com.example.lms.web;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LegacyLmsFeatureFlagContractTest {

    @Test
    void applicationYmlKeepsSingleFeatureBlockAndRemovesLegacyLmsFlags() throws Exception {
        String yml = Files.readString(Path.of("main/resources/application.yml"));
        Map<?, ?> root = loadMap(Path.of("main/resources/application.yml"));

        assertEquals(1, occurrences(yml, "\nfeature:"));
        assertFalse(yml.contains("legacy-lms"));
        assertFalse(root.containsKey("lms"));
    }

    @Test
    void activeSystemPromptUsesDynamicRagBranding() throws Exception {
        String properties = Files.readString(Path.of("main/resources/application.properties"));

        assertTrue(properties.contains("gpt.system.prompt="));
        assertFalse(properties.contains("LMS-Bot"));
        assertTrue(properties.contains("Dynamic RAG Orchestration Platform"));
    }

    @Test
    void readmeIntroducesDynamicRagPlatformBeforeHistoricalPackNotes() throws Exception {
        String readme = Files.readString(Path.of("README.md"));

        assertTrue(readme.startsWith("# Dynamic RAG Orchestration Platform"));
        assertTrue(readme.contains("The older pack notes below are retained as historical design context."));
        assertFalse(readme.startsWith("# Current Checkout Note"));
    }

    @Test
    void boardMvcSurfaceStaysAbsorbedIntoCorpusProjection() throws Exception {
        try (Stream<Path> paths = Files.walk(Path.of("main/java"))) {
            assertFalse(paths
                    .filter(Files::isRegularFile)
                    .anyMatch(path -> path.getFileName().toString().equals("BoardController.java")));
        }
    }

    @Test
    void legacyLmsJpaClusterStaysRemovedFromChatbotRuntime() throws Exception {
        for (Path path : List.of(
                Path.of("main/java/com/example/lms/web/StudentController.java"),
                Path.of("main/java/com/example/lms/web/ProfessorController.java"),
                Path.of("main/java/com/example/lms/web/CourseController.java"),
                Path.of("main/java/com/example/lms/web/AttendanceController.java"),
                Path.of("main/java/com/example/lms/web/AssignmentController.java"),
                Path.of("main/java/com/example/lms/web/EnrollmentController.java"),
                Path.of("main/java/com/example/lms/web/ExamController.java"),
                Path.of("main/java/com/example/lms/web/QuestionController.java"),
                Path.of("main/java/com/example/lms/api/ChannelWebhookController.java"),
                Path.of("main/java/com/example/lms/web/UploadController.java"),
                Path.of("main/java/com/example/lms/domain/Assignment.java"),
                Path.of("main/java/com/example/lms/domain/Attendance.java"),
                Path.of("main/java/com/example/lms/domain/Choice.java"),
                Path.of("main/java/com/example/lms/domain/Comment.java"),
                Path.of("main/java/com/example/lms/domain/Course.java"),
                Path.of("main/java/com/example/lms/domain/Enrollment.java"),
                Path.of("main/java/com/example/lms/domain/Exam.java"),
                Path.of("main/java/com/example/lms/domain/Grade.java"),
                Path.of("main/java/com/example/lms/domain/Notice.java"),
                Path.of("main/java/com/example/lms/domain/Professor.java"),
                Path.of("main/java/com/example/lms/domain/Question.java"),
                Path.of("main/java/com/example/lms/domain/Student.java"),
                Path.of("main/java/com/example/lms/domain/Submission.java"),
                Path.of("main/java/com/example/lms/repository/AssignmentRepository.java"),
                Path.of("main/java/com/example/lms/repository/AttendanceRepository.java"),
                Path.of("main/java/com/example/lms/repository/ChoiceRepository.java"),
                Path.of("main/java/com/example/lms/repository/CommentRepository.java"),
                Path.of("main/java/com/example/lms/repository/CourseRepository.java"),
                Path.of("main/java/com/example/lms/repository/EnrollmentRepository.java"),
                Path.of("main/java/com/example/lms/repository/ExamRepository.java"),
                Path.of("main/java/com/example/lms/repository/GradeRepository.java"),
                Path.of("main/java/com/example/lms/repository/NoticeRepository.java"),
                Path.of("main/java/com/example/lms/repository/ProfessorRepository.java"),
                Path.of("main/java/com/example/lms/repository/QuestionRepository.java"),
                Path.of("main/java/com/example/lms/repository/StudentRepository.java"),
                Path.of("main/java/com/example/lms/repository/SubmissionRepository.java"),
                Path.of("main/java/com/example/lms/service/AssignmentService.java"),
                Path.of("main/java/com/example/lms/service/AttendanceService.java"),
                Path.of("main/java/com/example/lms/service/CommentService.java"),
                Path.of("main/java/com/example/lms/service/CourseService.java"),
                Path.of("main/java/com/example/lms/service/EnrollmentService.java"),
                Path.of("main/java/com/example/lms/service/ExamService.java"),
                Path.of("main/java/com/example/lms/service/ProfessorService.java"),
                Path.of("main/java/com/example/lms/service/QuestionService.java"),
                Path.of("main/java/com/example/lms/service/StudentService.java"),
                Path.of("main/java/com/example/lms/service/SubmissionQueryService.java"),
                Path.of("main/java/com/example/lms/service/SubmissionQueryServiceImpl.java"),
                Path.of("main/java/com/example/lms/service/SubmissionService.java"),
                Path.of("main/java/com/example/lms/service/SubmissionServiceImpl.java"),
                Path.of("main/java/com/example/lms/learning/corpus/LegacyLmsCorpusIngestService.java"),
                Path.of("main/java/com/example/lms/learning/corpus/LmsCorpusProjector.java"),
                Path.of("main/java/com/example/lms/learning/chat/RolePromptProfileResolver.java"))) {
            assertFalse(Files.exists(path), path.toString());
        }
        for (Path path : List.of(
                Path.of("main/resources/templates/assignment"),
                Path.of("main/resources/templates/attendance"),
                Path.of("main/resources/templates/courses"),
                Path.of("main/resources/templates/enrollments"),
                Path.of("main/resources/templates/exam"),
                Path.of("main/resources/templates/professors"),
                Path.of("main/resources/templates/students"),
                Path.of("main/resources/templates/upload"))) {
            assertFalse(Files.exists(path), path.toString());
        }
    }

    @Test
    void rentalsMvcSurfaceStaysRemovedFromChatbotRuntime() {
        assertFalse(Files.exists(Path.of("main/java/com/example/lms/controller/RentalController.java")));
        assertFalse(Files.exists(Path.of("main/java/com/example/lms/domain/Rental.java")));
        assertFalse(Files.exists(Path.of("main/java/com/example/lms/repository/RentalRepository.java")));
        assertFalse(Files.exists(Path.of("main/resources/templates/rentals")));
    }

    @Test
    void genericListTemplatesStayRemovedFromChatbotRuntime() {
        assertFalse(Files.exists(Path.of("main/resources/templates/list")));
    }

    @Test
    void noticeCrudUiStaysRemovedFromChatbotRuntime() throws Exception {
        assertFalse(Files.exists(Path.of("main/java/com/example/lms/web/NoticeController.java")));
        assertFalse(Files.exists(Path.of("main/java/com/example/lms/service/NoticeService.java")));
        assertFalse(Files.exists(Path.of("main/java/com/example/lms/service/NoticeServiceImpl.java")));
        assertFalse(Files.exists(Path.of("main/resources/templates/admin/notices")));
    }

    @Test
    void legacyAuthRegistrationSurfaceStaysRemoved() throws Exception {
        assertFalse(Files.exists(Path.of("main/resources/templates/register.html")));
        assertFalse(Files.exists(Path.of("main/java/com/example/lms/web/RegistrationController.java")));
        assertFalse(Files.exists(Path.of("main/java/com/example/lms/web/AuthController.java")));
        assertFalse(Files.exists(Path.of("main/java/com/example/lms/web/LoginForm.java")));
        assertFalse(Files.exists(Path.of("main/java/com/example/lms/web/SignupForm.java")));
        assertFalse(Files.exists(Path.of("main/resources/templates/auth")));
        assertFalse(Files.readString(Path.of("main/java/com/example/lms/config/AppSecurityConfig.java"))
                .contains("\"/register\""));
        assertFalse(Files.readString(Path.of("main/java/com/example/lms/security/ChatOpenSecurityConfig.java"))
                .contains("AntPathRequestMatcher.antMatcher(\"/register\")"));
    }

    @Test
    void legacyNavigationNoLongerUsesControllerAdviceModelFlag() throws Exception {
        for (Path path : List.of(
                Path.of("main/resources/templates/dashboard.html"),
                Path.of("main/resources/templates/index.html"),
                Path.of("main/resources/templates/fragments/header.html"),
                Path.of("main/resources/templates/fragments/layout.html"),
                Path.of("main/resources/templates/admin/dashboard.html"))) {
            assertFalse(Files.readString(path).contains("legacyLmsEnabled"), path.toString());
        }
        assertFalse(Files.exists(Path.of("main/java/com/example/lms/web/LegacyLmsViewAdvice.java")));
    }

    @Test
    void activeChatUiUsesDynamicRagNavigationLabels() throws Exception {
        String chatUi = Files.readString(Path.of("main/resources/templates/chat-ui.html"));

        assertFalse(chatUi.contains("LMS 대시보드"));
        assertFalse(chatUi.contains("LMS로 가기"));
        assertTrue(chatUi.contains("RAG Ops"));
    }

    @Test
    void uawStrictModelKeyMatchesRuntimeProperty() throws Exception {
        Map<?, ?> root = loadMap(Path.of("main/resources/application.yml"));
        Map<?, ?> strict = map(map(map(root, "uaw"), "autolearn"), "strict");

        assertTrue(strict.containsKey("model"));
        assertFalse(strict.containsKey("forced-model"));
    }

    @Test
    void uawTrainingLoopDefaultsToLocalLightRouterWhenStrictModelIsBlank() throws Exception {
        String idleAspect = Files.readString(Path.of(
                "main/java/ai/abandonware/nova/orch/aop/UawIdleAutoTrainingPipelineAspect.java"));
        String strictAspect = Files.readString(Path.of(
                "main/java/ai/abandonware/nova/orch/aop/UawAutolearnStrictRequestAspect.java"));
        String service = Files.readString(Path.of(
                "main/java/com/example/lms/uaw/autolearn/UawAutolearnService.java"));

        for (String source : List.of(idleAspect, strictAspect, service)) {
            assertTrue(source.contains("llmrouter.light"));
            assertTrue(source.contains("autolearnStrictModel()"));
        }
        assertFalse(idleAspect.contains("final String forcedModel = trimToNull(env.getProperty(\"uaw.autolearn.strict.model\"));"));
        assertFalse(strictAspect.contains("final String forcedModel = trimToNull(env.getProperty(\"uaw.autolearn.strict.model\"));"));
        assertFalse(service.contains(".model(trimToNull(property(\"uaw.autolearn.strict.model\", null)))"));
    }

    @Test
    void legacyTuningSchedulerAndVectorShimStayRemoved() throws Exception {
        for (Path path : List.of(
                Path.of("main/java/com/example/lms/tuning/DynamicHyperparameterTuner.java"),
                Path.of("main/java/com/example/lms/tuning/StrategyWeightTuner.java"),
                Path.of("main/java/com/example/lms/llm/QueryTransform.java"))) {
            assertFalse(Files.exists(path), path.toString());
        }

        String properties = Files.readString(Path.of("main/resources/application.properties"));
        String exampleProperties = Files.readString(Path.of("main/resources/application.properties.example"));
        assertFalse(properties.contains("tuner."));
        assertFalse(properties.contains("tuning.strategy."));
        assertFalse(properties.contains("logging.level.com.example.lms.tuning"));
        assertFalse(exampleProperties.contains("logging.level.com.example.lms.tuning"));
    }

    @Test
    void legacyTransportAndExperimentPackagesStayRemoved() {
        for (Path path : List.of(
                Path.of("main/java/com/example/lms/audio"),
                Path.of("main/java/com/example/lms/netty"),
                Path.of("main/java/com/example/lms/trial"),
                Path.of("main/java/com/example/lms/compare"),
                Path.of("main/java/com/example/lms/alias"),
                Path.of("main/java/com/example/lms/gptapi"),
                Path.of("main/java/com/example/lms/learning/llm/compare"),
                Path.of("main/java/com/example/lms/learning/gemini/GeminiTuningService.java"))) {
            assertFalse(Files.exists(path), path.toString());
        }
    }

    @Test
    void legacyStudentTableAutofixAndDdlStayRemoved() throws Exception {
        assertFalse(Files.exists(Path.of("main/java/com/example/lms/boot/StudentChannelUserIdColumnAutoFix.java")));

        String properties = Files.readString(Path.of("main/resources/application.properties"));
        assertFalse(properties.contains("student-channel-user-id"));

        String ddl = Files.readString(Path.of("main/resources/db/ddl/V20260527__student_channel_user_id.sql"));
        assertFalse(ddl.contains("ALTER TABLE students"));
        assertFalse(ddl.contains("UPDATE students"));
        assertFalse(ddl.contains("CREATE UNIQUE INDEX uk_student_channel_user_id"));
        assertTrue(ddl.contains("DEPRECATED"));
    }

    private static int occurrences(String text, String needle) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }

    private static Map<?, ?> loadMap(Path path) throws Exception {
        try (InputStream in = Files.newInputStream(path)) {
            return new Yaml().load(in);
        }
    }

    private static Map<?, ?> map(Map<?, ?> source, String key) {
        return (Map<?, ?>) source.get(key);
    }
}
