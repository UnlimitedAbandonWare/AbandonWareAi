package com.example.lms.config.aop;

import com.example.lms.service.correction.VectorAliasCorrector;
import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AliasCorrectionPreResolveAspectTest {

    @Test
    void correctsBuiltInAliasBeforeProceedingIndependentOfDefaultLocale() throws Throwable {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            VectorAliasCorrector corrector = new VectorAliasCorrector(0.62, 3);
            AliasCorrectionPreResolveAspect aspect = new AliasCorrectionPreResolveAspect(corrector);
            ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
            when(joinPoint.getArgs()).thenReturn(new Object[]{"WIN11"});
            when(joinPoint.proceed(any(Object[].class)))
                    .thenAnswer(invocation -> ((Object[]) invocation.getArgument(0))[0]);

            assertEquals("Windows 11", aspect.correctArgs(joinPoint));
        } finally {
            Locale.setDefault(previous);
        }
    }
}
