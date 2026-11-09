import Foundation

/// 本机偏好。和安卓的 `ForceSubmitPrefs` / `WeakNetworkPrefs` 一一对应，
/// 存 UserDefaults（安卓是 SharedPreferences），键名保持可读即可，不需要跨端同步。
enum Prefs {
    /// 本地有报错也照样提交，由 ComfyUI 判定。
    static var forceSubmit: Bool {
        get { UserDefaults.standard.bool(forKey: "forceSubmit") }
        set { UserDefaults.standard.set(newValue, forKey: "forceSubmit") }
    }

    /// 弱网：少抢带宽。主机状态 15 秒一刷，采样预览最多 2 秒一张。
    static var weakNetwork: Bool {
        get { UserDefaults.standard.bool(forKey: "weakNetwork") }
        set { UserDefaults.standard.set(newValue, forKey: "weakNetwork") }
    }
}
