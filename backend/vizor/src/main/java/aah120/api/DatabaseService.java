package aah120.api;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import aah120.dto.Column;
import aah120.dto.DatabaseDetails;
import aah120.dto.ForeignKey;
import aah120.dto.DFRequest;
import aah120.dto.DFResponse;
import aah120.dto.TableMetadata;
import aah120.dto.VFRequest;
import aah120.dto.VFResponse;
import aah120.dto.VisualisationOption;

@Service
public class DatabaseService {

  List<String> NUM_TYPES = List.of("numeric", "int2", "int4", "int8", "float4", "float8");
  List<String> TEMP_TYPES = List.of("date", "time", "timestamp");
  List<String> LEX_TYPES = List.of("varchar", "text", "char");
  List<String> GEO_TABLE_NAMES = List.of("country", "city", "state", "county", "province");
  List<String> GEO_COLUMN_NAMES = List.of("name", "code", "id");

  List<String> BASIC_VIS_TYPES = List.of("bar", "calendar", "scatter", "bubble", "choropleth", "word-cloud");
  List<String> WEAK_VIS_TYPES = List.of("line", "stacked-bar", "grouped-bar", "spider");
  List<String> ONE_MANY_VIS_TYPES = List.of("treemap", "hierarchy-tree", "circle-packing");
  List<String> MANY_MANY_VIS_TYPES = List.of("sankey");
  List<String> REFLEXIVE_VIS_TYPES = List.of("chord");

  private final DatabaseConnectionManager connectionManager;
  private final List<TableMetadata> databaseMetadata;

  public DatabaseService(DatabaseConnectionManager connectionManager) {
    this.connectionManager = connectionManager;
    this.databaseMetadata = new ArrayList<>();
  }

  public void setConnectionDetails(DatabaseDetails databaseDetails) {
    connectionManager.setConnectionDetails(
        databaseDetails.getHost(),
        databaseDetails.getPort(),
        databaseDetails.getDatabaseName(),
        databaseDetails.getUsername(),
        databaseDetails.getPassword());
  }

  public void setDatabaseMetadata(List<TableMetadata> tables) {
    this.databaseMetadata.clear();
    this.databaseMetadata.addAll(tables);
  }

  public List<TableMetadata> fetchTableMetadata() throws SQLException {

    try (Connection connection = connectionManager.getConnection()) {
      DatabaseMetaData metaData = connection.getMetaData();
      ResultSet tablesRs = metaData.getTables(null, "public", "%", new String[] { "TABLE" });
      List<TableMetadata> tables = new ArrayList<>();

      while (tablesRs.next()) {
        String tableName = tablesRs.getString("TABLE_NAME");

        ResultSet primaryKeysRs = metaData.getPrimaryKeys(null, "public", tableName);
        List<String> primaryKeys = new ArrayList<>();
        while (primaryKeysRs.next()) {
          primaryKeys.add(primaryKeysRs.getString("COLUMN_NAME"));
        }

        ResultSet foreignKeysRs = metaData.getImportedKeys(null, "public", tableName);
        List<ForeignKey> foreignKeys = new ArrayList<>();
        while (foreignKeysRs.next()) {
          String parentTable = foreignKeysRs.getString("PKTABLE_NAME");
          String parentColumn = foreignKeysRs.getString("PKCOLUMN_NAME");
          String childTable = foreignKeysRs.getString("FKTABLE_NAME");
          String childColumn = foreignKeysRs.getString("FKCOLUMN_NAME");
          foreignKeys.add(new ForeignKey(parentTable, parentColumn, childTable, childColumn));
        }

        ResultSet columnsRs = metaData.getColumns(null, "public", tableName, "%");
        List<Column> columns = new ArrayList<>();
        while (columnsRs.next()) {
          String colName = columnsRs.getString("COLUMN_NAME");
          String colType = columnsRs.getString("TYPE_NAME");
          Column col = new Column(colName, colType, tableName);

          if (primaryKeys.contains(colName)) {
            col.setPrimaryKey(true);
          }

          if (foreignKeys.stream().anyMatch(fk -> fk.getChildColumn().equals(colName))) {
            col.setForeignKey(true);
          }

          columns.add(col);
        }

        tables.add(new TableMetadata(tableName, columns, primaryKeys, foreignKeys));
      }

      setDatabaseMetadata(tables);
      return tables;
    } catch (SQLException e) {
      e.printStackTrace();
      throw e;
    }
  }

