package com.bskim.jira.janitor.fields.dao;

import com.atlassian.jira.component.ComponentAccessor;
import com.atlassian.jira.database.DatabaseAccessor;
import com.atlassian.jira.database.DatabaseConnection;
import com.atlassian.jira.database.ConnectionFunction;
import org.apache.log4j.Logger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 직접 SQL을 쓰는 유일한 곳. 기획서 함정 10에 따라 방언·스키마 프리픽스 처리를
 * 여기 한 곳에 모아둔다.
 *
 * <p>커넥션은 {@link DatabaseAccessor}로 얻는다. 기획서 5.1은
 * {@code DefaultOfBizConnectionFactory}를 지목했지만 그 클래스는 jira-api에 없다
 * (jira-core 내부 구현체다). {@code DatabaseAccessor}는 jira-api 8.13에 공개된
 * 인터페이스이고 기획서가 요구한 것을 모두 준다 — schema-name 존중, 벤더 판별,
 * 커넥션 반납 보장. 근거(직접 SQL은 DAO 한 곳 / try-with-resources / 스키마 존중)는
 * 그대로 지켜지고, 공개 API라서 8.17 이후 호환이 오히려 낫다.
 *
 * <p>{@code executeQuery}가 커넥션 반납을 책임지므로 여기서는 커넥션을 닫지 않는다.
 * 대신 Statement/ResultSet은 try-with-resources로 반드시 닫는다(함정 9).
 */
public class JanitorDao {

    private static final Logger log = Logger.getLogger(JanitorDao.class);

    /**
     * "커스텀 필드 표기를 포함한다"는 LIKE 절. 세 DAO 쿼리가 같은 리터럴을 손으로
     * 복사하고 있었다(리뷰 지적). LIKE 의 {@code _} 는 와일드카드라 ESCAPE 가 필수다 —
     * 한 곳만 빠뜨리면 그 쿼리만 조용히 넓어진다.
     */
    public static final String LIKE_CONTAINS_CUSTOMFIELD = "LIKE '%customfield!_%' ESCAPE '!'";
    /** 값 전체가 {@code customfield_<id>} 형식인 컬럼용(앞에 다른 글자가 없다). */
    public static final String LIKE_STARTS_CUSTOMFIELD = "LIKE 'customfield!_%' ESCAPE '!'";

    private final DatabaseAccessor databaseAccessor;

    public JanitorDao() {
        this(ComponentAccessor.getComponent(DatabaseAccessor.class));
    }

    public JanitorDao(DatabaseAccessor databaseAccessor) {
        this.databaseAccessor = databaseAccessor;
    }

    /**
     * 커스텀 필드 전체 목록을 DB에서 읽는다. 존재 여부의 근거는 이 테이블이다.
     *
     * <p>{@link CustomFieldRow} 주석의 근거 참고 — 타입 제공 앱이 비활성이면
     * {@code CustomFieldManager}에서 필드가 아예 빠진다.
     */
    public List<CustomFieldRow> getCustomFieldRows() {
        final String sql = "SELECT id, cfname, customfieldtypekey FROM " + table("customfield") + " ORDER BY id";

        return databaseAccessor.executeQuery(new ConnectionFunction<List<CustomFieldRow>>() {
            @Override
            public List<CustomFieldRow> run(DatabaseConnection connection) {
                List<CustomFieldRow> rows = new ArrayList<CustomFieldRow>();
                Connection jdbc = connection.getJdbcConnection();
                try (PreparedStatement statement = jdbc.prepareStatement(sql);
                     ResultSet results = statement.executeQuery()) {
                    while (results.next()) {
                        rows.add(new CustomFieldRow(results.getLong("id"),
                                results.getString("cfname"),
                                results.getString("customfieldtypekey")));
                    }
                } catch (SQLException e) {
                    throw new DaoException("커스텀 필드 목록 쿼리 실패", e);
                }
                return rows;
            }
        });
    }

