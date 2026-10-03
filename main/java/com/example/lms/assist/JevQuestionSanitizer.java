package com.example.lms.assist;

import java.net.URI;
import java.text.Normalizer;
import java.util.Optional;
import java.util.regex.Pattern;

/** Question-only minimization. Reject over-limit or malformed input instead of truncating identities. */
public final class JevQuestionSanitizer {
    private static final Pattern URL=Pattern.compile("(?i)https?://[^\\s<>]+");
    private static final Pattern NUMBER_GROUPS=Pattern.compile("(?<!\\d)\\+?\\d{1,6}(?:[- .]\\d{1,6}){1,4}(?!\\d)");
    public Optional<String> sanitize(String question){
        if(question==null)return Optional.empty();
        for(int i=0;i<question.length();i++){
            char c=question.charAt(i);
            if(Character.isHighSurrogate(c)){
                if(i+1>=question.length()||!Character.isLowSurrogate(question.charAt(++i)))return Optional.empty();
            }else if(Character.isLowSurrogate(c))return Optional.empty();
        }
        try {
            String text=Normalizer.normalize(question,Normalizer.Form.NFC).replaceAll("[\\p{Z}\\s]+"," ").strip();
            if(text.length()>1200||text.isBlank())return Optional.empty();
            var urls=URL.matcher(text);var safe=new StringBuffer();
            while(urls.find()){
                var uri=URI.create(urls.group());
                if(uri.getHost()==null)return Optional.empty();
                String clean=new URI(uri.getScheme(),null,uri.getHost(),uri.getPort(),uri.getPath(),null,null).toASCIIString();
                urls.appendReplacement(safe,java.util.regex.Matcher.quoteReplacement(clean));
            }
            urls.appendTail(safe);text=safe.toString();
            text=text.replaceAll("(?i)[a-z]:[\\\\/]+users[\\\\/]+[^\\s<>]*","[PATH]")
                    .replaceAll("(?i)[\\p{L}\\p{N}._%+\\-]+@[\\p{L}\\p{N}.\\-]+\\.[\\p{L}]{2,}","[EMAIL]")
                    .replaceAll("(?i)\\bBearer\\s+[A-Za-z0-9._~+/=\\-]+","[CREDENTIAL]")
                    .replaceAll("(?i)\\b(?:api[ _-]?key|access[ _-]?token|client[ _-]?secret)\\s*[:=]\\s*[^\\s,;]+","[CREDENTIAL]")
                    .replaceAll("\\beyJ[A-Za-z0-9_\\-]*\\.[A-Za-z0-9_\\-]+\\.[A-Za-z0-9_\\-]+\\b","[CREDENTIAL]")
                    .replaceAll("\\b(?:sk|pk|gsk|ghp|gho|hf)[-_][A-Za-z0-9_\\-]{8,}\\b","[CREDENTIAL]")
                    .replaceAll("(?<!\\d)\\d{6}\\s*-?\\s*[1-8]\\d{6}(?!\\d)","[IDENTIFIER]")
                    .replaceAll("(?<!\\d)(?:\\+?82[- .]?)?0?1[016789][- .]?\\d{3,4}[- .]?\\d{4}(?!\\d)","[PHONE]")
                    .replaceAll("(?<!\\d)\\d{10,16}(?!\\d)","[NUMBER]");
            text=NUMBER_GROUPS.matcher(text).replaceAll(match->
                    match.group().codePoints().filter(Character::isDigit).count()>=10?"[NUMBER]":match.group());
            if(text.length()>1200||text.codePoints().anyMatch(c->Character.isISOControl(c)))return Optional.empty();
            return Optional.of(text);
        }catch(RuntimeException|java.net.URISyntaxException malformed){return Optional.empty();}
    }
}