  public DFResponse recommendVisualisations(DFRequest request) throws SQLException {

    List<String> tableNames = request.getTableNames();
    List<String> fullColumnNames = request.getFullColumnNames();
    List<String> columnNames = fullColumnNames.stream().map(col -> col.split("\\.")[1]).toList();

    List<TableMetadata> tables = new ArrayList<>();
    List<Column> columns = new ArrayList<>();
    String pattern = null; // one of basic, weak, one-many, or many-many
    List<VisualisationOption> visOptions = new ArrayList<>(); // one of graph choices

    for (TableMetadata table : databaseMetadata) {
      if (tableNames.contains(table.getTableName())) {
        tables.add(table);
        for (Column column : table.getColumns()) {
          if (columnNames.contains(column.getName())) {
            columns.add(column);
          }
        }
      }
    }

    int numPks = (int) columns.stream().filter(Column::isPrimaryKey).count();
    int numPureFks = (int) columns.stream().filter(col -> col.isForeignKey() && !col.isPrimaryKey()).count();
    int totalFks = (int) columns.stream().filter(col -> col.isForeignKey()).count();
    int numAtts = columns.size() - numPks - numPureFks;

    List<Column> chosenPks = columns.stream().filter(Column::isPrimaryKey).toList();
    List<Column> chosenFks = columns.stream().filter(col -> col.isForeignKey()).toList();
    List<Column> chosenAtts = columns.stream().filter(col -> !chosenPks.contains(col) && !chosenFks.contains(col))
        .toList();

    List<String> chosenPkNames = chosenPks.stream().map(Column::getName).toList();
    List<String> chosenFkNames = chosenFks.stream().map(Column::getName).toList();
    List<String> chosenAttNames = chosenAtts.stream().map(Column::getName).toList();

    // List<String> chosenPkTypes = chosenPks.stream().map(Column::getType).toList();
    // List<String> chosenFkTypes = chosenFks.stream().map(Column::getType).toList();
    List<String> chosenAttTypes = chosenAtts.stream().map(Column::getType).toList();

    if (numAtts == 0) {
      pattern = "none";
    } else if (numPks == 0 && numPureFks == 0) {
      pattern = "none";
    } else if (isBasicEntity(numPks, totalFks, tables, columns)) {
      pattern = "basic";

      Column key = numPks == 0 ? chosenFks.get(0) : chosenPks.get(0);
      String keyName = numPks == 0 ? String.join(" | ", chosenFkNames) : chosenPkNames.get(0);

      if (bar(chosenAttTypes)) {
        visOptions.add(new VisualisationOption("bar", "Bar Chart", keyName, "", chosenAttNames, ""));
      }
      if (calendar(chosenAttTypes)) {
        visOptions.add(new VisualisationOption("calendar", "Calendar", keyName, "", chosenAttNames, ""));
      }
      if (scatter(chosenAttTypes)) {
        visOptions.add(new VisualisationOption("scatter", "Scatter Chart", keyName, "", chosenAttNames, ""));
      }
      if (bubble(chosenAttTypes)) {
        visOptions.add(new VisualisationOption("bubble", "Bubble Chart", keyName, "", chosenAttNames, ""));
      }
      if (choropleth(key, chosenAttTypes)) {
        visOptions.add(new VisualisationOption("choropleth", "Choropleth Map", keyName, "", chosenAttNames, ""));
      }
      if (wordCloud(key, chosenAttTypes)) {
        visOptions.add(new VisualisationOption("word-cloud", "Word Cloud", keyName, "", chosenAttNames, ""));
      }
    } else if (isWeakEntity(tables, columns)) {
      pattern = "weak";

      String key1 = String.join(" | ", chosenFkNames);
      String key2 = chosenPks.stream().filter(pk -> !pk.isForeignKey()).map(Column::getName).findFirst().get();

      if (line(chosenPks, chosenAttTypes)) {
        visOptions.add(new VisualisationOption("line", "Line Chart", key1, key2, chosenAttNames, ""));
      }
      if (stackedBar(chosenAttTypes)) {
        visOptions.add(new VisualisationOption("stacked-bar", "Stacked Bar Chart", key1, key2, chosenAttNames, ""));
      }
      if (groupedBar(chosenAttTypes)) {
        visOptions.add(new VisualisationOption("grouped-bar", "Grouped Bar Chart", key1, key2, chosenAttNames, ""));
      }
      if (spider(chosenAttTypes)) {
        visOptions.add(new VisualisationOption("spider", "Spider Chart", key1, key2, chosenAttNames, ""));
      }
    } else if (isOneManyRelationship(numPks, numPureFks, tables, columns)) {
      pattern = "one-many";

      String key1 = String.join(" | ", chosenFkNames.stream().filter(fk -> !chosenPkNames.contains(fk)).toList());
      String key2 = chosenPkNames.get(0);

      if (treemap(chosenAttTypes)) {
        visOptions.add(new VisualisationOption("treemap", "Treemap", key1, key2, chosenAttNames, ""));
      }
      if (hierarchyTree(chosenAttTypes)) {
        visOptions.add(new VisualisationOption("hierarchy-tree", "Hierarchy Tree", key1, key2, chosenAttNames, ""));
      }
      if (circlePacking(chosenAttTypes)) {
        visOptions.add(new VisualisationOption("circle-packing", "Circle Packing", key1, key2, chosenAttNames, ""));
      }
    } else if (isManyManyRelationship(numPks, tables)) {
      if (isReflexive(tables)) {
        pattern = "reflexive";

        if (chord(chosenAttTypes)) {
          visOptions.add(new VisualisationOption("chord", "Chord Diagram", chosenPkNames.get(0), chosenPkNames.get(1),
              chosenAttNames, ""));
        }
      } else {
        pattern = "many-many";

        if (sankey(chosenAttTypes)) {
          visOptions.add(new VisualisationOption("sankey", "Sankey", chosenPkNames.get(0), chosenPkNames.get(1),
              chosenAttNames, ""));
        }
      }
    } else {
      pattern = "none";
    }

    // build and execute query to get data
    List<Map<String, Object>> data = new ArrayList<>();
    try (Connection connection = connectionManager.getConnection()) {

      String queryStr = "";

      if (pattern.equals("basic")) {
        queryStr = generateBasicQuery(tableNames, columnNames, numPks, chosenPkNames, chosenFkNames,
            chosenAttNames);
      } else if (pattern.equals("weak")) {
        queryStr = generateWeakQuery(tableNames, columnNames, chosenPkNames, chosenFkNames, chosenAttNames);
      } else if (pattern.equals("one-many")) {
        queryStr = generateOneManyQuery(tableNames, columnNames, chosenPkNames, chosenFkNames, chosenAttNames);
      } else {
        queryStr = generateRegularQuery(tableNames, columnNames);
      }

      System.out.println(queryStr);

      PreparedStatement preparedStatement = connection.prepareStatement(queryStr);
      ResultSet resultSet = preparedStatement.executeQuery();

      ResultSetMetaData metaData = resultSet.getMetaData();
      int columnCount = metaData.getColumnCount();

      while (resultSet.next()) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 1; i <= columnCount; i++) {
          row.put(metaData.getColumnName(i), resultSet.getObject(i));
        }

        data.add(row);
      }

    } catch (SQLException e) {
      e.printStackTrace();
      throw e;
    }

    return new DFResponse(pattern, visOptions, data);
  }

  private String generateRegularQuery(List<String> tableNames, List<String> columnNames) {

    System.out.println("Generating regular query");

    StringBuilder sb = new StringBuilder();

    sb.append("SELECT ");

    for (int i = 0; i < columnNames.size(); i++) {
      sb.append(columnNames.get(i));
      if (i == columnNames.size() - 1) {
        sb.append(" ");
      } else {
        sb.append(", ");
      }
    }

    sb.append("FROM ");
    sb.append(String.join(", ", tableNames));
    sb.append(" WHERE ");

    sb.append(String.join(" AND ", columnNames.stream().map(c -> c + " IS NOT NULL").toList()));
    sb.append(";");

    return sb.toString();
  }

  private String generateBasicQuery(List<String> tableNames, List<String> columnNames, int numPks,
      List<String> chosenPkNames, List<String> chosenFkNames, List<String> chosenAttNames) {

    System.out.println("Generating basic query");

    StringBuilder sb = new StringBuilder();

    sb.append("SELECT ");

    if (numPks == 0) {
      sb.append(String.join(" || ' | ' || ", chosenFkNames)).append(" AS ").append("\"")
          .append(String.join(" | ", chosenFkNames)).append("\"");
      sb.append(", ").append(
          String.join(", ", chosenAttNames.stream().map(att -> "SUM(" + att + ")" + " AS " + att).toList()));
    } else {
      sb.append(String.join(", ", chosenPkNames));
      sb.append(", ").append(String.join(", ", chosenAttNames));
    }

    sb.append(" FROM ");
    sb.append(String.join(", ", tableNames));

    sb.append(" WHERE ");
    sb.append(String.join(" AND ", columnNames.stream().map(c -> c + " IS NOT NULL").toList()));

    if (numPks == 0) {
      sb.append(" GROUP BY ").append(String.join(", ", chosenFkNames));
      sb.append(" ORDER BY ").append(String.join(", ", chosenFkNames));
    }

    sb.append(";");

    return sb.toString();
  }

  private String generateWeakQuery(List<String> tableNames, List<String> columnNames, List<String> chosenPkNames,
      List<String> chosenFkNames, List<String> chosenAttNames) {

    System.out.println("Generating weak query");

    StringBuilder sb = new StringBuilder();

    sb.append("SELECT ");
    sb.append(String.join(" || ' | ' || ", chosenFkNames)).append(" AS ").append("\"")
        .append(String.join(" | ", chosenFkNames)).append("\"");
    sb.append(", ")
        .append(String.join(", ", chosenPkNames.stream().filter(pk -> !chosenFkNames.contains(pk)).toList()));
    sb.append(", ").append(
        String.join(", ", chosenAttNames.stream().map(att -> "SUM(" + att + ")" + " AS " + att).toList()));

    sb.append(" FROM ");
    sb.append(String.join(", ", tableNames));

    sb.append(" WHERE ");
    sb.append(String.join(" AND ", columnNames.stream().map(c -> c + " IS NOT NULL").toList()));

    sb.append(" GROUP BY ")
        .append(String.join(", ", chosenPkNames.stream().filter(pk -> !chosenFkNames.contains(pk)).toList()))
        .append(", ").append(String.join(", ", chosenFkNames));
    sb.append(" ORDER BY ")
        .append(String.join(", ", chosenPkNames.stream().filter(pk -> !chosenFkNames.contains(pk)).toList()))
        .append(", ").append(String.join(", ", chosenFkNames));

    sb.append(";");

    return sb.toString();
  }

  private String generateOneManyQuery(List<String> tableNames, List<String> columnNames, List<String> chosenPkNames,
      List<String> chosenFkNames, List<String> chosenAttNames) {

    System.out.println("Generating one-many query");

    StringBuilder sb = new StringBuilder();

    List<String> chosenPureFks = chosenFkNames.stream().filter(fk -> !chosenPkNames.contains(fk)).toList();

    sb.append("SELECT ");
    sb.append(String.join(" || ' | ' || ", chosenPureFks)).append(" AS ")
        .append("\"").append(String.join(" | ", chosenPureFks)).append("\"");
    sb.append(", ").append(String.join(", ", chosenPkNames));
    sb.append(", ").append(
        String.join(", ", chosenAttNames.stream().map(att -> "ABS(" + att + ")" + " AS " + att).toList()));

    sb.append(" FROM ");
    sb.append(String.join(", ", tableNames));

    sb.append(" WHERE ");
    sb.append(String.join(" AND ", columnNames.stream().map(c -> c + " IS NOT NULL").toList()));

    sb.append(" ORDER BY ").append(String.join(", ", chosenAttNames));
    sb.append(" DESC ");

    sb.append(";");

    return sb.toString();
  }

  private boolean isScalarType(String type) {
    return NUM_TYPES.contains(type) || TEMP_TYPES.contains(type);
  }

  private boolean bar(List<String> attTypes) {
    return attTypes.size() == 1 && isScalarType(attTypes.get(0));
  }

  private boolean calendar(List<String> attTypes) {
    return attTypes.size() == 1 && TEMP_TYPES.contains(attTypes.get(0));
  }

  private boolean wordCloud(Column key, List<String> attTypes) {
    return attTypes.size() == 1 && LEX_TYPES.contains(key.getType())
        && isScalarType(attTypes.get(0));
  }

  private boolean scatter(List<String> attTypes) {
    return attTypes.size() == 2 && attTypes.stream().allMatch(this::isScalarType);
  }

  private boolean bubble(List<String> attTypes) {
    return attTypes.size() == 3 && attTypes.stream().allMatch(this::isScalarType);
  }

  private boolean choropleth(Column key, List<String> attTypes) {
    boolean geographicalKey = GEO_TABLE_NAMES.contains(key.getName())
        || (GEO_TABLE_NAMES.contains(key.getTableName()) && GEO_COLUMN_NAMES.contains(key.getName()));

    return geographicalKey && attTypes.size() == 1;
  }

  private boolean line(List<Column> keys, List<String> attTypes) {
    if (attTypes.size() != 1) {
      return false;
    }

    List<Column> k2 = keys.stream().filter(k -> !k.isForeignKey()).toList();

    if (k2.stream().anyMatch(k -> !isScalarType(k.getType()))) {
      return false;
    }

    return attTypes.stream().allMatch(this::isScalarType);
  }

  private boolean stackedBar(List<String> attTypes) {
    return attTypes.size() == 1 && isScalarType(attTypes.get(0));
  }

  private boolean groupedBar(List<String> attTypes) {
    return attTypes.size() == 1 && isScalarType(attTypes.get(0));
  }

  private boolean spider(List<String> attTypes) {
    return attTypes.size() == 1 && isScalarType(attTypes.get(0));
  }

  private boolean treemap(List<String> attTypes) {
    return attTypes.size() == 1 && isScalarType(attTypes.get(0));
  }

  private boolean hierarchyTree(List<String> attTypes) {
    return attTypes.size() == 0;
  }

  private boolean circlePacking(List<String> attTypes) {
    return attTypes.size() == 1 && isScalarType(attTypes.get(0));
  }

  private boolean sankey(List<String> attTypes) {
    return attTypes.size() == 1 && isScalarType(attTypes.get(0));
  }

  private boolean chord(List<String> attTypes) {
    return attTypes.size() == 1 && isScalarType(attTypes.get(0));
  }

  private boolean isBasicEntity(int numPks, int totalFks, List<TableMetadata> tables, List<Column> columns) {

    if (numPks > 1) {
      return false;
    }

    if (totalFks == 0) {
      return true;
    }

    // assume only single table used - NEED TO UPDATE FOR MULTIPLE TABLES
    TableMetadata table = tables.get(0);

    boolean inheritedPk = table.getForeignKeys().stream().map(ForeignKey::getChildColumn).toList()
        .containsAll(table.getPrimaryKeys());

    List<String> chosenFkNotPks = columns.stream().filter(col -> col.isForeignKey() && !col.isPrimaryKey())
        .map(Column::getName).toList();

    if ((inheritedPk && chosenFkNotPks.size() == 0) || table.getPrimaryKeys().size() == 0) {
      return true;
    }

    return false;
  }

  private boolean isWeakEntity(List<TableMetadata> tables, List<Column> columns) {

    // assume only single table used - NEED TO UPDATE FOR MULTIPLE TABLES
    TableMetadata table = tables.get(0);

    List<String> bothPkAndFk = columns.stream().filter(col -> col.isPrimaryKey() && col.isForeignKey())
        .map(Column::getName).toList();

    if (bothPkAndFk.size() == 0) {
      return false;
    }

    // parent pks NEED to be subset of all pks else reflexive many-many
    if (bothPkAndFk.size() == table.getPrimaryKeys().size()) {
      return false;
    }

    List<ForeignKey> chosenPkFks = table.getForeignKeys().stream()
        .filter(fk -> bothPkAndFk.contains(fk.getChildColumn())).toList();

    // check they all have same parent (within single foreign key)
    if (chosenPkFks.stream().map(ForeignKey::getParentTable).distinct().count() == 1) {
      return true;
    }

    return false;
  }

  private boolean isOneManyRelationship(int numPks, int numPureFks, List<TableMetadata> tables,
      List<Column> columns) {

    // assume only single table used - NEED TO UPDATE FOR MULTIPLE TABLES
    TableMetadata table = tables.get(0);

    if (numPureFks == 0 || numPks == 0) {
      return false;
    }

    List<String> chosenPureFks = columns.stream().filter(col -> col.isForeignKey() && !col.isPrimaryKey())
        .map(Column::getName).toList();
    List<ForeignKey> chosenFkObjs = table.getForeignKeys().stream()
        .filter(fk -> chosenPureFks.contains(fk.getChildColumn())).toList();

    if (chosenFkObjs.size() != chosenPureFks.size()) {
      return false;
    }

    if (chosenFkObjs.stream().map(ForeignKey::getParentTable).distinct().count() != 1) {
      return false;
    }

    return true;
  }

  private boolean isManyManyRelationship(int numPks, List<TableMetadata> tables) {

    if (numPks != 2) {
      return false;
    }

    // assume only single table used - NEED TO UPDATE FOR MULTIPLE TABLES
    TableMetadata table = tables.get(0);

    if (table.getPrimaryKeys().size() != 2) {
      return false;
    }

    if (!(table.getForeignKeys().stream().map(ForeignKey::getChildColumn).toList()
        .containsAll(table.getPrimaryKeys())
        && table.getPrimaryKeys()
            .containsAll(table.getForeignKeys().stream().map(ForeignKey::getChildColumn).toList()))) {
      return false;
    }

    return true;
  }

  private boolean isReflexive(List<TableMetadata> tables) {

    // assume only single table used - NEED TO UPDATE FOR MULTIPLE TABLES
    TableMetadata table = tables.get(0);

    return table.getForeignKeys().stream().map(ForeignKey::getParentTable).distinct().count() == 1;
  }

  // public List<Map<String, Object>> executeQuery(DFRequest query) throws
  // SQLException {

  // try (Connection connection = connectionManager.getConnection()) {

  // String tableNames = String.join(", ", query.getTableNames());
  // String columnNames = String.join(", ", query.getFullColumnNames());

  // System.out.println("Received query request for table: " + tableNames + " and
  // columns: " + columnNames);

  // String queryStr = "SELECT " + columnNames + " FROM " + tableNames;

  // System.out.println(queryStr);

  // PreparedStatement preparedStatement = connection.prepareStatement(queryStr);
  // ResultSet resultSet = preparedStatement.executeQuery();

  // List<Map<String, Object>> results = new ArrayList<>();
  // ResultSetMetaData metaData = resultSet.getMetaData();
  // int columnCount = metaData.getColumnCount();

  // while (resultSet.next()) {
  // Map<String, Object> row = new LinkedHashMap<>();
  // for (int i = 1; i <= columnCount; i++) {
  // row.put(metaData.getColumnName(i), resultSet.getObject(i));
  // }

  // results.add(row);
  // }

  // return results;
  // } catch (SQLException e) {
  // e.printStackTrace();
  // throw e;
  // }
  // }

  public VFResponse vfGenerateOptions(VFRequest request) {

    List<VisualisationOption> options = new ArrayList<>();

    String vis = request.getVisId();
    String tableName = request.getTable();

    TableMetadata table = databaseMetadata.stream().filter(t -> t.getTableName().equals(tableName)).findFirst()
        .get();
    List<Column> columns = table.getColumns();

    List<Column> pks = columns.stream().filter(Column::isPrimaryKey).toList();
    List<Column> fks = columns.stream().filter(col -> col.isForeignKey()).toList();
    List<Column> atts = columns.stream().filter(col -> !col.isPrimaryKey() && !col.isForeignKey()).toList();

    String pattern = "none";

    if (BASIC_VIS_TYPES.contains(vis)) {
      pattern = "basic";

      String keyName = "";

      if (pks.size() == 0) {
        keyName = String.join(" | ", fks.stream().map(Column::getName).toList());
      } else {
        keyName = pks.get(0).getName();
      }

      switch (vis) {
        case "bar":
          for (Column att : atts) {
            if (isScalarType(att.getType())) {
              String title = keyName + " vs " + att.getName();
              options.add(new VisualisationOption("bar", "Bar Chart", keyName, "", List.of(att.getName()),
                  title));
            }
          }
          break;
        case "calendar":
          for (Column att : atts) {
            if (TEMP_TYPES.contains(att.getType())) {
              String title = keyName + " by " + att.getName();
              options.add(new VisualisationOption("calendar", "Calendar", keyName, "",
                  List.of(att.getName()), title));
            }
          }
          break;
        case "scatter":
          for (int i = 0; i < atts.size() - 1; i++) {
            for (int j = 1; j < atts.size(); j++) {
              if (isScalarType(atts.get(i).getType()) && isScalarType(atts.get(j).getType()) && i != j) {
                String title = atts.get(i).getName() + " vs " + atts.get(j).getName();
                options.add(new VisualisationOption("scatter", "Scatter Chart", keyName, "",
                    List.of(atts.get(i).getName(), atts.get(j).getName()), title));
              }

            }
          }
          break;
        case "bubble":
          for (int i = 0; i < atts.size() - 2; i++) {
            for (int j = 1; j < atts.size() - 1; j++) {
              for (int k = 0; k < atts.size(); k++) {
                if (isScalarType(atts.get(i).getType()) && isScalarType(atts.get(j).getType())
                    && isScalarType(atts.get(k).getType()) && i != j && j != k && i != k) {
                  String title = atts.get(i).getName() + " vs " + atts.get(j).getName() + ", sized by "
                      + atts.get(k).getName();
                  options.add(new VisualisationOption("bubble", "Bubble Chart", keyName, "",
                      List.of(atts.get(i).getName(), atts.get(j).getName(),
                          atts.get(k).getName()),
                      title));
                }

              }
            }
          }
          break;
        case "choropleth":
          for (Column att : atts) {
            String title = att.getName();
            options.add(new VisualisationOption("choropleth", "Choropleth Map", keyName, "",
                List.of(att.getName()), title));
          }
          break;
        case "word-cloud":
          for (Column att : atts) {
            if (isScalarType(att.getType())) {
              String title = pks.get(0).getName() + ", sized by " + att.getName();
              options.add(new VisualisationOption("word-cloud", "Word Cloud", keyName, "",
                  List.of(att.getName()), title));
            }
          }
          break;
      }

    } else if (WEAK_VIS_TYPES.contains(vis)) {
      pattern = "weak";

      String concattedFkPkName = String.join(" | ", fks.stream().map(Column::getName).toList());

      List<Column> purePks = pks.stream().filter(pk -> !pk.isForeignKey()).toList();

      switch (vis) {
        case "line":
          for (Column pk : purePks) {
            if (isScalarType(pk.getType())) {
              for (Column att : atts) {
                if (isScalarType(att.getType())) {
                  String title = pk.getName() + " vs " + att.getName() + ", for each " + concattedFkPkName;
                  options.add(new VisualisationOption("line", "Line Chart", concattedFkPkName, pk.getName(),
                      List.of(att.getName()), title));
                }
              }
            }
          }
          break;
        case "stacked-bar":
          for (Column att : atts) {
            if (isScalarType(att.getType())) {
              String title = purePks.get(0).getName() + " vs " + att.getName() + ", for each " + concattedFkPkName;
              options.add(
                  new VisualisationOption("stacked-bar", "Stacked Bar Chart", concattedFkPkName,
                      purePks.get(0).getName(),
                      List.of(att.getName()), title));
            }
          }
          break;
        case "grouped-bar":
          for (Column att : atts) {
            if (isScalarType(att.getType())) {
              String title = purePks.get(0).getName() + " vs " + att.getName()
                  + ", for each " + concattedFkPkName;
              options.add(
                  new VisualisationOption("grouped-bar", "Grouped Bar Chart", concattedFkPkName,
                      purePks.get(0).getName(),
                      List.of(att.getName()), title));
            }
          }
          break;
        case "spider":
          for (Column att : atts) {
            if (isScalarType(att.getType())) {
              String title = purePks.get(0).getName() + " vs " + att.getName() + ", for each " + concattedFkPkName;
              options.add(new VisualisationOption("spider", "Spider Chart", concattedFkPkName, purePks.get(0).getName(),
                  List.of(att.getName()), title));
            }
          }
          break;
      }

    } else if (ONE_MANY_VIS_TYPES.contains(vis)) {
      pattern = "one-many";

      List<String> pureFks = fks.stream().filter(fk -> !pks.contains(fk)).map(Column::getName).toList();
      List<String> parentTables = table.getForeignKeys().stream().filter(fk -> pureFks.contains(fk.getChildColumn()))
          .map(ForeignKey::getParentTable).distinct()
          .toList();

      List<String> concattedFkNames = new ArrayList<>();

      for (String parentTable : parentTables) {
        List<String> children = table.getForeignKeys().stream()
            .filter(
                fk -> !table.getPrimaryKeys().contains(fk.getChildColumn()) && fk.getParentTable().equals(parentTable))
            .map(ForeignKey::getChildColumn).toList();
        String concattedFkName = String.join(" | ", children);
        concattedFkNames.add(concattedFkName);
      }

      switch (vis) {
        case "hierarchy-tree":
          for (String fkName : concattedFkNames) {
            String title = pks.get(0).getName() + ", for each " + fkName;
            options.add(new VisualisationOption("hierarchy-tree", "Hierarchy Tree", fkName, pks.get(0).getName(),
                List.of(), title));
          }
          break;
        case "treemap":
          for (String fkName : concattedFkNames) {
            for (Column att : atts) {
              if (isScalarType(att.getType())) {
                String title = pks.get(0).getName() + " vs " + att.getName() + ", for each " + fkName;
                options.add(new VisualisationOption("treemap", "Treemap", fkName, pks.get(0).getName(),
                    List.of(att.getName()), title));
              }
            }
          }
          break;
        case "circle-packing":
          for (String fkName : concattedFkNames) {
            for (Column att : atts) {
              if (isScalarType(att.getType())) {
                String title = pks.get(0).getName() + " vs " + att.getName() + ", for each " + fkName;
                options.add(new VisualisationOption("circle-packing", "Circle Packing", fkName, pks.get(0).getName(),
                    List.of(att.getName()), title));
              }
            }
          }
          break;
      }
    } else if (MANY_MANY_VIS_TYPES.contains(vis)) {
      pattern = "many-many";

      switch (vis) {
        case "sankey":
          String title = "Sankey Diagram: " + pks.get(0).getName() + " to " + pks.get(1).getName() + ", sized by "
              + atts.get(0).getName();
          for (Column att : atts) {
            if (isScalarType(att.getType())) {
              options.add(new VisualisationOption("sankey", "Sankey Diagram", pks.get(0).getName(),
                  pks.get(1).getName(), List.of(att.getName()), title));
            }
          }
          break;
      }

    } else if (REFLEXIVE_VIS_TYPES.contains(vis)) {
      pattern = "reflexive";

      switch (vis) {
        case "chord":
          String title = pks.get(0).getName() + " to " + pks.get(1).getName() + ", sized by " + atts.get(0).getName();
          for (Column att : atts) {
            if (isScalarType(att.getType())) {
              options.add(new VisualisationOption("chord", "Chord Diagram", pks.get(0).getName(), pks.get(1).getName(),
                  List.of(att.getName()), title));
            }
          }
          break;
      }
    }

    return new VFResponse(pattern, options);
  }

  public List<Map<String, Object>> vfExecuteQuery(DFRequest request) {

    List<Map<String, Object>> data = new ArrayList<>();

    String pattern = request.getPattern();
    List<String> tableNames = request.getTableNames();
    List<String> columnNames = request.getFullColumnNames();

    List<TableMetadata> tables = new ArrayList<>();
    List<Column> columns = new ArrayList<>();

    for (TableMetadata table : databaseMetadata) {
      if (tableNames.contains(table.getTableName())) {
        tables.add(table);
        for (Column column : table.getColumns()) {
          if (columnNames.contains(column.getName())) {
            columns.add(column);
          }
        }
      }
    }

    int numPks = (int) columns.stream().filter(Column::isPrimaryKey).count();

    List<String> chosenPkNames = columns.stream().filter(Column::isPrimaryKey).map(Column::getName).toList();
    List<String> chosenFkNames = columns.stream().filter(Column::isForeignKey).map(Column::getName).toList();
    List<String> chosenAttNames = columns.stream().map(Column::getName)
        .filter(col -> !chosenPkNames.contains(col) && !chosenFkNames.contains(col)).toList();

    try (Connection connection = connectionManager.getConnection()) {

      String queryStr = "";

      if (pattern.equals("basic")) {
        queryStr = generateBasicQuery(tableNames, columnNames, numPks, chosenPkNames, chosenFkNames,
            chosenAttNames);
      } else if (pattern.equals("weak")) {
        queryStr = generateWeakQuery(tableNames, columnNames, chosenPkNames, chosenFkNames, chosenAttNames);
      } else if (pattern.equals("one-many")) {
        queryStr = generateOneManyQuery(tableNames, columnNames, chosenPkNames, chosenFkNames, chosenAttNames);
      } else {
        queryStr = generateRegularQuery(tableNames, columnNames);
      }

      System.out.println(queryStr);

      PreparedStatement preparedStatement = connection.prepareStatement(queryStr);
      ResultSet resultSet = preparedStatement.executeQuery();

      ResultSetMetaData metaData = resultSet.getMetaData();
      int columnCount = metaData.getColumnCount();

      while (resultSet.next()) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 1; i <= columnCount; i++) {
          row.put(metaData.getColumnName(i), resultSet.getObject(i));
        }

        data.add(row);
      }

    } catch (SQLException e) {
      e.printStackTrace();
    }

    return data;
  }
}