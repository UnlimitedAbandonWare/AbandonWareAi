package com.example.lms.file;

import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import org.apache.commons.io.IOUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.CodingErrorAction;
import java.io.InputStream;
import java.util.HashSet;
import java.util.Set;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.LoggerFactory;
import org.slf4j.Logger;


// InputStream은 더 이상 필요 없음
/**
 * Service responsible for extracting plain text from a variety of file formats.
 * When users upload attachments via the chat API the controller forwards the raw
 * bytes and MIME type to this class, which attempts to extract a concise
 * textual representation.  Supported formats include plain text (UTF-8 and
 * UTF-16 with optional BOM), JSON/XML, CSV, Markdown and PDF.  The output is
 * truncated to a configurable maximum character length to guard against
 * extremely large inputs.  Extraction failures are swallowed and logged and
 * result in a null return value to avoid breaking the chat flow.
 */
@Service
public class FileIngestionService {
    private static final Logger log = LoggerFactory.getLogger(FileIngestionService.class);

    private static final int MAX_CHARS = 50_000;
    private static final int MAX_OFFICE_ENTRIES = 512;
    private static final int MAX_OFFICE_ENTRY_BYTES = 2_000_000;
    private static final int DEFAULT_MAX_ARCHIVE_ENTRIES = 300;
    private static final int DEFAULT_MAX_ARCHIVE_ENTRY_NAME_CHARS = 180;
    private static final int DEFAULT_MAX_ARCHIVE_SUMMARY_CHARS = 20_000;

    @Autowired(required = false)
    private Environment environment;

    public static final String PARSER_VERSION = "attachment-text-v1";
    private static final int UNIT_CHARS = 2_000;
    private static final int MAX_UNITS = 2_048;
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    /** Offsets are UTF-16 indexes in BOM-free decoded text; never byte offsets. */
    public record TextUnit(String memberPath, String locatorType, int lineStart, int lineEnd,
                           int startOffset, int endOffset, String jsonPointer, int paragraphIndex,
                           String text) {
        public String locator() {
            return (memberPath == null ? "" : memberPath + "#")
                    + ("PARAGRAPH".equals(locatorType) ? "P" + paragraphIndex
                    : "L" + lineStart + "-" + lineEnd + ":" + startOffset + "-" + endOffset)
                    + (jsonPointer == null ? "" : " json:" + jsonPointer);
        }

        public TextUnit excerpt(int maxChars, boolean fromEnd) {
            if (maxChars >= text.length()) return this;
            int start = fromEnd ? text.length() - Math.max(0, maxChars) : 0;
            int end = fromEnd ? text.length() : Math.max(0, maxChars);
            if (start < end && Character.isLowSurrogate(text.charAt(start))) start++;
            if (end > start && Character.isHighSurrogate(text.charAt(end - 1))) end--;
            String part = text.substring(start, end);
            int firstLine = lineStart > 0 ? lineStart
                    + (int) text.substring(0, start).chars().filter(c -> c == '\n').count() : 0;
            int lastLine = firstLine > 0 ? firstLine
                    + (int) part.chars().filter(c -> c == '\n').count() - (part.endsWith("\n") ? 1 : 0) : 0;
            return new TextUnit(memberPath, locatorType, firstLine, Math.max(firstLine, lastLine),
                    startOffset + start, startOffset + end, jsonPointer, paragraphIndex, part);
        }
    }

    public record DocumentExtraction(String state, String reasonCode, String detectedMime,
                                     String contentSha256, List<String> roles, List<TextUnit> units,
                                     List<String> archiveMembers, int inspectedEntryCount,
                                     int bodyReadCount, int omittedEntryCount, int invalidRecordCount) {
        public DocumentExtraction {
            roles = List.copyOf(roles);
            units = List.copyOf(units);
            archiveMembers = List.copyOf(archiveMembers);
        }
    }

    public boolean supportsStructuredDocument(String name, String mime) {
        String fn = name == null ? "" : name.toLowerCase(Locale.ROOT);
        String mt = mime == null ? "" : mime.toLowerCase(Locale.ROOT);
        return fn.endsWith(".docx") || isArchiveDocument(fn, mt) || isStructuredText(fn, mt);
    }

    private static boolean isStructuredText(String fn, String mt) {
        if (isOfficeDocument(fn, mt) || isArchiveDocument(fn, mt)) return false;
        return mt.startsWith("text/") || mt.contains("json") || mt.contains("yaml")
                || fn.matches(".*\\.(txt|md|markdown|json|jsonl|ndjson|java|py|js|ts|tsx|jsx|xml|csv|yml|yaml|properties|html|css|sql|log)$");
    }

    /** Deterministic attachment parsing. No provider calls or executable document instructions. */
    public DocumentExtraction extractDocument(String name, String mime, byte[] bytes, List<String> selectedMembers) {
        return extractDocument(name, mime, bytes, selectedMembers, null);
    }

