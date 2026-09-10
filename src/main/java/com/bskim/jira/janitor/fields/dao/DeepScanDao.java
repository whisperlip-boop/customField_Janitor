package com.bskim.jira.janitor.fields.dao;

import com.atlassian.jira.component.ComponentAccessor;
import com.atlassian.jira.database.ConnectionFunction;
import com.atlassian.jira.database.DatabaseAccessor;
import com.atlassian.jira.database.DatabaseConnection;
import com.bskim.jira.janitor.fields.deep.DeepTable;
import org.apache.log4j.Logger;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 앱 테이블({@code AO_*})을 직접 훑는 심층 스캔의 SQL. 일반 스캔의 SQL과 성격이
 * 달라 {@link JanitorDao} 와 나눴다 — 여기는 <b>어떤 테이블이 있는지도 모르는 채</b>
 * {@code DatabaseMetaData} 로 스키마를 읽어 쿼리를 만든다.
 *
 * <p>세 가지가 실측이다(docs/00 35번).
 * <ul>
 *   <li><b>식별자는 반드시 인용한다.</b> AO 테이블 이름은 대문자라 PostgreSQL 에서
 *       인용 없이 쓰면 소문자로 접혀 실패한다. 인용 문자는 DB마다 달라
 *       {@code getIdentifierQuoteString()} 으로 얻는다.</li>
 *   <li><b>감사 로그 프리픽스는 건너뛴다.</b> 거기 걸리는 것은 참조가 아니라 이력이라
 *       ("예전에 이 필드를 화면에 추가함") 한 번이라도 설정된 모든 필드가 걸린다.
 *       가장 크게 자라는 테이블이기도 하다.</li>
 *   <li><b>테이블마다 쿼리 하나.</b> 필드마다도, 컬럼마다도 아니다. 필드 300개 ×
 *       테이블 200개면 6만 번 쿼리가 된다(기획서 결정 3의 이유와 같다).</li>
 * </ul>
 */
public class DeepScanDao {

    private static final Logger log = Logger.getLogger(DeepScanDao.class);

    /**
     * 훑지 않는 프리픽스. 지금은 감사 로그 하나다(docs/00 35번).
     *
     * <p>이 예외는 "해석하지 않는다"는 원칙이 굽는 유일한 자리다. 그래서 화면에
     * 건너뛴 사실을 표시한다 — 조용히 빼면 표가 완전한 것으로 읽힌다.
     */
    public static final Set<String> SKIPPED_PREFIXES =
            Collections.unmodifiableSet(new HashSet<String>(Arrays.asList("AO_C77861")));

    /** 한 테이블에서 읽어 올 행의 상한. 한 앱이 결과를 뒤덮지 못하게 한다. */
    private static final int ROW_LIMIT = 500;

    /** 테이블 하나에 허용하는 시간. 넘으면 그 테이블만 포기하고 다음으로 간다. */
    private static final int QUERY_TIMEOUT_SECONDS = 60;

    private final DatabaseAccessor databaseAccessor;

    public DeepScanDao() {
        this(ComponentAccessor.getComponent(DatabaseAccessor.class));
    }

    public DeepScanDao(DatabaseAccessor databaseAccessor) {
        this.databaseAccessor = databaseAccessor;
    }

    /**
     * 훑을 대상 목록. 텍스트 컬럼이 하나도 없는 테이블은 애초에 뺀다.
     *
     * <p>행 수를 여기서 함께 센다. 비용을 관리자에게 알려줄 수 있는 유일한 숫자이고,
     * "테이블 200개"보다 "행 240만"이 실제 비용에 가깝다(실측 35번: 위험한 것은
     * 테이블 수가 아니라 한 테이블의 크기였다).
     */
    public List<DeepTable> listTables() {
        return databaseAccessor.executeQuery(new ConnectionFunction<List<DeepTable>>() {
            @Override
            public List<DeepTable> run(DatabaseConnection connection) {
                List<DeepTable> tables = new ArrayList<DeepTable>();
                Connection jdbc = connection.getJdbcConnection();
                try {
                    DatabaseMetaData meta = jdbc.getMetaData();
                    String quote = quote(meta);

                    for (String name : tableNames(meta)) {
                        if (isSkipped(name)) {
                            continue;
                        }
                        List<String> textColumns = textColumns(meta, name);
                        if (textColumns.isEmpty()) {
                            continue;
                        }
                        tables.add(new DeepTable(name, primaryKey(meta, name), textColumns,
                                countRows(jdbc, quote, name)));
                    }
                } catch (SQLException e) {
                    throw new JanitorDao.DaoException("앱 테이블 목록을 읽지 못했다", e);
                }
                return tables;
            }
        });
    }

