package it.comune.trieste.ouf.managed;

import com.healthmarketscience.jackcess.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class AccessReaderTest {
  @TempDir Path directory;
  @Test void readsMdbAndAccdbWithCompositeKeysWithoutChangingInput() throws Exception {
    for(var format:List.of(Database.FileFormat.V2000,Database.FileFormat.V2010)) {
      Path file=directory.resolve(format.name()+".db");
      try(var db=DatabaseBuilder.create(format,file.toFile())) {
        var table=new TableBuilder("Assets").addColumn(new ColumnBuilder("zone",DataType.TEXT))
            .addColumn(new ColumnBuilder("id",DataType.LONG)).addColumn(new ColumnBuilder("name",DataType.TEXT))
            .addIndex(new IndexBuilder("pk").addColumns("zone","id").setPrimaryKey()).toTable(db);
        table.addRow("Z1",7,"È un oggetto");
      }
      byte[] bytes=Files.readAllBytes(file),original=bytes.clone();
      try(var reader=new AccessReader(bytes)) {
        var info=reader.tables().getFirst();assertEquals(List.of("zone","id"),info.primaryKey());
        assertEquals("È un oggetto",reader.rows("Assets",List.of("id","name")).getFirst().get("name"));
        assertFalse(reader.rows("Assets",List.of("id")).getFirst().containsKey("name"));
        assertThrows(IllegalArgumentException.class,()->reader.rows("Assets",List.of("missing")));
      }
      assertArrayEquals(original,bytes);assertArrayEquals(original,Files.readAllBytes(file));
    }
  }
  @Test void rejectsLinkedDatabaseBeforeOpeningExternalFile() throws Exception {
    Path file=directory.resolve("linked.mdb");
    try(var db=DatabaseBuilder.create(Database.FileFormat.V2000,file.toFile())){db.createLinkedTable("remote","/must-not-open/external.mdb","External");}
    assertThrows(IllegalArgumentException.class,()->new AccessReader(Files.readAllBytes(file)));
  }
  @Test void rejectsCorruptOrOversizedContent() {
    assertThrows(IllegalArgumentException.class,()->new AccessReader(new byte[]{1,2,3}));
    assertThrows(IllegalArgumentException.class,()->new AccessReader(new byte[AccessReader.MAX_BYTES+1]));
  }
}
