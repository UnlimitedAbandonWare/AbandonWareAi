package com.example.lms.api;

import com.example.lms.jobs.JdbcJobService;
import com.example.lms.service.ChatService;
import com.example.lms.service.ChatResult;
import com.example.lms.dto.ChatRequestDto;
import com.example.lms.integrations.n8n.N8nNotifier;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import java.nio.file.Path;
import java.time.Clock;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TasksApiControllerDurableTest {
    @TempDir Path directory;
    @AfterEach void clearIdentity(){SecurityContextHolder.clearContext();}
    @Test void sameSemanticKeyReturnsOwnedCompletedReferenceAndConflictNeverCallsModelAgain() {
        var ds=new DriverManagerDataSource("jdbc:h2:file:"+directory.resolve("idem")+";MODE=MySQL;DB_CLOSE_ON_EXIT=FALSE","sa","");
        new ResourceDatabasePopulator(new FileSystemResource("main/resources/db/migration/V20260912__durable_jobs.sql"),new FileSystemResource("main/resources/db/migration/V20260912_03__job_idempotency.sql")).execute(ds);
        var chat=mock(ChatService.class);when(chat.continueChat(any(ChatRequestDto.class))).thenReturn(ChatResult.of("fixture","fixture",false));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("operator-a",null,java.util.List.of()));
        try(var jobs=new JdbcJobService(ds,new ObjectMapper(),Clock.systemUTC())){
            var c=new TasksApiController(chat,jobs,mock(N8nNotifier.class));c.registerPersistedWork();
            var costs=mock(ChatGenerationAdmissionFilter.class);var check=mock(Runnable.class);when(costs.costCheckCurrentRequest()).thenReturn(check);
            org.springframework.test.util.ReflectionTestUtils.setField(c,"costs",costs);
            var request=new TasksApiController.TaskAskRequest("질문\r\n둘",null,false,false,null,null,null);
            var first=c.askAsync(request,"key");assertEquals(202,first.getStatusCode().value());String id=first.getBody().get("taskId");
            var equivalent=new TasksApiController.TaskAskRequest("질문\n둘",java.util.List.of("ignored legacy history"),false,false,null,null,null);
            assertEquals(id,c.askAsync(equivalent,"key").getBody().get("taskId"));jobs.runPendingOnce();
            var replay=c.askAsync(equivalent,"key");assertEquals(200,replay.getStatusCode().value());assertEquals(id,replay.getBody().get("taskId"));assertEquals("SUCCEEDED",replay.getBody().get("state"));
            verify(check,times(1)).run();
            doThrow(new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,"chat_admission_unavailable")).when(check).run();
            assertEquals(200,c.askAsync(equivalent,"key").getStatusCode().value());
            assertEquals(200,c.task(id).getStatusCode().value());assertEquals(200,c.result(id).getStatusCode().value());
            assertEquals(503,assertThrows(org.springframework.web.server.ResponseStatusException.class,()->c.askAsync(equivalent,"new-key")).getStatusCode().value());
            doNothing().when(check).run();
            var conflict=c.askAsync(new TasksApiController.TaskAskRequest("different",null,false,false,null,null,null),"key");assertEquals(409,conflict.getStatusCode().value());
            assertEquals(400,c.askAsync(request,"invalid key").getStatusCode().value());verify(chat,times(1)).continueChat(any(ChatRequestDto.class));
            SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("operator-b",null,java.util.List.of()));assertEquals(404,c.result(id).getStatusCode().value());assertNotEquals(id,c.askAsync(request,"key").getBody().get("taskId"));
        }
    }
    @Test void acceptedTaskIsPolledAfterRestartAndOnlyVisibleToSubmittingPrincipal() {
        var ds = new DriverManagerDataSource("jdbc:h2:file:"+directory.resolve("api")+";MODE=MySQL;DB_CLOSE_ON_EXIT=FALSE", "sa", "");
        new ResourceDatabasePopulator(new FileSystemResource("main/resources/db/migration/V20260912__durable_jobs.sql")).execute(ds);
        var chat = mock(ChatService.class); var notifier=mock(N8nNotifier.class);
        when(chat.continueChat(any(ChatRequestDto.class))).thenReturn(ChatResult.of("saved fixture", "fixture", false));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("operator-a", null));
        String id;
        try(var first=new JdbcJobService(ds,new ObjectMapper(),Clock.systemUTC())) {
            var controller=new TasksApiController(chat,first,notifier);controller.registerPersistedWork();
            var accepted=controller.askAsync(new TasksApiController.TaskAskRequest("fixture",null,false,false,null,null,null));
            assertEquals(202,accepted.getStatusCode().value());id=accepted.getBody().get("taskId");
            assertEquals("/v1/tasks/"+id,accepted.getHeaders().getLocation().toString());
            verifyNoInteractions(chat);
        }
        try(var second=new JdbcJobService(ds,new ObjectMapper(),Clock.systemUTC())) {
            var controller=new TasksApiController(chat,second,notifier);controller.registerPersistedWork();second.runPendingOnce();
            assertEquals(200,controller.task(id).getStatusCode().value());
            assertEquals(200,controller.result(id).getStatusCode().value());
            assertTrue(controller.result(id).getBody().toString().contains("saved fixture"));
            SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("operator-b",null));
            assertEquals(404,controller.task(id).getStatusCode().value());
            assertEquals(404,controller.result(id).getStatusCode().value());
            verify(chat,times(1)).continueChat(any(ChatRequestDto.class));
        }
    }
}
