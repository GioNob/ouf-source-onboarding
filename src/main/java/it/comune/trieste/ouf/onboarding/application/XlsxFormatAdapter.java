package it.comune.trieste.ouf.onboarding.application;

import java.io.*;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import java.util.*;
import it.comune.trieste.ouf.onboarding.application.ManagedFileProfiler.*;
import static it.comune.trieste.ouf.onboarding.application.ManagedFileProfiler.*;

final class XlsxFormatAdapter implements ManagedFileFormatAdapter {
  @Override public String mediaType() { return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"; }

  @Override public Profile profile(byte[] bytes) {
    try (Workbook workbook=new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
      int count=workbook.getNumberOfSheets();
      if(count==0)throw new IllegalArgumentException("workbook has no sheets");
      var first=sheetProfile(workbook.getSheetAt(0),count);
      var metadata=new LinkedHashMap<>(first.metadata());
      var sheets=new ArrayList<Map<String,Object>>();
      // The first sheet is already retained in the original top-level profile.
      sheets.add(Map.of("sheet",first.metadata().get("sheet")));
      for(int i=1;i<count;i++) {
        Sheet sheet=workbook.getSheetAt(i);
        try {
          Profile profile=sheetProfile(sheet,count);
          var item=new LinkedHashMap<>(profile.metadata());
          item.put("columns",profile.columns());
          item.put("candidateKeys",profile.candidateKeys());
          item.put("sample",profile.sample());
          sheets.add(item);
        } catch(IllegalArgumentException invalidHeaderOrData) {
          // A secondary sheet can require a different header. Inventory it without
          // publishing a schema that the default interpretation could not prove.
          sheets.add(Map.of("sheet",sheet.getSheetName()));
        }
      }
      metadata.put("sheets",sheets);
      return new Profile(first.format(),metadata,first.columns(),first.candidateKeys(),first.sample());
    } catch(IOException e) {throw new IllegalArgumentException("invalid XLSX",e);}
  }

  private Profile sheetProfile(Sheet sheet,int count) {
    Row header=sheet.getRow(sheet.getFirstRowNum());
    if(header==null)throw new IllegalArgumentException("sheet has no header");
    int columns=header.getLastCellNum();
    if(columns<1||columns>MAX_COLUMNS)throw new IllegalArgumentException("column limit exceeded");
    var headers=new ArrayList<String>();
    for(int i=0;i<columns;i++)headers.add(cellText(header.getCell(i),true));
    validateHeaders(headers);
    var nativeTypes=new LinkedHashMap<String,String>();
    var rows=new ArrayList<List<String>>();
    for(Row row:sheet) {
      if(row.getRowNum()<=header.getRowNum())continue;
      if(rows.size()>=MAX_ROWS)throw new IllegalArgumentException("row limit exceeded");
      var values=new ArrayList<String>();
      for(int c=0;c<columns;c++) {
        Cell cell=row.getCell(c);
        values.add(cellText(cell,false));
        if(cell!=null && cell.getCellType()!=CellType.BLANK)
          nativeTypes.merge(headers.get(c),cell.getCellType().name(),(before,next)->before.equals(next)?before:"MIXED");
      }
      rows.add(values);
    }
    return build("XLSX",Map.of("workbookSheets",count,"sheet",sheet.getSheetName(),"headerRow",header.getRowNum()+1,
        "rows",rows.size(),"columns",columns,"formulas","REJECTED","nativeTypes",nativeTypes),headers,rows);
  }
  private static String cellText(Cell cell,boolean header){if(cell==null)return "";if(cell.getCellType()==CellType.FORMULA)throw new IllegalArgumentException("XLSX formulas are not allowed");DataFormatter formatter=new DataFormatter(Locale.ROOT);String value=formatter.formatCellValue(cell);return header?value.trim():value;}
}
