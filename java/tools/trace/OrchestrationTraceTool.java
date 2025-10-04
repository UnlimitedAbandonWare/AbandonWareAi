package tools.trace;
import java.io.*;
import java.time.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;

public class OrchestrationTraceTool {
    // Writes a one-line JSON trace to ./orchestration_traces.log (project root at runtime)
    public static class Result { public boolean ok=true; public String path="orchestration_traces.log"; }

    private static String jsonEscape(String s){
        if (s == null) return "";
        String r = s.replace("\\", "\\\\").replace("\"", "\\\"");
        r = r.replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
        return r;
    }

    private static String toJson(String[] arr){
        if (arr == null) return "[]";
        StringBuilder b = new StringBuilder("[");
        for (int i=0;i<arr.length;i++){
            if (i>0) b.append(',');
            b.append('\"').append(jsonEscape(arr[i])).append('\"');
        }
        b.append(']');
        return b.toString();
    }

    private static String toJson(double[] arr){
        if (arr == null) return "[]";
        StringBuilder b = new StringBuilder("[");
        for (int i=0;i<arr.length;i++){
            if (i>0) b.append(',');
            b.append(Double.toString(arr[i]));
        }
        b.append(']');
        return b.toString();
    }

    public Result invoke(String flowId, String[] steps, double[] sigma, double score){
        try{
            String ts = OffsetDateTime.now().toString();
            String json = String.format("{\"ts\":\"%s\",\"flow\":\"%s\",\"steps\":%s,\"sigma\":%s,\"S\":%.6f}%n",
                jsonEscape(ts), jsonEscape(flowId), toJson(steps), toJson(sigma), score);
            Files.write(Paths.get("orchestration_traces.log"), json.getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }catch(Exception e){}
        return new Result();
    }
}
