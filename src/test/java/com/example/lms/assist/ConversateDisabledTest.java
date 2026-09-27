package com.example.lms.assist;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.web.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.assertj.core.api.Assertions.assertThat;

class ConversateDisabledTest {
    @Test void featureOffCreatesNoAssistBeansAndKeepsExistingBodyGuardRouting() throws Exception {
        new ApplicationContextRunner().withUserConfiguration(ConversateSessionService.class,ConversateController.class,ConversatePreparedMaterials.class,ConversateAsrBridge.class,ConversateLocalCardGenerator.class)
            .withPropertyValues("conversate.enabled=false","conversate.generation.enabled=true").run(c->{assertThat(c).hasNotFailed();assertThat(c).doesNotHaveBean(ConversateSessionService.class);assertThat(c).doesNotHaveBean(ConversateController.class);assertThat(c).doesNotHaveBean(PreparedMaterialReader.class);assertThat(c).doesNotHaveBean(ConversateLocalCardGenerator.class);});
        var request=new MockHttpServletRequest("POST","/api/assist/sessions");request.setContent(new byte[16385]);
        var called=new AtomicBoolean();new com.example.lms.api.PublicRequestBudgetGuard().doFilter(request,new MockHttpServletResponse(),(q,r)->called.set(true));
        assertThat(called).isTrue();
    }
}
