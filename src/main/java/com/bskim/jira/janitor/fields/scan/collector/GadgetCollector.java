package com.bskim.jira.janitor.fields.scan.collector;

import com.bskim.jira.janitor.fields.dao.GadgetPrefRow;
import com.bskim.jira.janitor.fields.dao.JanitorDao;
import com.bskim.jira.janitor.fields.model.FieldUsage;
import com.bskim.jira.janitor.fields.model.Reference;
import com.bskim.jira.janitor.fields.model.ReferenceType;
import com.bskim.jira.janitor.fields.model.ScanProgress;
import com.bskim.jira.janitor.fields.scan.ReferenceCollector;
import com.bskim.jira.janitor.fields.scan.ScanContext;

/**
 * 대시보드 가젯 설정 참조 (기획서 5.3(8)).
 *
 * <p>가젯 설정은 {@code gadgetuserpreference.userprefvalue}에 문자열로 들어간다.
 * 필터 ID를 가리키는 가젯은 필터 쪽에서 이미 잡히므로, 여기서는 설정값에
 * {@code customfield_<id>}가 직접 박힌 경우(통계 가젯의 집계 필드 등)를 잡는다.
 *
 * <p>DAO가 {@code customfield_}를 포함하는 행만 걸러서 주므로 대시보드가 많아도 싸다.
 */
public class GadgetCollector implements ReferenceCollector {

    @Override
    public ScanProgress.Stage getStage() {
        return ScanProgress.Stage.GADGETS;
    }

    @Override
    public void collect(ScanContext context) {
        JanitorDao dao = new JanitorDao();
        for (GadgetPrefRow row : dao.getGadgetPrefsReferencingCustomFields()) {
            for (FieldUsage field : context.findReferencedFields(row.getPrefValue())) {
                String dashboard = row.getDashboardName() == null
                        ? "(dashboard " + row.getDashboardId() + ")"
                        : row.getDashboardName();
                String detail = row.getPrefKey()
                        + (row.getDashboardOwner() == null ? "" : " · " + row.getDashboardOwner())
                        + (row.getGadgetKey() == null ? "" : " · " + shorten(row.getGadgetKey()));

                context.addReference(field, new Reference(
                        ReferenceType.GADGET,
                        dashboard,
                        String.valueOf(row.getPortletConfigurationId()),
                        detail,
                        AdminUrls.dashboard(row.getDashboardId())));
            }
        }
    }

    /** 가젯 키가 긴 URI인 경우가 많다. 뒤쪽(파일명/모듈 키)만 남긴다. */
    private static String shorten(String gadgetKey) {
        int slash = gadgetKey.lastIndexOf('/');
        String tail = slash >= 0 ? gadgetKey.substring(slash + 1) : gadgetKey;
        return tail.length() <= 60 ? tail : tail.substring(0, 57) + "...";
    }
}
