package app.service.onnx;
import java.util.List;
public class OnnxCrossEncoderReranker {
    private final boolean enabled;
    public OnnxCrossEncoderReranker(boolean enabled){ this.enabled = enabled; }
    public List<String> rerank(List<String> docs, String query){
        // placeholder: return as-is when disabled
        return docs;
    }
}