    /** The caller's question selects names only; member text never grants further reads. */
    public DocumentExtraction extractDocumentForQuestion(String name, String mime, byte[] bytes, String question) {
        return extractDocument(name, mime, bytes, List.of(), question);
    }

    private DocumentExtraction extractDocument(String name, String mime, byte[] bytes,
                                               List<String> selectedMembers, String question) {
        String fn = name == null ? "" : name.toLowerCase(Locale.ROOT);
        String mt = mime == null ? "" : mime.toLowerCase(Locale.ROOT);
        String digest = bytes == null ? "" : org.apache.commons.codec.digest.DigestUtils.sha256Hex(bytes);
        if (bytes == null || bytes.length == 0) return extraction("EMPTY", "EMPTY_INPUT", mt, digest, fn, List.of());
        int limit = documentByteLimit();
        boolean archive = !fn.endsWith(".docx") && isArchiveDocument(fn, mt);
        int inputLimit = archive ? 25 * 1_048_576 : limit;
        if (bytes.length > inputLimit) return extraction("LIMIT_EXCEEDED", "DOCUMENT_BYTE_LIMIT", mt, digest, fn, List.of());
        try {
            if (archive || fn.endsWith(".docx")) {
                return structuredZip(fn, bytes, digest, selectedMembers == null ? List.of() : selectedMembers,
                        fn.endsWith(".docx"), limit, question);
            }
            if (!isStructuredText(fn, mt)) return extraction("UNSUPPORTED", "FORMAT_UNSUPPORTED", mt, digest, fn, List.of());
            String text = decodeDocument(bytes);
            if (fn.endsWith(".jsonl") || fn.endsWith(".ndjson")) {
                List<TextUnit> units = new ArrayList<>();
                int invalid = 0, offset = 0, line = 1;
                for (String record : text.split("(?<=\\n)", -1)) {
                    if (!record.isBlank()) {
                        try {
                            List<TextUnit> parsed = jsonUnits(record, null, offset, line);
                            if (units.size() + parsed.size() > MAX_UNITS)
                                return new DocumentExtraction("LIMIT_EXCEEDED", "TEXT_UNIT_LIMIT", "application/x-ndjson",
                                        digest, roles(fn), List.of(), List.of(), 0, 0, 0, invalid);
                            units.addAll(parsed);
                        } catch (IOException invalidJson) { invalid++; }
                    }
                    offset += record.length();
                    line++;
                }
                return new DocumentExtraction(invalid > 0 ? "PARTIAL" : units.isEmpty() ? "EMPTY" : "READY",
                        invalid > 0 ? "INVALID_JSONL_RECORDS" : "NONE", "application/x-ndjson",
                        digest, roles(fn), units, List.of(), 0, 0, 0, invalid);
            }
            boolean json = fn.endsWith(".json") || mt.contains("json");
            List<TextUnit> units = json ? jsonUnits(text, null, 0, 1) : textUnits(text, null);
            return extraction(units.isEmpty() ? "EMPTY" : "READY", "NONE",
                    json ? "application/json" : "text/plain", digest, fn, units);
        } catch (DocumentLimitException limitExceeded) {
            return extraction("LIMIT_EXCEEDED", limitExceeded.getMessage(), mt, digest, fn, List.of());
        } catch (Exception malformed) {
            return extraction("CORRUPT", "INVALID_DOCUMENT", mt, digest, fn, List.of());
        }
    }

    private int documentByteLimit() {
        return archiveInt("attachments.documents.maxBytes",
                archiveInt("attachments.inline.maxDocBytes", 1_048_576, 1, 1_048_576), 1, 1_048_576);
    }

    private static DocumentExtraction extraction(String state, String reason, String mime, String digest,
                                                 String name, List<TextUnit> units) {
        return new DocumentExtraction(state, reason, mime, digest, roles(name), units, List.of(), 0, 0, 0, 0);
    }

    private static List<String> roles(String name) {
        if (name.endsWith(".zip")) return List.of("source_snapshot");
        if (name.contains("directive") || name.startsWith("paste_")) return List.of("directive");
        if (name.contains("source_report") || name.contains("source_evidence")) return List.of("static_source_evidence");
        if (name.contains("test") && name.contains("report")) return List.of("test_report");
        if (name.endsWith(".log") || name.endsWith(".jsonl") || name.endsWith(".ndjson")) return List.of("log");
        return List.of("unknown");
    }

