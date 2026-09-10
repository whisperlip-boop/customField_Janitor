package com.bskim.jira.janitor.fields.dao;

import com.atlassian.jira.component.ComponentAccessor;
import com.atlassian.jira.database.ConnectionFunction;
import com.atlassian.jira.database.DatabaseAccessor;
import com.atlassian.jira.database.DatabaseConnection;
import com.bskim.jira.janitor.fields.deep.DeepScanPolicy;
import com.bskim.jira.janitor.fields.deep.DeepTable;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

/**
 * 앱 테이블({@code AO_*})을 직접 훑는 심층 스캔의 SQL. 일반 스캔의 SQL과 성격이
 * 달라 {@link JanitorDao} 와 나눴다 — 여기는 <b>어떤 테이블이 있는지도 모르는 채</b>
 * {@code DatabaseMetaData} 로 스키마를 읽어 쿼리를 만든다.
 *
 * <p>실측과 리뷰에서 나온 규칙(docs/00 35·36·39번):
 * <ul>
 *   <li><b>식별자는 반드시 인용한다.</b> AO 테이블 이름은 대문자라 PostgreSQL 에서
 *       인용 없이 쓰면 소문자로 접혀 실패한다. 인용 문자는 DB마다 달라
 *       {@code getIdentifierQuoteString()} 으로 얻는다.</li>
 *   <li><b>스키마를 붙인다.</b> {@code JanitorDao.table()} 과 같은 값이다. 안 붙이면
 *       {@code schema-name} 이 search_path 에 없는 PostgreSQL 에서 모든 테이블이
 *       "relation does not exist"로 죽고, 결과는 "테이블 200개를 훑었는데 0건"으로
 *       <b>완전한 것처럼</b> 보인다.</li>
 *   <li><b>메타데이터 패턴의 이스케이프는 드라이버에게 묻는다.</b> Oracle 은 {@code /} 다.
 *       {@code \} 를 박아 두면 그 DB 에서 테이블 0개가 나오고 스캔은 "성공"한다.</li>
 *   <li><b>감사 로그 프리픽스는 건너뛴다.</b> 거기 걸리는 것은 참조가 아니라 이력이라
 *       한 번이라도 설정된 모든 필드가 걸린다. 가장 크게 자라는 테이블이기도 하다.</li>
 *   <li><b>테이블마다 쿼리 하나.</b> 필드마다도, 컬럼마다도 아니다.</li>
 *   <li><b>행을 쌓지 않고 흘려보낸다.</b> 전에는 500행의 CLOB 을 이어붙여 목록에 담아
 *       돌려줬다 — 값 복사 두 배에 500행 동시 보유. 이제 한 행씩 {@link RowSink} 로 넘긴다.</li>
 *   <li><b>잘리면 말한다.</b> 상한을 넘는 행이 있으면 {@link Rows#truncated} 로 알린다 —
 *       조용히 자르면 상세 화면이 "발견되지 않았습니다"를 단정문으로 낸다.</li>
 * </ul>
 */
public class DeepScanDao {

    /** {@link #findRows} 가 읽은 행을 한 줄씩 받는 곳. 컬럼 값은 이어붙이지 않고 따로 준다. */
    public interface RowSink {

        /**
         * @param rowId  기본키 값. 기본키가 없는 테이블이면 null.
         * @param values 텍스트 컬럼 값들(null 제외). 같은 행의 것이다 — 필드 하나가 두 컬럼에
         *               있어도 부르는 쪽이 행 단위로 한 번만 세어야 한다.
         */
        void row(String rowId, List<String> values);
    }

    /** 한 테이블을 읽은 결과 요약. 행 자체는 {@link RowSink} 로 이미 넘어갔다. */
    public static final class Rows {

        /** 넘긴 행 수. */
        public final int rows;
        /** 상한을 넘어 잘렸는가. 참이면 이 테이블의 결과는 불완전하다. */
        public final boolean truncated;

        Rows(int rows, boolean truncated) {
            this.rows = rows;
            this.truncated = truncated;
        }
    }

    private final DatabaseAccessor databaseAccessor;
    private final String schema;

    public DeepScanDao() {
        this(ComponentAccessor.getComponent(DatabaseAccessor.class));
    }

    public DeepScanDao(DatabaseAccessor databaseAccessor) {
        this.databaseAccessor = databaseAccessor;
        this.schema = new JanitorDao(databaseAccessor).schemaName();
    }

