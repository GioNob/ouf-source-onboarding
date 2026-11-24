package it.comune.trieste.ouf.onboarding.application;

import static it.comune.trieste.ouf.onboarding.application.ManagedFileProfiler.*;
import java.util.*;
import java.io.*;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

final class XlsxFormatAdapter implements ManagedFileFormatAdapter {
  @Override public Profile profile(byte[] bytes){try(Workbook workbook=new XSSFWorkbook(new ByteArrayInputStream(bytes))){if(workbook.getNumberOfSheets()==0)throw new IllegalArgumentException("workbook has no sheets");Sheet sheet=workbook.getSheetAt(0);Row header=sheet.getRow(sheet.getFirstRowNum());if(header==null)throw new IllegalArgumentException("sheet has no header");int columns=header.getLastCellNum();if(columns<1||columns>MAX_COLUMNS)throw new IllegalArgumentException("column limit exceeded");List<String> headers=new ArrayList<>();for(int i=0;i<columns;i++)headers.add(cellText(header.getCell(i),true));validateHeaders(headers);List<List<String>> rows=new ArrayList<>();for(int r=header.getRowNum()+1;r<=sheet.getLastRowNum();r++){if(rows.size()>=MAX_ROWS)throw new IllegalArgumentException("row limit exceeded");Row row=sheet.getRow(r);if(row==null)continue;List<String> values=new ArrayList<>();for(int c=0;c<columns;c++)values.add(cellText(row.getCell(c),false));rows.add(values);}return build("XLSX",Map.of("workbookSheets",workbook.getNumberOfSheets(),"sheet",sheet.getSheetName(),"headerRow",header.getRowNum()+1,"rows",rows.size(),"columns",columns,"formulas","REJECTED"),headers,rows);}catch(IOException e){throw new IllegalArgumentException("invalid XLSX",e);}}
  private static String cellText(Cell cell,boolean header){if(cell==null)return "";if(cell.getCellType()==CellType.FORMULA)throw new IllegalArgumentException("XLSX formulas are not allowed");DataFormatter formatter=new DataFormatter(Locale.ROOT);String value=formatter.formatCellValue(cell);return header?CellWhitespace.trim(value):value;}
}
