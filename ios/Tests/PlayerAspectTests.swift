import XCTest
import CoreGraphics
import AVFoundation

/// 「画面比例」档位的纯计算部分。
///
/// 为什么要单测：`16:9` / `4:3` 在 iOS 上长期无效（两档都落到 `resizeAspect`，
/// 和「自动」一模一样），但表现只是"选了没反应"，靠真机肉眼看不出是设置没生效
/// 还是比例算错。把 `forcedAspect` / `fit` 抽成纯函数后，档位映射与居中都能量化。
final class PlayerAspectTests: XCTestCase {

    // MARK: 档位 → 目标比例

    func testOnlyRatioModesForceAspect() {
        XCTAssertNil(PlayerLayerView.forcedAspect("auto"))
        XCTAssertNil(PlayerLayerView.forcedAspect("fill"))
        XCTAssertNil(PlayerLayerView.forcedAspect("zoom"))
        XCTAssertEqual(PlayerLayerView.forcedAspect("16:9")!, 16.0 / 9.0, accuracy: 1e-9)
        XCTAssertEqual(PlayerLayerView.forcedAspect("4:3")!, 4.0 / 3.0, accuracy: 1e-9)
    }

    /// 16:9 / 4:3 必须把画面**填满**裁出来的图层，否则强制比例只留下黑边。
    func testRatioModesFillTheirLayer() {
        XCTAssertEqual(PlayerLayerView.gravity("16:9").rawValue,
                       AVLayerVideoGravity.resize.rawValue)
        XCTAssertEqual(PlayerLayerView.gravity("4:3").rawValue,
                       AVLayerVideoGravity.resize.rawValue)
    }

    /// 「自动」按源比例适配；「裁切铺满」是填满并裁掉溢出；「拉伸」是拉变形。
    func testOtherModesKeepTheirGravity() {
        XCTAssertEqual(PlayerLayerView.gravity("auto").rawValue,
                       AVLayerVideoGravity.resizeAspect.rawValue)
        XCTAssertEqual(PlayerLayerView.gravity("zoom").rawValue,
                       AVLayerVideoGravity.resizeAspectFill.rawValue)
        XCTAssertEqual(PlayerLayerView.gravity("fill").rawValue,
                       AVLayerVideoGravity.resize.rawValue)
    }

    // MARK: 居中裁剪

    func testNoForcedAspectUsesWholeBounds() {
        let rect = CGRect(x: 0, y: 0, width: 1220, height: 686)
        XCTAssertEqual(PlayerLayerUIView.fit(nil, in: rect), rect)
    }

    /// 容器正好是 16:9 时不产生**可见**黑边（浮点除法有尾差，按 1 点容差判）。
    ///
    /// 这里曾经写成 `XCTAssertEqual(out, rect)`：1220/686 与 16/9 差 0.0006，
    /// 算出来的宽比容器少 0.4 点，精确比较直接红——比例本身是对的。
    func testSixteenByNineContainerHasNoLetterbox() {
        let rect = CGRect(x: 0, y: 0, width: 1920, height: 1080)
        let out = PlayerLayerUIView.fit(16.0 / 9.0, in: rect)
        XCTAssertEqual(out.width, rect.width, accuracy: 1)
        XCTAssertEqual(out.height, rect.height, accuracy: 1)
        XCTAssertLessThan(abs(out.minX), 1)
        XCTAssertLessThan(abs(out.minY), 1)
    }

    /// 容器比目标宽 → 高度顶满，左右各留黑边（横屏长屏上看 4:3 源）。
    func testFourByThreeInWideContainerIsPillarboxed() {
        let rect = CGRect(x: 0, y: 0, width: 2168, height: 1220)
        let out = PlayerLayerUIView.fit(4.0 / 3.0, in: rect)
        XCTAssertEqual(out.height, rect.height, accuracy: 0.5)
        XCTAssertEqual(out.width, rect.height * 4.0 / 3.0, accuracy: 0.5)
        // 居中：左右黑边等宽
        XCTAssertEqual(out.minX, rect.width - out.maxX, accuracy: 0.5)
        XCTAssertEqual(out.minY, rect.minY, accuracy: 0.5)
    }

    /// 容器比目标高 → 宽度顶满，上下各留黑边（竖屏里看 16:9 之外的比例）。
    func testSixteenByNineInTallContainerIsLetterboxed() {
        let rect = CGRect(x: 0, y: 0, width: 400, height: 800)
        let out = PlayerLayerUIView.fit(16.0 / 9.0, in: rect)
        XCTAssertEqual(out.width, rect.width, accuracy: 0.5)
        XCTAssertEqual(out.height, 400 * 9.0 / 16.0, accuracy: 0.5)
        XCTAssertEqual(out.minY, rect.height - out.maxY, accuracy: 0.5)
    }

    /// 图层带偏移（不全屏、被父容器内缩）时也必须相对 `rect` 居中。
    func testFitKeepsOriginOffset() {
        let rect = CGRect(x: 40, y: 12, width: 800, height: 200)
        let out = PlayerLayerUIView.fit(4.0 / 3.0, in: rect)
        XCTAssertEqual(out.minX, rect.minX + (rect.width - out.width) / 2, accuracy: 0.5)
        XCTAssertEqual(out.minY, rect.minY, accuracy: 0.5)
    }

    /// 宽高为 0（首帧布局前）不能算出 inf / NaN 的图层，否则 CoreAnimation 直接报错。
    func testDegenerateBoundsFallBackToRect() {
        let zero = CGRect(x: 0, y: 0, width: 0, height: 500)
        XCTAssertEqual(PlayerLayerUIView.fit(16.0 / 9.0, in: zero), zero)
        let tall = CGRect(x: 0, y: 0, width: 100, height: 100)
        XCTAssertEqual(PlayerLayerUIView.fit(0, in: tall), tall)
    }
}