    /**
     * 훑을 대상 목록. 텍스트 컬럼이 하나도 없는 테이블은 애초에 뺀다.
     *
     * <p>행 수는 여기서 세지 않는다. 전에는 여기서 세었는데, 그러면 테이블 수십 개의
     * {@code COUNT(*)} 가 진행률 갱신 없이 한 커넥션 안에서 돌아 화면이 "(0/0)"에
     * 멈춰 보였다(리뷰 지적). {@link #countRows} 를 테이블 루프 안에서 부른다.
     */
    public List<DeepTable> listTables() {
        return databaseAccessor.executeQuery(new ConnectionFunction<List<DeepTable>>() {
            @Override
            public List<DeepTable> run(DatabaseConnection connection) {
                List<DeepTable> tables = new ArrayList<DeepTable>();
                try {
                    DatabaseMetaData meta = connection.getJdbcConnection().getMetaData();
                    for (String name : tableNames(meta)) {
                        if (isSkipped(name)) {
                            continue;
                        }
                        List<String> textColumns = textColumns(meta, name);
                        if (textColumns.isEmpty()) {
                            continue;
                        }
                        tables.add(new DeepTable(name, primaryKey(meta, name), textColumns));
                    }
                } catch (SQLException e) {
                    throw new JanitorDao.DaoException("앱 테이블 목록을 읽지 못했다", e);
                }
                return tables;
            }
        });
    }

    /**
     * 테이블 하나의 행 수. 비용을 관리자에게 알려줄 수 있는 유일한 숫자다 —
     * "테이블 200개"보다 "행 240만"이 실제 비용에 가깝다(실측 35번).
     *
     * @throws JanitorDao.DaoException 못 셌을 때. 부르는 쪽이 "확인 불가"로 남긴다 —
     *         전에는 -1 을 0 으로 접고 DEBUG 로그만 남겨 합계가 조용히 줄었다(리뷰 지적).
     */
    public long countRows(final DeepTable table) {
        return databaseAccessor.executeQuery(new ConnectionFunction<Long>() {
            @Override
            public Long run(DatabaseConnection connection) {
                Connection jdbc = connection.getJdbcConnection();
                try {
                    String sql = "SELECT COUNT(*) FROM " + qualified(table.getName(), quote(jdbc.getMetaData()));
                    // 타임아웃은 executeQuery 보다 먼저. try-with-resources 헤더 안에서
                    // executeQuery 를 해 버리면 그 뒤의 setQueryTimeout 은 아무 일도
                    // 하지 않는다 — v1.3.0 이 정확히 그렇게 되어 있었다(리뷰 지적).
                    try (PreparedStatement statement = jdbc.prepareStatement(sql)) {
                        statement.setQueryTimeout(DeepScanPolicy.QUERY_TIMEOUT_SECONDS);
                        try (ResultSet rows = statement.executeQuery()) {
                            return rows.next() ? rows.getLong(1) : 0L;
                        }
                    }
                } catch (SQLException e) {
                    throw new JanitorDao.DaoException("테이블 " + table.getName() + " 행 수 조회 실패", e);
                }
            }
        });
    }

    /**
     * 테이블 하나에서 커스텀 필드 표기를 담은 행을 읽어 한 행씩 {@code sink} 에 넘긴다.
     *
     * @throws JanitorDao.DaoException 그 테이블만 실패했을 때. 부르는 쪽이 "확인 불가"로
     *         기록하고 다음 테이블로 간다 — 앱 하나 때문에 심층 스캔 전체를 버리지 않는다.
     */
    public Rows findRows(final DeepTable table, final RowSink sink) {
        return databaseAccessor.executeQuery(new ConnectionFunction<Rows>() {
            @Override
            public Rows run(DatabaseConnection connection) {
                Connection jdbc = connection.getJdbcConnection();
                int count = 0;
                boolean truncated = false;
                try {
                    String sql = selectSql(table, quote(jdbc.getMetaData()), schema);
                    try (PreparedStatement statement = jdbc.prepareStatement(sql)) {
                        statement.setQueryTimeout(DeepScanPolicy.QUERY_TIMEOUT_SECONDS);
                        // 상한보다 하나 더 읽는다. 그 하나가 있으면 "잘렸다"를 안다.
                        statement.setMaxRows(DeepScanPolicy.ROW_LIMIT + 1);
                        try (ResultSet result = statement.executeQuery()) {
                            while (result.next()) {
                                if (count == DeepScanPolicy.ROW_LIMIT) {
                                    truncated = true;
                                    break;
                                }
                                List<String> values = new ArrayList<String>(table.getTextColumns().size());
                                for (String column : table.getTextColumns()) {
                                    String value = result.getString(column);
                                    if (value != null) {
                                        values.add(value);
                                    }
                                }
                                String id = table.getIdColumn() == null
                                        ? null : result.getString(table.getIdColumn());
                                sink.row(id, values);
                                count++;
                            }
                        }
                    }
                } catch (SQLException e) {
                    throw new JanitorDao.DaoException("테이블 " + table.getName() + " 조회 실패", e);
                }
                return new Rows(count, truncated);
            }
        });
    }

