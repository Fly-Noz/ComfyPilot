import Photos
import UIKit

/// 存图到系统相册。
///
/// 用 `.addOnly` 授权：只申请“添加”权限，不申请读取整个相册——
/// 我们不需要看用户的照片，申请更小的权限审核和用户都更舒服。
/// Info.plist 里对应的是 `NSPhotoLibraryAddUsageDescription`。
enum PhotoSaver {
    static func save(_ image: UIImage, completion: @escaping (String) -> Void) {
        PHPhotoLibrary.requestAuthorization(for: .addOnly) { status in
            guard status == .authorized || status == .limited else {
                completion("没有相册权限，去设置里开启")
                return
            }
            PHPhotoLibrary.shared().performChanges {
                PHAssetChangeRequest.creationRequestForAsset(from: image)
            } completionHandler: { success, error in
                if success {
                    completion("已保存到相册")
                } else if let error {
                    completion("保存失败：\(error.localizedDescription)")
                } else {
                    completion("保存失败")
                }
            }
        }
    }
}
