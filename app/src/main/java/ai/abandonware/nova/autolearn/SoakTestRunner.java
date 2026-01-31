package ai.abandonware.nova.autolearn;

import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.*;

/**
 * Attempts to call an existing ChatService bean via reflection if available.
 * Otherwise, returns a deterministic stub answer with no pass.
 */
@Component
@ConditionalOnProperty(name = "autolearn.enabled", havingValue = "true")
public class SoakTestRunner {

    private static final Logger log = LoggerFactory.getLogger(SoakTestRunner.class);
    private final ApplicationContext applicationContext;

    @Autowired
    public SoakTestRunner(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    public SoakResult runSingle(String query) {
        try {
            Object chatBean = findChatBean();
            if (chatBean == null) {
                log.debug("[SoakTestRunner] No ChatService bean; returning stub result.");
                return SoakResult.failed(query, "no_chat_service");
            }
            // Prefer a method like: Answer ask(String q) or Answer chat(String q)
            Method m = findAskMethod(chatBean.getClass());
            if (m == null) {
                return SoakResult.failed(query, "no_ask_method");
            }
            Object answer = m.invoke(chatBean, query);
            // extract fields via reflection: text + citations + score
            String text = extractString(answer, "getText", "getAnswer", "toString");
            List<String> citations = extractList(answer, "getCitations", "getSources");
            double score = extractDouble(answer, "getFinalSigmoidScore", "getScore");
            if (citations == null) citations = Collections.emptyList();
            boolean pass = citations.size() >= Integer.getInteger("gate.citation.min", 4) && score >= Double.parseDouble(System.getProperty("gate.finalSigmoid.threshold", "0.90"));
            String mode = "normal";
            if (!pass && hasMode(chatBean, "brave")) {
                Object braveAns = invokeWithMode(chatBean, m, query, "brave");
                text = extractString(braveAns, "getText", "getAnswer", "toString");
                citations = extractList(braveAns, "getCitations", "getSources");
                score = extractDouble(braveAns, "getFinalSigmoidScore", "getScore");
                mode = "brave";
                pass = citations != null && citations.size() >= Integer.getInteger("gate.citation.min", 4) && score >= Double.parseDouble(System.getProperty("gate.finalSigmoid.threshold", "0.90"));
            }
            if (pass) return SoakResult.pass(query, text, citations, mode, score);
            return SoakResult.failed(query, "insufficient_evidence");
        } catch (Exception e) {
            log.warn("[SoakTestRunner] Exception: {}", e.toString());
            return SoakResult.failed(query, "exception");
        }
    }

    private Object findChatBean() {
        String[] names = applicationContext.getBeanDefinitionNames();
        for (String n : names) {
            if (n.toLowerCase().contains("chatservice") || n.toLowerCase().contains("chatengine")) {
                try {
                    return applicationContext.getBean(n);
                } catch (Throwable ignore) {}
            }
        }
        return null;
    }

    private Method findAskMethod(Class<?> c) {
        for (Method m : c.getMethods()) {
            if (m.getName().equals("ask") && m.getParameterCount()==1 && m.getParameterTypes()[0]==String.class) return m;
            if (m.getName().equals("chat") && m.getParameterCount()==1 && m.getParameterTypes()[0]==String.class) return m;
        }
        return null;
    }

    private boolean hasMode(Object bean, String mode) {
        try {
            Method m = bean.getClass().getMethod("supportsMode", String.class);
            Object out = m.invoke(bean, mode);
            return out instanceof Boolean && (Boolean) out;
        } catch (Exception ignore) {}
        return false;
    }

    private Object invokeWithMode(Object bean, Method ask, String q, String mode) {
        try {
            Method m = bean.getClass().getMethod("setMode", String.class);
            m.invoke(bean, mode);
        } catch (Exception ignore) {}
        try {
            return ask.invoke(bean, q);
        } catch (Exception e) {
            return null;
        } finally {
            try {
                Method m2 = bean.getClass().getMethod("setMode", String.class);
                m2.invoke(bean, "normal");
            } catch (Exception ignore) {}
        }
    }

    @SuppressWarnings("unchecked")
    private List<String> extractList(Object bean, String... getters) {
        for (String g : getters) {
            try {
                Object v = bean.getClass().getMethod(g).invoke(bean);
                if (v == null) return null;
                if (v instanceof List) return (List<String>) v;
                if (v instanceof String) return Arrays.asList(((String)v).split("\\s+"));
            } catch (Exception ignore) {}
        }
        return null;
    }

    private String extractString(Object bean, String... getters) {
        for (String g : getters) {
            try {
                Object v = bean.getClass().getMethod(g).invoke(bean);
                if (v != null) return String.valueOf(v);
            } catch (Exception ignore) {}
        }
        return null;
    }

    private double extractDouble(Object bean, String... getters) {
        for (String g : getters) {
            try {
                Object v = bean.getClass().getMethod(g).invoke(bean);
                if (v instanceof Number) return ((Number)v).doubleValue();
                if (v != null) return Double.parseDouble(String.valueOf(v));
            } catch (Exception ignore) {}
        }
        return 0.0;
    }
}