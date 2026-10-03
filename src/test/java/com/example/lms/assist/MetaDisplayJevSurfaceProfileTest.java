package com.example.lms.assist;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import static org.junit.jupiter.api.Assertions.*;

/** DEMO1-DEVIN-META-DISPLAY-JEV-PORT-20260930 S3: application-meta-display.yml의 Jev
 *  surface 계약 고정. 기본 off, surface override는 env/-D로만, 빈 override는 off. */
class MetaDisplayJevSurfaceProfileTest {
    static List<PropertySource<?>> displaySources()throws Exception{
        return new YamlPropertySourceLoader().load("application-meta-display.yml",new ClassPathResource("application-meta-display.yml"));
    }
    static StandardEnvironment displayEnv()throws Exception{
        var env=new StandardEnvironment();
        for(PropertySource<?> source:displaySources())env.getPropertySources().addLast(source);
        return env;
    }
    @Test void defaultsKeepEverySurfaceOff()throws Exception{
        var env=displayEnv();var policy=new JevSurfacePolicy(env);
        assertEquals("off",policy.resolve("focus").mode());
        assertEquals("off",policy.resolve("cue").mode());
        assertEquals("off",policy.resolve("main").mode());
        assertEquals("off",env.getProperty("demo.jev.mode"));
        assertEquals(Boolean.FALSE,env.getProperty("demo.jev.allow-paid",Boolean.class));
        assertEquals(Boolean.TRUE,env.getProperty("demo.jev.free-only",Boolean.class));
        assertEquals(Boolean.FALSE,env.getProperty("jev.gateway.zero-data-retention",Boolean.class));
    }
    @Test void ymlHasNoSurfaceChoicePrefetchKeys()throws Exception{
        int enumerated=0;
        for(PropertySource<?> source:displaySources())if(source instanceof EnumerablePropertySource<?> enumerable)
            for(String name:enumerable.getPropertyNames()){enumerated++;
                assertFalse(name.startsWith("demo.jev.surface.")||name.startsWith("demo.jev.choice.")||name.startsWith("demo.jev.prefetch."),name);}
        assertTrue(enumerated>0,"yml property names must be enumerable");
    }
    @Test void shadowFocusOnlyProfile()throws Exception{
        var env=displayEnv();
        env.getPropertySources().addFirst(new MapPropertySource("overlay",
                Map.of("demo.jev.mode","shadow","demo.jev.surface.cue.mode","off")));
        var policy=new JevSurfacePolicy(env);
        assertEquals("shadow",policy.resolve("focus").mode());
        assertEquals("off",policy.resolve("cue").mode());
    }
    @Test void emptyOverrideForcesOffPitfall()throws Exception{
        var env=displayEnv();
        env.getPropertySources().addFirst(new MapPropertySource("overlay",
                Map.of("demo.jev.mode","shadow","demo.jev.surface.focus.mode","")));
        assertEquals("off",new JevSurfacePolicy(env).resolve("focus").mode());
    }
}
