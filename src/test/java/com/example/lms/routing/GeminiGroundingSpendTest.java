package com.example.lms.routing;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.YearMonth;
import com.fasterxml.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;
class GeminiGroundingSpendTest {
 @TempDir Path directory;
 @Test void monthlyWarningPersistsCountsRollsOverAndNeverDeniesSearch() throws Exception {
  var method=ApiSpendAttribution.class.getMethod("recordGrounding",Path.class,YearMonth.class,int.class,long.class);
  Path ledger=directory.resolve("usage.json");var mapper=new ObjectMapper();
  var first=mapper.valueToTree(method.invoke(null,ledger,YearMonth.of(2026,10),4499,5000L));
  assertFalse(first.path("nearAllowance").asBoolean());
  var near=mapper.valueToTree(method.invoke(null,ledger,YearMonth.of(2026,10),1,5000L));
  assertEquals(4500,near.path("observedQueries").asLong());assertTrue(near.path("nearAllowance").asBoolean());
  var over=mapper.valueToTree(method.invoke(null,ledger,YearMonth.of(2026,10),600,5000L));
  assertEquals(5100,over.path("observedQueries").asLong());assertFalse(over.has("allowed"));
  var next=mapper.valueToTree(method.invoke(null,ledger,YearMonth.of(2026,11),2,5000L));
  assertEquals(2,next.path("observedQueries").asLong());
  var stored=mapper.readTree(Files.readString(ledger));assertEquals(2,stored.path("observedQueries").asLong());
  assertEquals(2,stored.size());assertFalse(Files.readString(ledger).contains("synthetic"));
 }
}
