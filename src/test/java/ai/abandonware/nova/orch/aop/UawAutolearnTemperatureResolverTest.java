package ai.abandonware.nova.orch.aop;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertNull;

class UawAutolearnTemperatureResolverTest {

    @Test
    void strictAndIdleTemperatureResolversDropNonFiniteOverrides() throws Exception {
        assertNull(resolve(UawAutolearnStrictRequestAspect.class, "1.0e309"));
        assertNull(resolve(UawIdleAutoTrainingPipelineAspect.class, "1.0e309"));
    }

    private static Object resolve(Class<?> type, String raw) throws Exception {
        Method method = type.getDeclaredMethod("resolveTemperature", String.class);
        method.setAccessible(true);
        return method.invoke(null, raw);
    }
}
