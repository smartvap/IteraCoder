export default {
  menu: { aiChat: "AIチャット", settings: "設定", stats: "トークン統計", requirement: "要件分解", profile: "プロフィール" },
  chat: {
    placeholder: "質問を入力、Enterで送信、Shift+Enterで改行", send: "送信", stop: "停止",
    thinking: "考え中...", reasoning: "思考プロセス", todayTokens: "今日 {n} トークン",
    newChat: "新規チャット", clearChat: "チャットをクリア", refreshModels: "モデル更新",
    copy: "コピー", retry: "再試行", regenerate: "再生成",
    copied: "クリップボードにコピーしました", stopped: "生成を停止しました", error: "ネットワークエラー、再試行してください",
    emptyTitle: "新しいチャットを開始", emptyDesc: "質問を入力してAIと会話を始めましょう",
    escHint: "Escで停止", sendHint: "Enterで送信 · Shift+Enterで改行",
  },
  settings: {
    title: "設定", language: "言語", appearance: "外観", functions: "機能",
    bgColor: "背景色", menuTheme: "メニューテーマ", skipLogin: "ログインをスキップ",
    modelType: "モデルタイプ", local: "ローカル", network: "ネットワーク", apiUrl: "API URL", apiKey: "APIキー",
    save: "設定を保存", changePassword: "パスワード変更", oldPassword: "現在のパスワード", newPassword: "新しいパスワード", confirmPassword: "パスワード確認",
    modelConfig: "モデル設定", modelName: "モデル名", addModel: "モデル追加",
  },
  stats: {
    title: "トークン使用統計", todayRequests: "本日のリクエスト", inputTokens: "入力トークン", outputTokens: "出力トークン",
    totalTokens: "合計トークン", detailTitle: "リクエスト詳細", time: "時間", model: "モデル", ip: "IP",
    duration: "所要時間(ms)", status: "状態", success: "成功", failed: "失敗",
  },
  common: { copy: "コピー", delete: "削除", save: "保存", cancel: "キャンセル", confirm: "確認", retry: "再試行", loading: "読み込み中...", noData: "データなし" },
}
