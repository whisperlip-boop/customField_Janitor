/*
 * 기획서 6.3 — 기존 커스텀 필드 관리 화면에 사용 현황을 주입한다.
 *
 * 근거: 관리자가 "이거 지워도 되나?"를 생각하는 순간은 ViewCustomFields.jspa 를
 * 보고 있을 때다. 별도 메뉴로 넘어가 같은 필드를 다시 찾게 만들면 안 된다.
 *
 * 실측 사실(docs/00-환경실측.md [확인 1]):
 *  - Jira 8.13의 이 화면은 서버가 표를 렌더링하지 않는다. Marionette 뷰가
 *    #customfields-container 안에 비동기로 그린다. 그래서 MutationObserver가 필요하다.
 *  - 각 행은 <tr data-custom-field-id="10302"> 형태다
 *    (includes/admin/customfields/customfieldRowView.js 의 attributes()).
 *    숫자 ID다 — customfield_ 접두사가 아니다.
 *  - 표는 페이지네이션·필터로 다시 그려진다. 한 번 꾸미고 끝낼 수 없다.
 *
 * 실패해도 기존 화면을 깨뜨리지 않는 것이 이 스크립트의 첫 번째 요구사항이다.
 * 스캔 결과가 없으면 아무것도 주입하지 않고 조용히 물러난다.
 */
(function () {
    "use strict";

    if (!/\/secure\/admin\/ViewCustomFields\.jspa/.test(window.location.pathname)) {
        return;
    }

    var DECORATED = "data-janitor-decorated";
    var contextPath = (window.AJS && typeof AJS.contextPath === "function") ? AJS.contextPath() : "";
    var state = null;

    function fetchUsage(done) {
        var xhr = new XMLHttpRequest();
        xhr.open("GET", contextPath + "/rest/janitor/1.0/fields", true);
        xhr.setRequestHeader("Accept", "application/json");
        xhr.onreadystatechange = function () {
            if (xhr.readyState !== 4) {
                return;
            }
            if (xhr.status !== 200) {
                // 권한이 없거나 플러그인이 꺼졌다. 아무것도 하지 않는다.
                done(null);
                return;
            }
            try {
                done(JSON.parse(xhr.responseText));
            } catch (e) {
                done(null);
            }
        };
        xhr.send();
    }

    function format(template, value) {
        return String(template || "").replace("{0}", value);
    }

    /*
     * 이름 칸을 찾는다. 버전에 따라 표 구조가 다르다(실측).
     *
     *   8.13.0 : <tr><td>이름</td><td>타입</td>...            첫 td가 이름
     *   8.17.1 : <tr><td>체크박스</td><td class="customfield-name-cell">이름</td>...
     *            일괄 작업(showBulkCheckbox)이 켜지면 첫 td가 체크박스다.
     *
     * "첫 td"로 가정하면 8.17.1에서 배지가 체크박스 칸에 들어간다. 그래서
     * 클래스 → 이름 요소의 부모 → 첫 td 순으로 내려간다. 어느 버전에서 무엇이
     * 바뀌든 이름 요소만 찾으면 맞는 칸에 붙는다.
     */
    function findNameCell(row) {
        var byClass = row.querySelector("td.customfield-name-cell");
        if (byClass) {
            return byClass;
        }
        var nameEl = row.querySelector("td strong");
        while (nameEl && nameEl.tagName !== "TD") {
            nameEl = nameEl.parentNode;
        }
        return nameEl || row.querySelector("td");
    }

    function decorate(row) {
        if (row.getAttribute(DECORATED) === "true") {
            return;
        }
        var numericId = row.getAttribute("data-custom-field-id");
        if (!numericId) {
            return;
        }
        var usage = state.byId[numericId];
        if (!usage) {
            return;
        }
        var nameCell = findNameCell(row);
        if (!nameCell) {
            return;
        }
        row.setAttribute(DECORATED, "true");

        var box = document.createElement("div");
        box.className = "janitor-inject";

        var lozenge = document.createElement("span");
        lozenge.className = "aui-lozenge" +
            (usage.statusLozenge ? " aui-lozenge-" + usage.statusLozenge : "");
        lozenge.textContent = usage.statusLabel || usage.status;
        box.appendChild(lozenge);

        // 8.17.1의 커스텀 필드 화면은 잠긴 필드에 자체 LOCKED 배지를 이미 붙인다.
        // 우리가 또 붙이면 같은 말이 두 번 나온다. 있으면 생략한다.
        var jiraShowsLocked = !!(row.querySelector(".customfield-locked")
                || row.querySelector(".customfield-managed"));
        if (usage.locked && !jiraShowsLocked) {
            var lockedBadge = document.createElement("span");
            lockedBadge.className = "aui-lozenge aui-lozenge-error";
            lockedBadge.textContent = state.labels.locked;
            box.appendChild(lockedBadge);
        }

        var counts = document.createElement("span");
        counts.className = "janitor-inject-counts";
        counts.textContent = format(state.labels.values, usage.issuesWithValue);
        box.appendChild(counts);

        var link = document.createElement("a");
        link.className = "janitor-inject-link";
        link.href = contextPath + "/secure/admin/CustomFieldUsage.jspa?fieldId=" + usage.fieldId;
        link.textContent = state.labels.link;
        box.appendChild(link);

        nameCell.appendChild(box);
    }

    function decorateAll() {
        var rows = document.querySelectorAll("tr[data-custom-field-id]");
        for (var i = 0; i < rows.length; i++) {
            decorate(rows[i]);
        }
    }

    function start(response) {
        // 스캔한 적이 없으면 배지에 넣을 것이 없다. 조용히 비활성.
        if (!response || !response.fields || response.fields.length === 0) {
            return;
        }
        state = { byId: {}, labels: response.labels || {} };
        for (var i = 0; i < response.fields.length; i++) {
            state.byId[String(response.fields[i].id)] = response.fields[i];
        }

        decorateAll();

        var container = document.getElementById("customfields-container") || document.body;
        if (typeof MutationObserver !== "function") {
            return;
        }
        // 표는 페이지 이동·필터 변경마다 다시 그려진다. 그때마다 다시 꾸민다.
        new MutationObserver(function () {
            decorateAll();
        }).observe(container, { childList: true, subtree: true });
    }

    if (document.readyState === "loading") {
        document.addEventListener("DOMContentLoaded", function () {
            fetchUsage(start);
        });
    } else {
        fetchUsage(start);
    }
})();