    private static String decodeDocument(byte[] bytes) throws IOException {
        Charset encoding = StandardCharsets.UTF_8;
        int offset = 0;
        if (bytes.length >= 2 && bytes[0] == (byte) 0xff && bytes[1] == (byte) 0xfe) {
            encoding = StandardCharsets.UTF_16LE; offset = 2;
        } else if (bytes.length >= 2 && bytes[0] == (byte) 0xfe && bytes[1] == (byte) 0xff) {
            encoding = StandardCharsets.UTF_16BE; offset = 2;
        } else if (bytes.length >= 3 && bytes[0] == (byte) 0xef && bytes[1] == (byte) 0xbb && bytes[2] == (byte) 0xbf) {
            offset = 3;
        }
        String text = encoding.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes, offset, bytes.length - offset)).toString();
        if (text.codePoints().anyMatch(c -> c < 32 && c != '\n' && c != '\r' && c != '\t'))
            throw new IOException("BINARY_TEXT");
        return text;
    }

    private static List<TextUnit> textUnits(String text, String member) throws IOException {
        List<TextUnit> units = new ArrayList<>();
        int start = 0, line = 1;
        while (start < text.length()) {
            if (units.size() >= MAX_UNITS) throw new DocumentLimitException("TEXT_UNIT_LIMIT");
            int end = Math.min(text.length(), start + UNIT_CHARS);
            if (end < text.length()) {
                int newline = text.lastIndexOf('\n', end - 1);
                if (newline >= start) end = newline + 1;
                else if (Character.isHighSurrogate(text.charAt(end - 1))) end--;
            }
            String part = text.substring(start, end);
            int newlines = (int) part.chars().filter(c -> c == '\n').count();
            int lastLine = line + newlines - (part.endsWith("\n") ? 1 : 0);
            if (!part.isBlank()) units.add(new TextUnit(member, "LINE", line, Math.max(line, lastLine),
                    start, end, null, 0, part));
            line += newlines;
            start = end;
        }
        return units;
    }

    private static List<TextUnit> jsonUnits(String text, String member, int baseOffset, int baseLine) throws IOException {
        JSON.readTree(text);
        List<TextUnit> units = new ArrayList<>();
        try (JsonParser parser = JSON.createParser(text)) {
            JsonToken token;
            while ((token = parser.nextToken()) != null) {
                if (!token.isScalarValue()) continue;
                parser.getText(); // Consume lazy strings before capturing their source end.
                int start = (int) parser.currentTokenLocation().getCharOffset();
                int end = (int) parser.currentLocation().getCharOffset();
                if (units.size() >= MAX_UNITS || end - start > UNIT_CHARS)
                    throw new DocumentLimitException("JSON_SPAN_LIMIT");
                units.add(new TextUnit(member, "JSON_POINTER",
                        baseLine + parser.currentTokenLocation().getLineNr() - 1,
                        baseLine + parser.currentLocation().getLineNr() - 1, baseOffset + start, baseOffset + end,
                        parser.getParsingContext().pathAsPointer().toString(), 0, text.substring(start, end)));
            }
        }
        if (units.isEmpty() && !text.isBlank())
            units.add(new TextUnit(member, "JSON_POINTER", baseLine, baseLine, baseOffset,
                    baseOffset + text.length(), "", 0, text));
        return units;
    }

    private DocumentExtraction structuredZip(String name, byte[] bytes, String digest,
                                             List<String> requested, boolean docx, int byteLimit,
                                             String question) throws Exception {
        validateCentralDirectory(bytes);
        Path temporary = Files.createTempFile("attachment-parse-", ".zip");
        try {
            Files.write(temporary, bytes);
            try (ZipFile zip = new ZipFile(temporary.toFile())) {
                Map<String, ZipEntry> entries = new LinkedHashMap<>();
                Set<String> foldedNames = new HashSet<>();
                var enumeration = zip.entries();
                while (enumeration.hasMoreElements()) {
                    ZipEntry entry = enumeration.nextElement();
                    String path = entry.getName();
                    if (!safeMember(path) || !foldedNames.add(path.toLowerCase(Locale.ROOT)))
                        throw new IOException("UNSAFE_ARCHIVE_PATH");
                    if (!entry.isDirectory()) entries.put(path, entry);
                }
                if (docx) {
                    ZipEntry document = entries.get("word/document.xml");
                    if (document == null) throw new IOException("DOCX_BODY_MISSING");
                    byte[] xml = zipBody(zip, document, byteLimit);
                    DocumentBuilderFactory factory = secureXmlFactory();
                    factory.setNamespaceAware(true);
                    factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
                    factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
                    Document doc = factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
                    NodeList paragraphs = doc.getElementsByTagNameNS("*", "p");
                    List<TextUnit> units = new ArrayList<>();
                    for (int i = 0; i < paragraphs.getLength(); i++) {
                        List<String> parts = new ArrayList<>();
                        collectText(paragraphs.item(i), parts);
                        String text = String.join(" ", parts);
                        if (text.length() > UNIT_CHARS || units.size() >= MAX_UNITS)
                            throw new DocumentLimitException("DOCX_SPAN_LIMIT");
                        if (!text.isBlank()) units.add(new TextUnit("word/document.xml", "PARAGRAPH", 0, 0,
                                0, text.length(), null, i + 1, text));
                    }
                    return extraction(units.isEmpty() ? "EMPTY" : "READY", "TEXT_ONLY",
                            "application/vnd.openxmlformats-officedocument.wordprocessingml.document", digest, name, units);
                }
                List<String> selections = requested.isEmpty() && question != null && !question.isBlank()
                        ? selectQuestionMembers(entries.keySet(), question)
                        : requested.stream().distinct().toList();
                if (selections.size() > 8) throw new DocumentLimitException("ARCHIVE_BODY_COUNT_LIMIT");
                List<TextUnit> units = new ArrayList<>();
                int readCount = 0, readBytes = 0;
                for (String selected : selections) {
                    if (!safeMember(selected) || excludedMember(selected))
                        throw new IOException("ARCHIVE_MEMBER_DISALLOWED");
                    ZipEntry entry = entries.get(selected);
                    if (entry == null) throw new IOException("ARCHIVE_MEMBER_MISSING");
                    String lower = selected.toLowerCase(Locale.ROOT);
                    if (!isStructuredText(lower, "")) throw new IOException("ARCHIVE_MEMBER_UNSUPPORTED");
                    byte[] body = zipBody(zip, entry, byteLimit);
                    readBytes += body.length;
                    if (readBytes > 8 * byteLimit) throw new DocumentLimitException("ARCHIVE_TOTAL_BYTE_LIMIT");
                    DocumentExtraction parsed = extractDocument(selected, "", body, List.of());
                    if (!Set.of("READY", "EMPTY").contains(parsed.state()))
                        throw new IOException("ARCHIVE_MEMBER_INVALID");
                    for (TextUnit u : parsed.units()) {
                        if (units.size() >= MAX_UNITS) throw new DocumentLimitException("TEXT_UNIT_LIMIT");
                        units.add(new TextUnit(selected, u.locatorType(), u.lineStart(), u.lineEnd(),
                                u.startOffset(), u.endOffset(), u.jsonPointer(), u.paragraphIndex(), u.text()));
                    }
                    readCount++;
                }
                int previewLimit = archiveInt("attachments.archive.maxEntries", DEFAULT_MAX_ARCHIVE_ENTRIES, 1, 10_000);
                int omitted = Math.max(0, entries.size() - readCount);
                return new DocumentExtraction(omitted > 0 || readCount == 0 ? "PARTIAL" : "READY",
                        readCount == 0 ? "ARCHIVE_LIST_ONLY" : omitted > 0 ? "ARCHIVE_SELECTION_ONLY" : "NONE",
                        "application/zip", digest, roles(name), units,
                        entries.keySet().stream().limit(previewLimit).toList(), entries.size(), readCount, omitted, 0);
            }
        } finally { Files.deleteIfExists(temporary); }
    }

    private static byte[] zipBody(ZipFile zip, ZipEntry entry, int limit) throws IOException {
        if (entry.getSize() < 0 || entry.getSize() > limit) throw new DocumentLimitException("ARCHIVE_MEMBER_BYTE_LIMIT");
        try (InputStream input = zip.getInputStream(entry)) {
            byte[] body = input.readNBytes(limit + 1);
            if (body.length > limit) throw new DocumentLimitException("ARCHIVE_MEMBER_BYTE_LIMIT");
            java.util.zip.CRC32 crc = new java.util.zip.CRC32();
            crc.update(body);
            if (body.length != entry.getSize() || crc.getValue() != entry.getCrc())
                throw new IOException("ARCHIVE_INTEGRITY");
            return body;
        }
    }

    private static List<String> selectQuestionMembers(Set<String> members, String question) {
        // Bound matching work independently of the archive preview/display limit.
        String bounded = question.substring(0, Math.min(question.length(), 16_000));
        Map<String, Integer> scores = new java.util.TreeMap<>();
        for (String member : members) {
            if (!safeMember(member) || excludedMember(member)
                    || !isStructuredText(member.toLowerCase(Locale.ROOT), "")) continue;
            String base = member.substring(member.lastIndexOf('/') + 1);
            int dot = base.lastIndexOf('.');
            String stem = dot > 0 ? base.substring(0, dot) : base;
            int score = mentionsMember(bounded, member) ? 3
                    : mentionsMember(bounded, base) ? 2
                    : stem.length() >= 3 && mentionsMember(bounded, stem) ? 1 : 0;
            if (score > 0) scores.put(member, score);
        }
        return scores.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed()
                        .thenComparing(Map.Entry.comparingByKey()))
                .limit(8).map(Map.Entry::getKey).toList();
    }

    private static boolean mentionsMember(String question, String member) {
        // A basename inside another full path or identifier is not an explicit reference.
        return java.util.regex.Pattern.compile("(?<![\\p{L}\\p{N}_./\\\\-])"
                + java.util.regex.Pattern.quote(member)
                + "(?:(?![\\p{L}\\p{N}_./\\\\-])|(?=(?:을|를|의|에|와|과|은|는|이|가)(?:\\s|$)))")
                .matcher(question).find();
    }

    private static boolean safeMember(String path) {
        if (path == null || path.isBlank() || path.length() > 2_000 || path.startsWith("/")
                || path.contains("\\") || path.contains(":") || path.codePoints().anyMatch(Character::isISOControl)) return false;
        for (String segment : path.split("/")) {
            if (segment.isBlank() || segment.equals(".") || segment.equals("..")
                    || segment.endsWith(".") || segment.endsWith(" ")) return false;
        }
        return true;
    }

    private static boolean excludedMember(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        for (String part : lower.split("/")) {
            if (Set.of(".git", ".secrets", "node_modules", "build", "target", ".gradle", "__pycache__",
                    "cookies", "auth", "credentials", "secrets", "dumps").contains(part)
                    || part.startsWith(".env") || part.equals("apikey.txt")
                    || part.matches(".*\\.(pem|key|pfx|p12|jks|db|sqlite|zip|jar|exe|dll)$")
                    || part.matches(".*(cookie|token|credential|storage.?state|session.?state|dump).*")
                    || part.equals("application-local.yml")) return true;
        }
        return false;
    }

    // JDK ZipFile owns decompression; inspect only bounded central-directory security flags.
    // ZIP APPNOTE 4.3.12/4.3.16: encrypted, split, ZIP64 and symbolic-link containers fail closed.
    private static void validateCentralDirectory(byte[] bytes) throws IOException {
        ByteBuffer b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        int end = -1;
        for (int p = bytes.length - 22; p >= Math.max(0, bytes.length - 65_557); p--) {
            if (b.getInt(p) == 0x06054b50 && p + 22 + Short.toUnsignedInt(b.getShort(p + 20)) == bytes.length) {
                end = p; break;
            }
        }
        if (end < 0) throw new IOException("ZIP_END_MISSING");
        int count = Short.toUnsignedInt(b.getShort(end + 10));
        long size = Integer.toUnsignedLong(b.getInt(end + 12));
        long start = Integer.toUnsignedLong(b.getInt(end + 16));
        if (count > 10_000) throw new DocumentLimitException("ARCHIVE_ENTRY_LIMIT");
        if (b.getShort(end + 4) != 0 || b.getShort(end + 6) != 0
                || Short.toUnsignedInt(b.getShort(end + 8)) != count || start + size != end)
            throw new IOException("ZIP_DIRECTORY_INVALID");
        int p = (int) start;
        for (int i = 0; i < count; i++) {
            if (p < 0 || p > end - 46 || b.getInt(p) != 0x02014b50) throw new IOException("ZIP_DIRECTORY_INVALID");
            int flags = Short.toUnsignedInt(b.getShort(p + 8));
            int unixType = (b.getInt(p + 38) >>> 16) & 0xf000;
            if ((flags & 1) != 0 || unixType == 0xa000 || b.getShort(p + 34) != 0)
                throw new IOException("ZIP_ENTRY_UNSUPPORTED");
            p += 46 + Short.toUnsignedInt(b.getShort(p + 28))
                    + Short.toUnsignedInt(b.getShort(p + 30)) + Short.toUnsignedInt(b.getShort(p + 32));
        }
        if (p != end) throw new IOException("ZIP_DIRECTORY_INVALID");
    }

    private static final class DocumentLimitException extends IOException {
        private DocumentLimitException(String reason) { super(reason); }
    }

    /**
     * Extract plain text from an uploaded file.  The strategy is chosen based on
     * the MIME type; unknown types fall back to an empty result.  Errors are
     * caught and logged; callers should handle a {@code null} return.
     *
     * @param fileName the name of the file
     * @param mimeType the declared MIME type (may be null or blank)
     * @param content  the raw file bytes
     * @return a string containing extracted text, or {@code null} on failure
     */
        /**
     * Extract plain text from an uploaded file.
     *
     * <p>This implementation uses a combination of MIME type and file extension
     * to decide how to interpret the content. Text-like formats are decoded as
     * UTF-8/UTF-16, while PDF files are processed via PDFBox. Unsupported or
     * clearly binary formats return {@code null} so that callers can decide
     * whether to attempt a fallback.</p>
     *
     * @param fileName the name of the file (may be {@code null})
     * @param mimeType the declared MIME type (may be {@code null} or generic)
     * @param content  the raw file bytes
     * @return extracted plain text, or {@code null} on failure/unsupported type
     */
    public String extractText(String fileName, String mimeType, byte[] content) {
        if (content == null || content.length == 0) {
            return null;
        }

        String mt = (mimeType == null) ? "" : mimeType.toLowerCase(Locale.ROOT);
        String fn = (fileName == null) ? "" : fileName.toLowerCase(Locale.ROOT);

        try {
            boolean isOffice = isOfficeDocument(fn, mt);
            boolean isArchive = !isOffice && isArchiveDocument(fn, mt);
            // MERGE_HOOK:PROJ_AGENT::file_ingestion_v2
            // 1) 텍스트/코드 파일 판별 (MIME 또는 확장자 기준)
            boolean isText = !isOffice && (mt.startsWith("text/")
                    || mt.contains("json") || mt.contains("xml") || mt.contains("csv") || mt.contains("yaml")
                    || mt.equals("application/javascript") || mt.equals("application/x-sh")
                    || fn.endsWith(".txt") || fn.endsWith(".json") || fn.endsWith(".xml") || fn.endsWith(".csv")
                    || fn.endsWith(".md") || fn.endsWith(".yml") || fn.endsWith(".yaml") || fn.endsWith(".properties")
                    || fn.endsWith(".java") || fn.endsWith(".py") || fn.endsWith(".js") || fn.endsWith(".ts")
                    || fn.endsWith(".html") || fn.endsWith(".css") || fn.endsWith(".sql") || fn.endsWith(".log"));

            if (isText) {
                // UTF-8 / UTF-16 BOM 감지
                Charset charset = StandardCharsets.UTF_8;
                if (content.length >= 2) {
                    int b0 = content[0] & 0xFF;
                    int b1 = content[1] & 0xFF;
                    if (b0 == 0xFE && b1 == 0xFF) {
                        charset = StandardCharsets.UTF_16BE;
                    } else if (b0 == 0xFF && b1 == 0xFE) {
                        charset = StandardCharsets.UTF_16LE;
                    }
                }
                String text = new String(content, charset);
                return truncate(text);

            // 2) PDF: MIME 타입 또는 확장자 기준 (application/octet-stream + .pdf 대응)
            } else if (mt.equals("application/pdf") || fn.endsWith(".pdf")) {
                try (PDDocument doc = Loader.loadPDF(content)) {
                    PDFTextStripper stripper = new PDFTextStripper();
                    String text = stripper.getText(doc);
                    return truncate(text);
                } catch (Throwable t) {
                    log.warn("[FileIngestion] PDF extraction failed fileNameHash={} fileNameLength={} errorHash={} errorLength={}",
                            com.example.lms.trace.SafeRedactor.hashValue(fileName), fileName == null ? 0 : fileName.length(),
                            com.example.lms.trace.SafeRedactor.hashValue(messageOf(t)), messageLength(t));
                    return null;
                }

            // 3) 기타: 지원하지 않는 형식은 여기서 명확히 걸러낸다.
            } else if (isOffice) {
                return extractOfficeText(fn, content);

            } else if (isArchive) {
                return extractArchiveTreeSummary(fn, content);

            } else {
                log.debug("[FileIngestion] Unsupported MIME type {} fileNameHash={} fileNameLength={}", mimeType, com.example.lms.trace.SafeRedactor.hashValue(fileName), fileName == null ? 0 : fileName.length());
                return null;
            }
        } catch (Exception e) {
            log.warn("[FileIngestion] extraction failed fileNameHash={} fileNameLength={} errorHash={} errorLength={}",
                    com.example.lms.trace.SafeRedactor.hashValue(fileName), fileName == null ? 0 : fileName.length(),
                    com.example.lms.trace.SafeRedactor.hashValue(messageOf(e)), messageLength(e));
            return null;
        }
    }

    private static boolean isOfficeDocument(String fn, String mt) {
        return fn.endsWith(".docx") || fn.endsWith(".pptx") || fn.endsWith(".xlsx") || fn.endsWith(".hwpx")
                || mt.contains("officedocument.wordprocessingml.document")
                || mt.contains("officedocument.presentationml.presentation")
                || mt.contains("officedocument.spreadsheetml.sheet")
                || mt.contains("application/x-hwpml")
                || mt.contains("application/haansoft-hwpx");
    }

    private static boolean isArchiveDocument(String fn, String mt) {
        return fn.endsWith(".zip")
                || mt.equals("application/zip")
                || mt.equals("application/x-zip-compressed")
                || mt.equals("multipart/x-zip")
                || mt.equals("application/octet-stream") && fn.endsWith(".zip");
    }

    private String extractArchiveTreeSummary(String fn, byte[] content) {
        int maxEntries = archiveInt("attachments.archive.maxEntries", DEFAULT_MAX_ARCHIVE_ENTRIES, 1, 10_000);
        int maxEntryNameChars = archiveInt("attachments.archive.maxEntryNameChars",
                DEFAULT_MAX_ARCHIVE_ENTRY_NAME_CHARS, 32, 2_000);
        int maxSummaryChars = archiveInt("attachments.archive.maxSummaryChars",
                DEFAULT_MAX_ARCHIVE_SUMMARY_CHARS, 512, 1_000_000);
        int entries = 0;
        int directories = 0;
        boolean truncated = false;
        Map<String, Integer> extCounts = new TreeMap<>();
        List<String> paths = new ArrayList<>();
        try (ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(content))) {
            ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null) {
                entries++;
                String path = safeArchivePath(entry.getName(), maxEntryNameChars);
                if (entry.isDirectory()) {
                    directories++;
                } else {
                    extCounts.merge(extensionOf(path), 1, Integer::sum);
                }
                if (paths.size() < maxEntries) {
                    paths.add((entry.isDirectory() ? "[dir] " : "[file] ") + path);
                }
                if (entries >= maxEntries) {
                    truncated = zin.getNextEntry() != null;
                    break;
                }
            }
        } catch (Throwable t) {
            log.warn("[FileIngestion] Archive tree extraction failed fileNameHash={} fileNameLength={} errorHash={} errorLength={}",
                    com.example.lms.trace.SafeRedactor.hashValue(fn), fn == null ? 0 : fn.length(),
                    com.example.lms.trace.SafeRedactor.hashValue(messageOf(t)), messageLength(t));
            return null;
        }
        if (entries == 0) {
            return null;
        }
        Map<String, Integer> boundedExtCounts = new LinkedHashMap<>();
        extCounts.entrySet().stream().limit(40).forEach(e -> boundedExtCounts.put(e.getKey(), e.getValue()));
        StringBuilder sb = new StringBuilder(1024);
        sb.append("### ARCHIVE TREE SUMMARY\n");
        sb.append("file=").append(fn == null || fn.isBlank() ? "archive.zip" : fn).append('\n');
        sb.append("sampledEntryCount=").append(entries).append('\n');
        sb.append("maxEntries=").append(maxEntries).append('\n');
        sb.append("truncated=").append(truncated).append('\n');
        sb.append("directoryCount=").append(directories).append('\n');
        sb.append("fileCount=").append(Math.max(0, entries - directories)).append('\n');
        sb.append("extensions=").append(boundedExtCounts).append('\n');
        sb.append("tree:\n");
        for (String path : paths) {
            sb.append("- ").append(path).append('\n');
        }
        if (truncated) {
            sb.append("- [truncated: maxEntries=").append(maxEntries).append("]\n");
        }
        return truncateArchiveSummary(sb.toString(), maxSummaryChars);
    }

    private static String safeArchivePath(String name, int maxEntryNameChars) {
        String value = name == null ? "" : name.replace('\\', '/').replaceAll("[\\p{Cntrl}&&[^\r\n\t]]", "");
        while (value.startsWith("/")) {
            value = value.substring(1);
        }
        if (value.contains("..")) {
            value = value.replace("..", "__");
        }
        if (value.length() > maxEntryNameChars) {
            value = value.substring(0, maxEntryNameChars) + "...";
        }
        return value.isBlank() ? "unnamed" : value;
    }

    private static String extensionOf(String path) {
        if (path == null || path.isBlank()) {
            return "[none]";
        }
        String name = path.toLowerCase(Locale.ROOT);
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return "[none]";
        }
        return name.substring(dot + 1);
    }

    private static String truncateArchiveSummary(String text, int maxSummaryChars) {
        if (text == null || text.length() <= maxSummaryChars) {
            return text;
        }
        return text.substring(0, maxSummaryChars) + "\n[ARCHIVE SUMMARY TRUNCATED]";
    }

    private int archiveInt(String key, int def, int min, int max) {
        try {
            if (environment != null) {
                String raw = environment.getProperty(key);
                if (raw != null && !raw.isBlank()) {
                    int value = Integer.parseInt(raw.trim());
                    return Math.max(min, Math.min(max, value));
                }
            }
        } catch (Exception ignore) {
            traceSuppressed("fileIngestion.archiveInt", ignore);
            // Keep archive inspection fail-soft; defaults remain safe.
        }
        return def;
    }

    private static String extractOfficeText(String fn, byte[] content) {
        List<String> chunks = new ArrayList<>();
        int entries = 0;
        try (ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(content))) {
            ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null && entries < MAX_OFFICE_ENTRIES) {
                entries++;
                if (entry.isDirectory() || !isOfficeTextEntry(fn, entry.getName())) {
                    continue;
                }
                String text = xmlText(readBounded(zin, MAX_OFFICE_ENTRY_BYTES));
                if (text != null && !text.isBlank()) {
                    chunks.add(text);
                }
            }
        } catch (Throwable t) {
            log.warn("[FileIngestion] Office XML extraction failed fileNameHash={} fileNameLength={} errorHash={} errorLength={}",
                    com.example.lms.trace.SafeRedactor.hashValue(fn), fn == null ? 0 : fn.length(),
                    com.example.lms.trace.SafeRedactor.hashValue(messageOf(t)), messageLength(t));
            return null;
        }
        return chunks.isEmpty() ? null : truncate(String.join("\n", chunks));
    }

    private static boolean isOfficeTextEntry(String fn, String name) {
        if (name == null) {
            return false;
        }
        String n = name.replace('\\', '/').toLowerCase(Locale.ROOT);
        if (!n.endsWith(".xml") || n.contains("..") || n.startsWith("/") || n.startsWith("meta-inf/")) {
            return false;
        }
        if (fn.endsWith(".docx")) {
            return n.equals("word/document.xml") || n.startsWith("word/header") || n.startsWith("word/footer");
        }
        if (fn.endsWith(".pptx")) {
            return n.startsWith("ppt/slides/") || n.startsWith("ppt/notesslides/");
        }
        if (fn.endsWith(".xlsx")) {
            return n.equals("xl/sharedstrings.xml") || n.startsWith("xl/worksheets/");
        }
        if (fn.endsWith(".hwpx")) {
            return n.startsWith("contents/") || n.startsWith("section") || n.contains("/section");
        }
        return false;
    }

    private static byte[] readBounded(ZipInputStream in, int maxBytes) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int total = 0;
        int read;
        while ((read = in.read(buffer)) >= 0) {
            total += read;
            if (total > maxBytes) {
                throw new IOException("office XML entry too large");
            }
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    private static String xmlText(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        try {
            DocumentBuilderFactory factory = secureXmlFactory();
            Document doc = factory.newDocumentBuilder().parse(new InputSource(new ByteArrayInputStream(bytes)));
            List<String> text = new ArrayList<>();
            collectText(doc, text);
            String joined = String.join(" ", text).replaceAll("\\s+", " ").trim();
            return joined.isBlank() ? null : joined;
        } catch (Throwable ignore) {
            traceSuppressed("fileIngestion.xmlText", ignore);
            return null;
        }
    }

    private static DocumentBuilderFactory secureXmlFactory() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        setFeature(factory, "http://apache.org/xml/features/disallow-doctype-decl", true);
        setFeature(factory, "http://xml.org/sax/features/external-general-entities", false);
        setFeature(factory, "http://xml.org/sax/features/external-parameter-entities", false);
        setFeature(factory, "http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        return factory;
    }

    private static void setFeature(DocumentBuilderFactory factory, String feature, boolean enabled) {
        try {
            factory.setFeature(feature, enabled);
        } catch (javax.xml.parsers.ParserConfigurationException ignored) {
            traceSuppressed("fileIngestion.xmlFeature", ignored);
            // Parser implementations differ; FEATURE_SECURE_PROCESSING remains the baseline.
        }
    }

    private static void collectText(Node node, List<String> out) {
        if (node == null) {
            return;
        }
        if (node.getNodeType() == Node.TEXT_NODE || node.getNodeType() == Node.CDATA_SECTION_NODE) {
            String value = node.getNodeValue();
            if (value != null && !value.isBlank()) {
                out.add(value.trim());
            }
        }
        NodeList children = node.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            collectText(children.item(i), out);
        }
    }

    private static String messageOf(Throwable t) {
        return t == null ? null : t.getMessage();
    }

    private static int messageLength(Throwable t) {
        String message = messageOf(t);
        return message == null ? 0 : message.length();
    }

    private static void traceSuppressed(String stage, Throwable ignored) {
        String safeStage = SafeRedactor.traceLabelOrFallback(stage, "unknown");
        String safeErrorType = errorType(ignored);
        TraceStore.put("file.ingestion.suppressed.stage", safeStage);
        TraceStore.put("file.ingestion.suppressed.errorType", safeErrorType);
        TraceStore.put("file.ingestion.suppressed." + safeStage, true);
        TraceStore.put("file.ingestion.suppressed." + safeStage + ".errorType", safeErrorType);
    }

    private static String errorType(Throwable ignored) {
        if (ignored == null) {
            return "unknown";
        }
        if (ignored instanceof NumberFormatException) {
            return "invalid_number";
        }
        return SafeRedactor.traceLabelOrFallback(ignored.getClass().getSimpleName(), "unknown");
    }

    private static String truncate(String text) {
        if (text == null) return null;
        if (text.length() > MAX_CHARS) {
            int end = MAX_CHARS;
            if (Character.isHighSurrogate(text.charAt(end - 1))
                    && Character.isLowSurrogate(text.charAt(end))) {
                end--;
            }
            return text.substring(0, end) + "\n[TRUNCATED]";
        }
        return text;
    }
}
