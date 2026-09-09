package com.bskim.jira.janitor.fields.scan;

/**
 * 스캔을 계속할 수 없다는 뜻. 던지면 스캔 전체가 실패로 끝난다.
 *
 * <p>왜 "부분 결과 + 확인 불가"로 넘기지 않는가: 판정에 직접 쓰이는 재료가 빠지면
 * 라벨이 비는 게 아니라 <b>거꾸로</b> 나온다. 값 집계가 실패하면 값 있는 이슈 수가
 * 0으로 남고, {@code judge(0, 0, 0, 0)} 은 [미사용] — 즉 "삭제 안전"이다. 잠긴 필드
 * 조회가 실패하면 Sprint / Epic Link 가 [미사용]로 찍히고 삭제 링크까지 열린다.
 *
 * <p>그래서 재료를 두 등급으로 나눈다.
 *
 * <ul>
 *   <li><b>필수</b> — 값 집계({@code customfieldvalue}), 잠긴 필드
 *       ({@code managedconfigurationitem}). 실패하면 이 예외를 던져 스캔을 실패시킨다.
 *       이전 결과는 그대로 남고 화면은 실패를 알린다.</li>
 *   <li><b>보조</b> — 나머지 수집기와 마지막 변경일. 실패하면 "확인 불가" 목록에
 *       올리고 계속한다. 이쪽은 없으면 수가 줄어들 뿐 라벨이 뒤집히지 않는다.</li>
 * </ul>
 *
 * <p>기획서 7·8 (실패를 조용히 누락시키지 않는다 / "참조 없음 = 삭제 안전"으로
 * 읽히면 안 된다)의 직접적인 귀결이다.
 */
public class ScanFailedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ScanFailedException(String message, Throwable cause) {
        super(message, cause);
    }
}
