import UIKit

/// 上传前的图片处理。
///
/// 一律重编码成 JPEG：相册里的 HEIC 用 PIL 读不了（ComfyUI 的 LoadImage 走 PIL），
/// 直接原样上传会在服务端报「无法识别图片」。顺带按最长边压一下，
/// 手机上原图 5–10MB，压完通常几百 KB，上传时间差一个量级。
enum ImageUploadPrep {
    static func jpeg(from data: Data, maxSide: CGFloat) -> Data? {
        guard let image = UIImage(data: data) else { return nil }

        let pixelWidth = image.size.width * image.scale
        let pixelHeight = image.size.height * image.scale
        let longest = max(pixelWidth, pixelHeight)
        let ratio = longest > maxSide ? maxSide / longest : 1
        let target = CGSize(
            width: max(1, (pixelWidth * ratio).rounded()),
            height: max(1, (pixelHeight * ratio).rounded())
        )

        let format = UIGraphicsImageRendererFormat.default()
        format.scale = 1
        let renderer = UIGraphicsImageRenderer(size: target, format: format)
        let resized = renderer.image { _ in
            image.draw(in: CGRect(origin: .zero, size: target))
        }
        return resized.jpegData(compressionQuality: 0.85)
    }
}
