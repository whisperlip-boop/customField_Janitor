package com.bskim.jira.janitor.fields.dao;

/**
 * {@code gadgetuserpreference} 한 행 + 소속 대시보드 정보. 기획서 5.3(8).
 */
public final class GadgetPrefRow {

    private final long portletConfigurationId;
    private final String prefKey;
    private final String prefValue;
    private final Long dashboardId;
    private final String dashboardName;
    private final String dashboardOwner;
    private final String gadgetKey;

    public GadgetPrefRow(long portletConfigurationId, String prefKey, String prefValue,
                         Long dashboardId, String dashboardName, String dashboardOwner, String gadgetKey) {
        this.portletConfigurationId = portletConfigurationId;
        this.prefKey = prefKey;
        this.prefValue = prefValue;
        this.dashboardId = dashboardId;
        this.dashboardName = dashboardName;
        this.dashboardOwner = dashboardOwner;
        this.gadgetKey = gadgetKey;
    }

    public long getPortletConfigurationId() {
        return portletConfigurationId;
    }

    public String getPrefKey() {
        return prefKey;
    }

    public String getPrefValue() {
        return prefValue;
    }

    public Long getDashboardId() {
        return dashboardId;
    }

    public String getDashboardName() {
        return dashboardName;
    }

    public String getDashboardOwner() {
        return dashboardOwner;
    }

    /** 가젯 식별자(모듈 키 또는 gadget xml URI). 무슨 가젯인지 알려주는 용도. */
    public String getGadgetKey() {
        return gadgetKey;
    }
}
