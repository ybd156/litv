"""把 ios-ipa.yml 的构建步骤改成走 CocoaPods workspace。

用脚本而不是直接改文件，是为了绕开 YAML 里大量反斜杠续行带来的转义麻烦。
"""
import io
import re

PATH = ".github/workflows/ios-ipa.yml"
src = io.open(PATH, encoding="utf-8").read()
lines = src.split("\n")

# 找到「生成 Xcode 工程」到「打包未签名 ipa」之间的所有行（含两端前一行）
try:
    start = next(i for i, l in enumerate(lines) if "生成 Xcode 工程" in l) - 1
    end = next(i for i, l in enumerate(lines) if "打包未签名 ipa" in l) - 1
except StopIteration:
    raise SystemExit("找不到步骤标记，检查文件结构")

new_block = '''      - name: 生成 Xcode 工程
        working-directory: ios
        run: |
          xcodegen generate
          ls -la

      - name: 安装 CocoaPods 依赖（MobileVLCKit）
        working-directory: ios
        run: |
          # 用 VLCKit 而不是自编 libmpv：libmpv 在 iOS 上要编六层依赖
          # （uavs3d/ffmpeg/fribidi/freetype/libass/mpv），
          # 而 MobileVLCKit 是预编译 xcframework，能力覆盖更全（AC3/DTS/TS）。
          pod install --repo-update 2>&1 | tail -20
          ls -la *.xcworkspace

      - name: 编译（不签名）
        working-directory: ios
        run: |
          # 有 Pod 之后必须用 .xcworkspace 而不是 .xcodeproj
          xcodebuild \\
            -workspace LITV.xcworkspace \\
            -scheme LITV \\
            -sdk iphoneos \\
            -configuration Release \\
            -derivedDataPath build \\
            -destination 'generic/platform=iOS' \\
            CODE_SIGNING_ALLOWED=NO \\
            CODE_SIGNING_REQUIRED=NO \\
            CODE_SIGN_IDENTITY="" \\
            CODE_SIGN_ENTITLEMENTS="" \\
            build 2>&1 | tail -80

'''

lines[start:end] = new_block.split("\n")
io.open(PATH, "w", encoding="utf-8").write("\n".join(lines))
print("已替换构建步骤")
