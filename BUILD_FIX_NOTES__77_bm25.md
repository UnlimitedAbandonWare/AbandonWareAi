# Build Fix Notes — src111_merge15 (77)

## Errors observed
- `class, interface, enum, or record expected` around lines with `} catch (Throwable ignore) { /* analyzer not on classpath */ }`
- `cannot find symbol: class NoriAnalyzer` at:
  - `com.abandonware.ai.agent.service.rag.bm25.Bm25IndexHolder`
  - `com.abandonware.ai.agent.service.rag.bm25.Bm25IndexService`

## Root causes (matched with in-repo pattern codes)
- **IllegalStartOfType / ClassOrInterfaceExpected** → caused by prior merge where method/class blocks were malformed (the parser thinks it's outside a class when it hits `catch`).
- **MissingSymbol (NoriAnalyzer)** → module does not always include `lucene-analyzers-nori` on the compile classpath.

## What I changed
1) **Made `NoriAnalyzer` optional at _compile time_**  
   - Removed the direct import `org.apache.lucene.analysis.ko.NoriAnalyzer`.
   - Implemented a reflective loader in `createAnalyzer(String)`:
     ```java
     if (!"nori".equalsIgnoreCase(name)) return new StandardAnalyzer();
     try {
         Class<?> c = Class.forName("org.apache.lucene.analysis.ko.NoriAnalyzer");
         return (Analyzer) c.getDeclaredConstructor().newInstance();
     } catch (Throwable ignore) { /* analyzer not on classpath */ }
     return new StandardAnalyzer();
     ```
   - This compiles even when Nori is not on the classpath, while still using it when present at runtime.

2) **Normalized the try/catch block layout** in both files to avoid parser confusion.

## Touched files
- `src/main/java/com/abandonware/ai/agent/service/rag/bm25/Bm25IndexHolder.java`
- `src/main/java/com/abandonware/ai/agent/service/rag/bm25/Bm25IndexService.java`

## Side notes
- If you want to force-enable Nori, keep `lucene-analyzers-nori` aligned with `lucene-core` (e.g., `9.9.2`).  
- `StandardAnalyzer` requires `lucene-analyzers-common` (already present in some modules).

## Verification tips
Local check (skip tests):
```
./gradlew -x test :compileJava
```
If you still see `MissingSymbol` for other Lucene classes, verify the Gradle module that owns the BM25 classes declares:
```
implementation("org.apache.lucene:lucene-core:<version>")
implementation("org.apache.lucene:lucene-analyzers-common:<version>")
```
and optionally (for runtime Nori):
```
runtimeOnly("org.apache.lucene:lucene-analyzers-nori:<version>")
```
