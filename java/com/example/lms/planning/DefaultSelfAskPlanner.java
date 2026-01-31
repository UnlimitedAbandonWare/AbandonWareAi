
package com.example.lms.planning;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;




@Component
@ConditionalOnProperty(name="selfask.branch3.enabled", havingValue = "true", matchIfMissing = true)
public class DefaultSelfAskPlanner implements SelfAskPlanner {

    private static final Pattern ENTITY = Pattern.compile("([A-Z][a-z]+(?:\\s+[A-Z][a-z]+)*)|([가-힣]{2,})");

    @Override
    public List<SubQ> branch3(String query) {
        String q = Optional.ofNullable(query).orElse("").trim();
        String bq = q.endsWith("?")? q : q + "?";
        // ER: extract a probable entity and ask relation
        Matcher m = ENTITY.matcher(q);
        String ent = m.find()? m.group().trim() : "해당 대상";
        String er = String.format("%s 관련 핵심 관계/속성은?", ent);
        // RC: resolve ambiguity by asking for context/time/place
        String rc = q + " - 최신 업데이트(날짜)와 출처는?";
        return Arrays.asList(new SubQ(BranchType.BQ, bq), new SubQ(BranchType.ER, er), new SubQ(BranchType.RC, rc));
    }
}