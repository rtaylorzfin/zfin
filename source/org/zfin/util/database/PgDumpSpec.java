package org.zfin.util.database;



public class PgDumpSpec {
    public String description = """
Spec for a library that handles opening SQL dump files from postgresql, comparing them, saving diffs of them. It would enable someone to do this:

```java
PostgresDumpReader reader = new PostgresDumpReader(new File("dump.sql"));
PostgresDump dump = reader.getDump();
dump.getPrologue(); // returns the prologue of the dump file
dump.getTables(); // returns a list of table names
dump.getEpilogue(); // returns the epilogue of the dump file

List<PostgresDumpTable> tables = reader.getTables(); // returns a list of table names
PostgresDumpTable table = reader.getTable("table_name");
List<PostgresDumpColumn> columns = table.getColumns(); // returns a list of column names
List<PostgresDumpRow> rows = table.getRows(); // returns a list of rows
```

And a related class that can compare two dump files and tell you what's different between them. It would enable someone to do this:

```java
PostgresDumpReader reader1 = new PostgresDumpReader(new File("dump1.sql"));
PostgresDumpReader reader2 = new PostgresDumpReader(new File("dump2.sql"));
PostgresDumpDiff diff = new PostgresDumpDiff(reader1, reader2);
PostgresDumpTable = diff.getTablesAdded(); // returns a list of tables that are in dump2 but not in dump1
PostgresDumpTable = diff.getTablesRemoved(); // returns a list of tables that are in dump1 but not in dump2
List<PostgresDumpTableDiff> tableDiffs = diff.getTablesModified(); // returns a list of tables that are in both dumps but have different columns or rows

tableDiffs.getDiffType().equals(PostgresDumpTableDiffType.COLUMN); // returns true if the diff is a column diff (i.e. the structure of the table has changed and the columns are different)
tableDiffs.getDiffType().equals(PostgresDumpTableDiffType.ROW); // returns true if the diff is a row diff (i.e. the data in the table has changed)

if (tableDiffs.getDiffType().equals(PostgresDumpTableDiffType.COLUMN)) {
    PostgresDumpColumnDiff columnDiff = (PostgresDumpColumnDiff) tableDiffs.getDiff();
    columnDiff.getColumnsAdded(); // returns a list of columns that are in dump2 but not in dump1
    columnDiff.getColumnsRemoved(); // returns a list of columns that are in dump1 but not in dump2
}

if (tableDiffs.getDiffType().equals(PostgresDumpTableDiffType.ROW)) {
    PostgresDumpRowDiff rowDiff = (PostgresDumpRowDiff) tableDiffs.getDiff();
    rowDiff.getRowsAdded(); // returns a list of rows that are in dump2 but not in dump1
    rowDiff.getRowsRemoved(); // returns a list of rows that are in dump1 but not in dump2
}
```

Since some tables may have rows that are only different superficially, I want to be able to compare rows based on a subset of columns. For example, if a table has columns `id`, `name`, `age`, and `address`,
I want to be able to compare rows based on `name`, `age`, and `address` only.

I'm thinking it would be useful to be able to get the rows that are different when considering the full set of columns and also when considering a subset of columns. So I would like to be able to do something like this:

```java
PostgresDumpDiffConfig config = new PostgresDumpDiffConfig();
config.setRowComparisonColumns("table_name", Arrays.asList("name", "age", "address"));
PostgresDumpDiff diff = new PostgresDumpDiff(reader1, reader2, config);

List<PostgresDumpTableDiff> tableDiffs = diff.getTablesModified(); // returns a list of tables that are in both dumps but have different columns or rows
PostgresDumpTableDiff tableDiff = tableDiffs.get(0);

List<PostgresDumpRow> addedRows = tableDiff.getRowsAdded();
List<PostgresDumpRow> removedRows = tableDiff.getRowsRemoved();

List<PostgresDumpRow> uniqueAddedRows = tableDiff.getUniqueRowsAdded(); // returns a list of rows that are in dump2 but not in dump1 when comparing based on the subset of columns
List<PostgresDumpRow> uniqueRemovedRows = tableDiff.getUniqueRowsRemoved(); // returns a list of rows that are in dump1 but not in dump2 when comparing based on the subset of columns
```

I'd like to also be able to serialize and deserialize the `PostgresDump` and `PostgresDumpDiff` objects.
```java
PostgresDump dump = (new PostgresDumpReader(new File("dump.sql"))).getDump();
PostgresDumpWriter writer = new PostgresDumpWriter(new File("dump2.sql"));
writer.write(dump);

//both files should be the same:
assertEquals(-1, Files.mismatch(new File("dump.sql").toPath(), new File("dump2.sql").toPath()));
```

//diffs should be able to be used like patches
```java
PostgresDumpReader reader1 = new PostgresDumpReader(new File("dump1.sql"));
PostgresDumpReader reader2 = new PostgresDumpReader(new File("dump2.sql"));
PostgresDumpDiff diff = new PostgresDumpDiff(reader1, reader2);
PostgresDumpWriter writer = new PostgresDumpWriter(new File("dump1PlusDiff.sql"));
PostgresDump pgdump1 = reader1.getDump();
PostgresDump pgdump1PlusPatch = diff.apply(pgdump1);
writer.write(pgdump1PlusPatch);

//both files should be the same:
assertEquals(-1, Files.mismatch(new File("dump2.sql").toPath(), new File("dump1PlusDiff.sql").toPath()));
```


""";
}