    /**
     * {@code SELECT <id>, <text cols> FROM <schema.t> WHERE <col> LIKE ... OR ... ORDER BY <id>}
     *
     * <p>ORDER BY 를 두는 이유: 상한이 있는 조회에 순서가 없으면 DB 가 임의의 500행을
     * 주고, 두 번 돌리면 결과가 다르다. 기본키가 없으면 정렬도 없다.
     *
     * <p>열어 두는 이유: 테스트가 SQL 을 문자열로 확인한다. 인용·스키마를 빠뜨리면
     * PostgreSQL 에서만 깨지는데 개발은 H2 로 하므로 배포 뒤에야 드러난다.
     */
    public static String selectSql(DeepTable table, String quote, String schema) {
        StringBuilder columns = new StringBuilder();
        if (table.getIdColumn() != null) {
            columns.append(quote).append(table.getIdColumn()).append(quote);
        }
        StringBuilder where = new StringBuilder();
        for (String column : table.getTextColumns()) {
            if (columns.length() > 0) {
                columns.append(", ");
            }
            columns.append(quote).append(column).append(quote);
            if (where.length() > 0) {
                where.append(" OR ");
            }
            where.append(quote).append(column).append(quote).append(' ')
                 .append(JanitorDao.LIKE_CONTAINS_CUSTOMFIELD);
        }
        String orderBy = table.getIdColumn() == null
                ? "" : " ORDER BY " + quote + table.getIdColumn() + quote;
        return "SELECT " + columns + " FROM " + qualified(table.getName(), quote, schema)
                + " WHERE " + where + orderBy;
    }

    public static boolean isSkipped(String tableName) {
        for (String prefix : DeepScanPolicy.SKIPPED_PREFIXES) {
            if (tableName.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /** 문자열 계열만 본다. 바이너리(BLOB/VARBINARY)는 LIKE 대상이 아니다. */
    public static boolean isText(int sqlType) {
        return sqlType == Types.CHAR || sqlType == Types.VARCHAR || sqlType == Types.LONGVARCHAR
                || sqlType == Types.NCHAR || sqlType == Types.NVARCHAR
                || sqlType == Types.LONGNVARCHAR || sqlType == Types.CLOB || sqlType == Types.NCLOB;
    }

    /**
     * 메타데이터 검색 패턴. {@code AO_%} 의 {@code _} 는 와일드카드라 이스케이프해야
     * 하는데, <b>이스케이프 문자는 드라이버마다 다르다</b>(PostgreSQL·H2 {@code \},
     * Oracle {@code /}). 하드코딩하면 그 DB 에서 테이블 0개가 나오고 스캔은 성공한다.
     */
    public static String tablePattern(String searchStringEscape) {
        String escape = searchStringEscape == null || searchStringEscape.isEmpty() ? "\\" : searchStringEscape;
        return "AO" + escape + "_%";
    }

    private String qualified(String table, String quote) {
        return qualified(table, quote, schema);
    }

    private static String qualified(String table, String quote, String schema) {
        String quoted = quote + table + quote;
        return schema == null ? quoted : schema + "." + quoted;
    }

    private static String quote(DatabaseMetaData meta) throws SQLException {
        String quote = meta.getIdentifierQuoteString();
        // 인용을 지원하지 않는 DB 는 공백을 준다. 그때는 인용하지 않는다.
        return quote == null || quote.trim().isEmpty() ? "" : quote;
    }

    private List<String> tableNames(DatabaseMetaData meta) throws SQLException {
        List<String> names = new ArrayList<String>();
        try (ResultSet rows = meta.getTables(null, schema, tablePattern(meta.getSearchStringEscape()),
                new String[]{"TABLE"})) {
            while (rows.next()) {
                names.add(rows.getString("TABLE_NAME"));
            }
        }
        return names;
    }

    private List<String> textColumns(DatabaseMetaData meta, String table) throws SQLException {
        List<String> columns = new ArrayList<String>();
        try (ResultSet rows = meta.getColumns(null, schema, table, "%")) {
            while (rows.next()) {
                if (isText(rows.getInt("DATA_TYPE"))) {
                    columns.add(rows.getString("COLUMN_NAME"));
                }
            }
        }
        return columns;
    }

    /** @return 단일 컬럼 기본키의 이름. 없거나 복합키면 null — 그때는 행을 지목하지 않는다. */
    private String primaryKey(DatabaseMetaData meta, String table) throws SQLException {
        String key = null;
        try (ResultSet rows = meta.getPrimaryKeys(null, schema, table)) {
            while (rows.next()) {
                if (key != null) {
                    return null;
                }
                key = rows.getString("COLUMN_NAME");
            }
        }
        return key;
    }
}
