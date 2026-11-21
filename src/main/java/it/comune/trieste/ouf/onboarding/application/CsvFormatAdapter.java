package it.comune.trieste.ouf.onboarding.application;

import java.io.IOException;
import java.nio.charset.*;
import org.apache.commons.csv.*;
import java.util.*;
import it.comune.trieste.ouf.onboarding.application.ManagedFileProfiler.*;
import static it.comune.trieste.ouf.onboarding.application.ManagedFileProfiler.*;

final class CsvFormatAdapter implements ManagedFileFormatAdapter {
  @Override public String mediaType() { return "text/csv"; }

  @Override public Profile profile(byte[] bytes) {
    String text;
    try {
      text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString();
    } catch(CharacterCodingException e) {throw new IllegalArgumentException("CSV must be valid UTF-8 text",e);}
    if(text.startsWith("\uFEFF"))text=text.substring(1);
    if(text.indexOf('\u0000')>=0)throw new IllegalArgumentException("CSV must be UTF-8 text");
    char separator=delimiter(text);
    CSVFormat format=CSVFormat.DEFAULT.builder().setDelimiter(separator).setHeader()
        .setSkipHeaderRecord(true).setIgnoreEmptyLines(true).get();
    try(CSVParser parser=CSVParser.parse(text,format)) {
      List<String> headers=parser.getHeaderNames();validateHeaders(headers);
      List<Set<String>> distinct=new ArrayList<>(headers.size());
      for(int c=0;c<headers.size();c++)distinct.add(new HashSet<>());
      String[] types=new String[headers.size()];boolean[] nullable=new boolean[headers.size()];
      List<Map<String,String>> sample=new ArrayList<>(5);int rows=0;
      for(CSVRecord record:parser) {
        if(rows>=MAX_ROWS)throw new IllegalArgumentException("row limit exceeded");
        if(record.size()>headers.size())throw new IllegalArgumentException("CSV row exceeds declared headers");
        Map<String,String> example=rows<5?new LinkedHashMap<>():null;
        for(int c=0;c<headers.size();c++) {
          String raw=record.isSet(c)?record.get(c):"",value=raw.trim();
          if(value.isEmpty())nullable[c]=true;
          else {
            distinct.get(c).add(value);
            types[c]=merge(types[c],infer(value));
          }
          if(example!=null)example.put(headers.get(c),redact(headers.get(c),raw));
        }
        if(example!=null)sample.add(example);
        rows++;
      }
      List<ColumnProfile> columns=new ArrayList<>(headers.size());List<String> keys=new ArrayList<>();
      for(int c=0;c<headers.size();c++) {
        columns.add(new ColumnProfile(headers.get(c),types[c]==null?"STRING":types[c],nullable[c],distinct.get(c).size()));
        if(!nullable[c]&&rows>0&&distinct.get(c).size()==rows)keys.add(headers.get(c));
      }
      return new Profile("CSV",Map.of("encoding","UTF-8","delimiter",String.valueOf(separator),
          "headerRow",1,"rows",rows,"columns",headers.size()),List.copyOf(columns),List.copyOf(keys),List.copyOf(sample));
    } catch(IOException e) {throw new IllegalArgumentException("invalid CSV",e);}
  }
  private static char delimiter(String text){String first=text.lines().findFirst().orElse("");long comma=first.chars().filter(c->c==',').count(),semicolon=first.chars().filter(c->c==';').count();return semicolon>comma?';':',';}
}
