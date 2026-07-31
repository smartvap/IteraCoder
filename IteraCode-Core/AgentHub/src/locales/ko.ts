export default {
  menu: { aiChat: "AI 채팅", settings: "설정", stats: "토큰 통계", requirement: "요구사항 분석", profile: "프로필" },
  chat: {
    placeholder: "질문을 입력하세요, Enter 전송, Shift+Enter 줄바꿈", send: "전송", stop: "중지",
    thinking: "생각 중...", reasoning: "추론 과정", todayTokens: "오늘 {n} 토큰",
    newChat: "새 채팅", clearChat: "채팅 지우기", refreshModels: "모델 새로고침",
    copy: "복사", retry: "재시도", regenerate: "재생성",
    copied: "클립보드에 복사됨", stopped: "생성 중지됨", error: "네트워크 오류, 다시 시도해주세요",
    emptyTitle: "새 대화 시작", emptyDesc: "질문을 입력하여 AI와 대화를 시작하세요",
    escHint: "Esc로 중지", sendHint: "Enter 전송 · Shift+Enter 줄바꿈",
  },
  settings: {
    title: "설정", language: "언어", appearance: "외관", functions: "기능",
    bgColor: "배경색", menuTheme: "메뉴 테마", skipLogin: "로그인 건너뛰기",
    modelType: "모델 유형", local: "로컬", network: "네트워크", apiUrl: "API URL", apiKey: "API 키",
    save: "설정 저장", changePassword: "비밀번호 변경", oldPassword: "현재 비밀번호", newPassword: "새 비밀번호", confirmPassword: "비밀번호 확인",
    modelConfig: "모델 설정", modelName: "모델 이름", addModel: "모델 추가",
  },
  stats: {
    title: "토큰 사용 통계", todayRequests: "오늘의 요청", inputTokens: "입력 토큰", outputTokens: "출력 토큰",
    totalTokens: "총 토큰", detailTitle: "요청 상세", time: "시간", model: "모델", ip: "IP",
    duration: "소요시간(ms)", status: "상태", success: "성공", failed: "실패",
  },
  common: { copy: "복사", delete: "삭제", save: "저장", cancel: "취소", confirm: "확인", retry: "재시도", loading: "로딩 중...", noData: "데이터 없음" },
}
