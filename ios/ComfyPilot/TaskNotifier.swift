import UserNotifications

/// 任务完成 / 失败 / 取消的本地通知。
///
/// 平台限制（不是漏做）：iOS 挂起进程后 shared 的状态机不再 pump，
/// 收不到完成事件；这里覆盖的是「前台等待 + 刚退到后台的短暂窗口」。
/// 长时间后台靠回前台对账，Android 的常驻后台通知不能照搬。
enum TaskNotifier {
    private static let enabledKey = "taskNotificationsEnabled"

    static var enabled: Bool {
        get { UserDefaults.standard.object(forKey: enabledKey) as? Bool ?? false }
        set { UserDefaults.standard.set(newValue, forKey: enabledKey) }
    }

    /// 打开开关时请求授权；已拒绝过就静默（系统不会二次弹窗）。
    static func requestAuthorizationIfNeeded() {
        UNUserNotificationCenter.current().requestAuthorization(
            options: [.alert, .sound, .badge]
        ) { _, _ in }
    }

    /// 任务进入终结态时调用（SessionStore 对比前后快照后触发）。
    static func notifyTaskFinished(_ task: TaskRow) {
        guard enabled else { return }
        let content = UNMutableNotificationContent()
        switch task.state {
        case "Completed":
            content.title = "任务完成"
            content.body = task.name.isEmpty ? "产物已生成" : "「\(task.name)」已生成产物"
        case "Failed":
            content.title = "任务失败"
            content.body = task.error.isEmpty
                ? (task.name.isEmpty ? "任务执行失败" : "「\(task.name)」执行失败")
                : task.error
        case "Cancelled":
            content.title = "任务已取消"
            content.body = task.name.isEmpty ? "任务已取消" : "「\(task.name)」已取消"
        default:
            return
        }
        content.sound = .default
        let request = UNNotificationRequest(
            identifier: "task-\(task.id)-\(task.state)",
            content: content,
            trigger: nil
        )
        UNUserNotificationCenter.current().add(request)
    }
}