    /**
     * 테이블 하나에서 커스텀 필드 표기를 담은 행을 읽는다.
     *
     * @return 행마다 [행 ID(없으면 null), 텍스트 컬럼을 이어붙인 문자열]
     * @throws DaoException 그 테이블만 실패했을 때. 부르는 쪽이 "확인 불가"로 기록하고
     *                      다음 테이블로 간다 — 앱 하나 때문에 심층 스캔 전체를 버리지 않는다.
     */
    public List<String[]> findRows(final DeepTable table) {
        return databaseAccessor.executeQuery(new ConnectionFunction<List<String[]>>() {
            @Override
            public List<String[]> run(DatabaseConnection connection) {
                Connection jdbc = connection.getJdbcConnection();
                List<String[]> rows = new ArrayList<String[]>();
                try {
                    String quote = quote(jdbc.getMetaData());
                    String sql = selectSql(table, quote);
                    try (PreparedStatement statement = jdbc.prepareStatement(sql)) {
                        statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
                        statement.setMaxRows(ROW_LIMIT);
                        try (ResultSet result = statement.executeQuery()) {
                            while (result.next()) {
                                StringBuilder text = new StringBuilder();
                                for (String column : table.getTextColumns()) {
                                    String value = result.getString(column);
                                    if (value != null) {
                                        text.append(value).append('\n');
                                    }
                                }
                                String id = table.getIdColumn() == null
                                        ? null : result.getString(table.getIdColumn());
                                rows.add(new String[]{id, text.toString()});
                            }
                        }
                    }
                } catch (SQLException e) {
                    throw new JanitorDao.DaoException("테이블 " + table.getName() + " 조회 실패", e);
                }
                return rows;
            }
        });
    }

    /**
     * {@code SELECT <id>, <text cols> FROM <t> WHERE <col> LIKE ... OR ...}
     *
     * <p>패키지 밖에서도 볼 수 있게 열어 둔다 — 테스트가 SQL 을 문자열로 확인한다.
     * 실제 DB 없이 확인할 수 있는 유일한 부분이고, 인용을 빠뜨리면 PostgreSQL 에서만
     * 깨지는 종류의 버그라 고정해 둘 값어치가 있다.
     */
    public static String selectSql(DeepTable table, String quote) {
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
            // LIKE 의 _ 는 와일드카드다. 리터럴로 쓰려는 것이므로 ESCAPE 를 준다.
            where.append(quote).append(column).append(quote)
                 .append(" LIKE '%customfield!_%' ESCAPE '!'");
        }
        return "SELECT " + columns + " FROM " + quote + table.getName() + quote + " WHERE " + where;
    }

    public static boolean isSkipped(String tableName) {
        for (String prefix : SKIPPED_PREFIXES) {
            if (tableName.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static String quote(DatabaseMetaData meta) throws SQLException {
        String quote = meta.getIdentifierQuoteString();
        // 인용을 지원하지 않는 DB 는 공백을 준다. 그때는 인용하지 않는다.
        return quote == null || quote.trim().isEmpty() ? "" : quote;
    }

    private static List<String> tableNames(DatabaseMetaData meta) throws SQLException {
        List<String> names = new ArrayList<String>();
        // "AO\_%" — 여기서도 _ 는 와일드카드다. 이스케이프하지 않으면 AOx 로 시작하는
        // 남의 테이블까지 훑는다.
        try (ResultSet rows = meta.getTables(null, null, "AO\\_%", new String[]{"TABLE"})) {
            while (rows.next()) {
                names.add(rows.getString("TABLE_NAME"));
            }
        }
        return names;
    }

    private static List<String> textColumns(DatabaseMetaData meta, String table) throws SQLException {
        List<String> columns = new ArrayList<String>();
        try (ResultSet rows = meta.getColumns(null, null, table, "%")) {
            while (rows.next()) {
                if (isText(rows.getInt("DATA_TYPE"))) {
                    columns.add(rows.getString("COLUMN_NAME"));
                }
            }
        }
        return columns;
    }

    /** 문자열 계열만 본다. 바이너리(BLOB/VARBINARY)는 LIKE 대상이 아니다. */
    public static boolean isText(int sqlType) {
        return sqlType == Types.CHAR || sqlType == Types.VARCHAR || sqlType == Types.LONGVARCHAR
                || sqlType == Types.NCHAR || sqlType == Types.NVARCHAR
                || sqlType == Types.LONGNVARCHAR || sqlType == Types.CLOB || sqlType == Types.NCLOB;
    }

    /** @return 단일 컬럼 기본키의 이름. 없거나 복합키면 null — 그때는 행을 지목하지 않는다. */
    private static String primaryKey(DatabaseMetaData meta, String table) throws SQLException {
        String key = null;
        try (ResultSet rows = meta.getPrimaryKeys(null, null, table)) {
            while (rows.next()) {
                if (key != null) {
                    return null;
                }
                key = rows.getString("COLUMN_NAME");
            }
        }
        return key;
    }

    private static long countRows(Connection jdbc, String quote, String table) {
        String sql = "SELECT COUNT(*) FROM " + quote + table + quote;
        try (PreparedStatement statement = jdbc.prepareStatement(sql);
             ResultSet rows = statement.executeQuery()) {
            statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
            return rows.next() ? rows.getLong(1) : 0L;
        } catch (SQLException e) {
            // 행 수는 비용 안내용이다. 못 세도 스캔은 한다.
            log.debug("행 수를 못 셌다: " + table, e);
            return -1L;
        }
    }
}