    /**
     * 앱이 관리하는(잠긴) 커스텀 필드 목록. 쿼리 한 번으로 전체를 얻는다.
     *
     * <p>{@link ManagedFieldRow} 주석의 근거 참고 — 앱이 비활성이어도 이 테이블은 남는다.
     *
     * @return 필드 숫자 ID → 관리 정보. 관리 대상이 아닌 필드는 키가 없다.
     */
    public Map<Long, ManagedFieldRow> getManagedCustomFields() {
        // managed 컬럼을 SQL에서 비교하지 않는다. OfBiz의 indicator 타입이라 DB에 따라
        // varchar / char(1) / number로 떨어지고, 실측한 PostgreSQL에서는 varchar(10)에
        // 문자열 "true"가 들어 있었다. boolean 파라미터로 비교하니 드라이버가
        // "operator does not exist: character varying = boolean"으로 거부했다.
        // 값을 그대로 받아 Java에서 판정하면 방언 문제가 사라진다(함정 10).
        final String sql = "SELECT item_id, managed, access_level, source, description_key"
                + " FROM " + table("managedconfigurationitem")
                + " WHERE item_type = 'CUSTOM_FIELD'";

        return databaseAccessor.executeQuery(new ConnectionFunction<Map<Long, ManagedFieldRow>>() {
            @Override
            public Map<Long, ManagedFieldRow> run(DatabaseConnection connection) {
                Map<Long, ManagedFieldRow> managed = new HashMap<Long, ManagedFieldRow>();
                Connection jdbc = connection.getJdbcConnection();
                try (PreparedStatement statement = jdbc.prepareStatement(sql);
                     ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        if (!isTrue(rows.getString("managed"))) {
                            continue;
                        }
                        Long fieldId = parseFieldId(rows.getString("item_id"));
                        if (fieldId == null) {
                            continue;
                        }
                        managed.put(fieldId, new ManagedFieldRow(fieldId,
                                rows.getString("access_level"),
                                rows.getString("source"),
                                rows.getString("description_key")));
                    }
                } catch (SQLException e) {
                    throw new DaoException("앱 관리 필드 쿼리 실패", e);
                }
                return managed;
            }
        });
    }

    /**
     * OfBiz indicator 값 판정. Jira/DB 조합에 따라 {@code "true"}, {@code "Y"},
     * {@code "1"}, {@code "t"} 중 무엇이든 올 수 있어서 전부 받아들인다.
     */
    static boolean isTrue(String indicator) {
        if (indicator == null) {
            return false;
        }
        String value = indicator.trim();
        return "true".equalsIgnoreCase(value)
                || "y".equalsIgnoreCase(value)
                || "t".equalsIgnoreCase(value)
                || "1".equals(value);
    }

    /** {@code item_id}는 {@code customfield_10104} 형태다. 숫자가 아니면 커스텀 필드가 아니다. */
    static Long parseFieldId(String itemId) {
        if (itemId == null) {
            return null;
        }
        String value = itemId.trim();
        if (value.startsWith("customfield_")) {
            value = value.substring("customfield_".length());
        }
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 축 A 주 지표. 전체 필드의 값 집계를 <b>쿼리 한 번</b>으로 얻는다(기획서 결정 4).
     * 필드가 500개여도 1회다.
     *
     * @return 필드 숫자 ID → 값 집계. 값이 한 건도 없는 필드는 아예 키가 없다.
     */
    public Map<Long, ValueCount> getValueCounts() {
        final String sql = "SELECT customfield, COUNT(DISTINCT issue) AS issue_count, COUNT(*) AS row_count"
                + " FROM " + table("customfieldvalue")
                + " WHERE customfield IS NOT NULL"
                + " GROUP BY customfield";

        return databaseAccessor.executeQuery(new ConnectionFunction<Map<Long, ValueCount>>() {
            @Override
            public Map<Long, ValueCount> run(DatabaseConnection connection) {
                Map<Long, ValueCount> counts = new HashMap<Long, ValueCount>();
                Connection jdbc = connection.getJdbcConnection();
                try (PreparedStatement statement = jdbc.prepareStatement(sql);
                     ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        counts.put(rows.getLong("customfield"),
                                new ValueCount(rows.getLong("issue_count"), rows.getLong("row_count")));
                    }
                } catch (SQLException e) {
                    throw new DaoException("값 집계 쿼리 실패", e);
                }
                return counts;
            }
        });
    }

    /**
     * 라벨 타입 커스텀 필드의 값 집계.
     *
     * <p>기획서 5.1은 {@code customfieldvalue}만 본다. 그런데 라벨 타입 커스텀 필드는
     * 값을 {@code label} 테이블에 저장한다(실측: 라벨 필드에 값 3개를 넣었더니
     * {@code customfieldvalue}에는 한 행도 없고 {@code label}에 3행이 생겼다).
     *
     * <p>그래서 라벨 필드는 값이 잔뜩 있어도 "값 0건"으로 나온다. 이 도구에서 가장
     * 나쁜 오류다 — 관리자가 데이터가 없다고 믿고 지우면 데이터가 사라진다.
     * 쿼리 한 번이면 막을 수 있으므로 막는다.
     *
     * <p>{@code label.fieldid}가 NULL인 행은 Jira 시스템 라벨 필드(커스텀 필드가
     * 아님)이므로 제외한다.
     *
     * @return 필드 숫자 ID → 값 집계
     */
    public Map<Long, ValueCount> getLabelValueCounts() {
        final String sql = "SELECT fieldid, COUNT(DISTINCT issue) AS issue_count, COUNT(*) AS row_count"
                + " FROM " + table("label")
                + " WHERE fieldid IS NOT NULL"
                + " GROUP BY fieldid";

        return databaseAccessor.executeQuery(new ConnectionFunction<Map<Long, ValueCount>>() {
            @Override
            public Map<Long, ValueCount> run(DatabaseConnection connection) {
                Map<Long, ValueCount> counts = new HashMap<Long, ValueCount>();
                Connection jdbc = connection.getJdbcConnection();
                try (PreparedStatement statement = jdbc.prepareStatement(sql);
                     ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        counts.put(rows.getLong("fieldid"),
                                new ValueCount(rows.getLong("issue_count"), rows.getLong("row_count")));
                    }
                } catch (SQLException e) {
                    throw new DaoException("라벨 값 집계 쿼리 실패", e);
                }
                return counts;
            }
        });
    }

    /**
     * 마지막 값 변경일 (보조 지표, 기획서 5.2).
     *
     * <p>{@code changeitem.field}는 변경 당시의 필드 <b>이름</b> 문자열이다. 그래서
     * 결과 키가 ID가 아니라 이름이고, 이름이 바뀌었거나 중복되면 부정확하다(함정 5).
     * 호출부는 이름 중복 필드에 대해 이 값을 "부정확" 표시와 함께 내거나 감춰야 한다.
     *
     * @return 소문자로 정규화한 필드 이름 → 마지막 변경 시각
     */
    public Map<String, Date> getLastValueChangeByFieldName() {
        final String sql = "SELECT ci.field AS field_name, MAX(cg.created) AS last_change"
                + " FROM " + table("changeitem") + " ci"
                + " JOIN " + table("changegroup") + " cg ON ci.groupid = cg.id"
                + " WHERE ci.fieldtype = 'custom'"
                + " GROUP BY ci.field";

        return databaseAccessor.executeQuery(new ConnectionFunction<Map<String, Date>>() {
            @Override
            public Map<String, Date> run(DatabaseConnection connection) {
                Map<String, Date> lastChanges = new HashMap<String, Date>();
                Connection jdbc = connection.getJdbcConnection();
                try (PreparedStatement statement = jdbc.prepareStatement(sql);
                     ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        String name = rows.getString("field_name");
                        Timestamp created = rows.getTimestamp("last_change");
                        if (name == null || created == null) {
                            continue;
                        }
                        String key = normalizeName(name);
                        Date existing = lastChanges.get(key);
                        if (existing == null || existing.before(created)) {
                            lastChanges.put(key, new Date(created.getTime()));
                        }
                    }
                } catch (SQLException e) {
                    throw new DaoException("마지막 변경일 쿼리 실패", e);
                }
                return lastChanges;
            }
        });
    }

    /**
     * 대시보드 가젯 설정 중 커스텀 필드를 참조할 수 있는 행만 뽑는다(기획서 5.3(8)).
     *
     * <p>{@code customfield_} 문자열이 들어간 행만 가져오므로 대시보드가 많아도
     * 전송량이 작다. 어느 필드인지 판별은 호출부가 문자열 매칭으로 한다.
     */
    public List<GadgetPrefRow> getGadgetPrefsReferencingCustomFields() {
        final String sql = "SELECT gup.portletconfiguration AS pc_id, gup.userprefkey AS pref_key,"
                + " gup.userprefvalue AS pref_value, pc.portalpage AS dashboard_id,"
                + " pc.dashboard_module_complete_key AS module_key, pc.gadget_xml AS gadget_xml,"
                + " pp.pagename AS dashboard_name, pp.username AS dashboard_owner"
                + " FROM " + table("gadgetuserpreference") + " gup"
                + " JOIN " + table("portletconfiguration") + " pc ON gup.portletconfiguration = pc.id"
                + " LEFT JOIN " + table("portalpage") + " pp ON pc.portalpage = pp.id"
                // LIKE 에서 _ 는 임의의 한 글자다. 여기서는 리터럴로 쓰려는 것이므로
                // ESCAPE 를 준다. 지금 데이터로는 결과가 같지만(키가 customfield_숫자
                // 뿐) 의도한 쿼리가 아니고 다른 DB로 옮길 때 오탐이 된다.
                + " WHERE gup.userprefvalue " + LIKE_CONTAINS_CUSTOMFIELD;

        return databaseAccessor.executeQuery(new ConnectionFunction<List<GadgetPrefRow>>() {
            @Override
            public List<GadgetPrefRow> run(DatabaseConnection connection) {
                List<GadgetPrefRow> prefs = new ArrayList<GadgetPrefRow>();
                Connection jdbc = connection.getJdbcConnection();
                try (PreparedStatement statement = jdbc.prepareStatement(sql);
                     ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        String moduleKey = rows.getString("module_key");
                        String gadgetXml = rows.getString("gadget_xml");
                        Long dashboardId = rows.getObject("dashboard_id") == null
                                ? null : rows.getLong("dashboard_id");
                        prefs.add(new GadgetPrefRow(
                                rows.getLong("pc_id"),
                                rows.getString("pref_key"),
                                rows.getString("pref_value"),
                                dashboardId,
                                rows.getString("dashboard_name"),
                                rows.getString("dashboard_owner"),
                                moduleKey != null ? moduleKey : gadgetXml));
                    }
                } catch (SQLException e) {
                    throw new DaoException("가젯 설정 쿼리 실패", e);
                }
                return prefs;
            }
        });
    }

    /**
     * 이슈 네비게이터 컬럼 설정에서 커스텀 필드를 쓰는 행 전부(기획서 5.3(9)).
     *
     * <p>집계만 내던 것을 행 단위로 바꿨다. 수만 보여주면 관리자가 할 수 있는 일이
     * 없기 때문이다 — "3곳" 은 어디를 고쳐야 하는지 알려주지 않는다. 시스템 기본
     * 컬럼인지, 어느 필터의 컬럼인지, 개인 설정인지가 갈려야 조치가 정해진다.
     *
     * <p>{@code columnlayoutitem} 은 필드 하나가 여러 설정에 들어가도 행이 나뉘므로
     * 이 쿼리 자체는 인스턴스 크기에 비례한다. 다만 {@code WHERE} 로 커스텀 필드
     * 항목만 걸러서 가져온다 — 시스템 필드(issuekey, status …)가 대부분이라
     * 실제로 넘어오는 행은 훨씬 적다.
     */
    public List<ColumnLayoutRow> getColumnLayoutRows() {
        final String sql = "SELECT cl.id AS layout_id, cl.username AS user_key,"
                + " cl.searchrequest AS filter_id, sr.filtername AS filter_name,"
                + " cli.fieldidentifier AS field_id"
                + " FROM " + table("columnlayoutitem") + " cli"
                + " JOIN " + table("columnlayout") + " cl ON cli.columnlayout = cl.id"
                + " LEFT JOIN " + table("searchrequest") + " sr ON cl.searchrequest = sr.id"
                // LIKE 의 _ 는 와일드카드다. 리터럴로 쓰려는 것이므로 ESCAPE 를 준다.
                + " WHERE cli.fieldidentifier " + LIKE_STARTS_CUSTOMFIELD;

        return databaseAccessor.executeQuery(new ConnectionFunction<List<ColumnLayoutRow>>() {
            @Override
            public List<ColumnLayoutRow> run(DatabaseConnection connection) {
                List<ColumnLayoutRow> rows = new ArrayList<ColumnLayoutRow>();
                Connection jdbc = connection.getJdbcConnection();
                try (PreparedStatement statement = jdbc.prepareStatement(sql);
                     ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        Long filterId = result.getObject("filter_id") == null
                                ? null : result.getLong("filter_id");
                        rows.add(new ColumnLayoutRow(
                                result.getLong("layout_id"),
                                trimToNull(result.getString("user_key")),
                                filterId,
                                result.getString("filter_name"),
                                result.getString("field_id")));
                    }
                } catch (SQLException e) {
                    throw new DaoException("컬럼 레이아웃 쿼리 실패", e);
                }
                return rows;
            }
        });
    }

    /** 빈 문자열은 "값 없음" 으로 본다. 컬럼 설정의 주인 컬럼이 빈 문자열인 인스턴스가 있다. */
    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** 진단용. 화면에 "DB: PostgreSQL 11" 처럼 찍어 관리자가 실측 조건을 알게 한다. */
    public String describeDatabase() {
        try {
            return databaseAccessor.getDatabaseVendor().getHumanReadableName()
                    + " (" + databaseAccessor.getDatabaseType() + ")";
        } catch (RuntimeException e) {
            log.debug("DB 종류를 못 읽었다", e);
            return "unknown";
        }
    }

    /**
     * 스키마 프리픽스를 붙인 테이블 이름. {@code schema-name}이 설정된 인스턴스
     * (실측 대상 docker Jira는 {@code public})에서 프리픽스가 없으면 쿼리가 깨진다.
     */
    private String table(String name) {
        String schema = schemaName();
        return schema == null ? name : schema + "." + name;
    }

    /**
     * dbconfig 의 {@code schema-name}. 없으면 null. {@link DeepScanDao} 도 이 값을 쓴다 —
     * 저쪽이 스키마를 안 붙여서 PostgreSQL 전용 스키마 설정에서는 심층 스캔 전체가
     * "relation does not exist"로 죽는 결함이 있었다(리뷰 지적).
     */
    public String schemaName() {
        String schema = databaseAccessor.getSchemaName().orElse(null);
        return schema == null || schema.trim().isEmpty() ? null : schema.trim();
    }

    static String normalizeName(String name) {
        return name.trim().toLowerCase(java.util.Locale.ENGLISH);
    }

    /** DAO 실패를 상위에서 "확인 불가"로 바꿀 수 있게 하는 런타임 예외. */
    public static class DaoException extends RuntimeException {
        public DaoException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
