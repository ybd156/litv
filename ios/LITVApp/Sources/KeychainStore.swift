import Foundation
import Security

/// Keychain 封装：设备 bearer token / device_key 这类凭据**只能**放这里。
///
/// 为什么必须这么做（AGENTS §3/§6）：
///   UserDefaults 就是一个明文 plist。设备被取证、或从备份里恢复一份，
///   里面的 bearer token 就能被直接读走并顶替这台设备。
///   Android 端早就走了 Keystore（`data/SecureTokenStore.kt`），iOS 必须对齐。
///
/// 失败语义（与 Android SecureTokenStore 一致）：**视为未登录，绝不回退明文**。
/// 宁可让用户重新批准一次，也不要把凭据写到不安全的地方。
enum KeychainStore {

    enum Failure: Error {
        /// Keychain 返回的 OSStatus。不往外打日志：里面不含凭据，但没必要外泄。
        case osStatus(OSStatus)
        case badValue
    }

    /// 服务名从 bundle id 派生，不写死业务名，也不含任何本机信息。
    private static var service: String {
        (Bundle.main.bundleIdentifier ?? "litv") + ".credentials"
    }

    private static func query(_ account: String) -> [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
        ]
    }

    /// 读取。条目不存在返回 nil（这是正常状态，不是错误）。
    static func read(_ account: String) -> String? {
        var q = query(account)
        q[kSecReturnData as String] = true
        q[kSecMatchLimit as String] = kSecMatchLimitOne
        var item: CFTypeRef?
        guard SecItemCopyMatching(q as CFDictionary, &item) == errSecSuccess,
              let data = item as? Data,
              let text = String(data: data, encoding: .utf8),
              !text.isEmpty else {
            return nil
        }
        return text
    }

    /// 写入（已存在则更新）。
    ///
    /// 可访问性选 `AfterFirstUnlockThisDeviceOnly`：
    ///   - AfterFirstUnlock：被系统以后台方式唤起时也能读（播放常驻场景需要）；
    ///   - ThisDeviceOnly：不同步 iCloud 钥匙串，也不随备份迁到别的设备。
    ///     对「设备绑定 token」这是正确语义——它只对这台设备有效。
    static func write(_ value: String, account: String) throws {
        guard let data = value.data(using: .utf8), !data.isEmpty else {
            throw Failure.badValue
        }
        let base = query(account)
        let attrs: [String: Any] = [
            kSecValueData as String: data,
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
        ]
        let updated = SecItemUpdate(base as CFDictionary, attrs as CFDictionary)
        if updated == errSecSuccess { return }
        guard updated == errSecItemNotFound else { throw Failure.osStatus(updated) }

        var add = base
        for (k, v) in attrs { add[k] = v }
        let added = SecItemAdd(add as CFDictionary, nil)
        guard added == errSecSuccess else { throw Failure.osStatus(added) }
    }

    /// 删除。条目本来就不存在也算成功。
    static func delete(_ account: String) {
        SecItemDelete(query(account) as CFDictionary)
    }

    /// 一次性迁移：UserDefaults（旧的明文位置）→ Keychain。
    ///
    /// 只在 Keychain 里还没有该条目时才迁移；迁移成功后**删掉旧键**，
    /// 不让明文继续躺在 plist 里。若 Keychain 已有值但旧键还在，也顺手清掉。
    ///
    /// 写 Keychain 失败时**保留**旧值（下次启动还能再试一次），
    /// 但本次返回 nil —— 调用方据此把用户当作未登录。
    static func migrate(account: String, legacyKey: String) -> String? {
        let legacy = UserDefaults.standard.string(forKey: legacyKey) ?? ""
        if let existing = read(account) {
            if !legacy.isEmpty { UserDefaults.standard.removeObject(forKey: legacyKey) }
            return existing
        }
        guard !legacy.isEmpty else { return nil }
        do {
            try write(legacy, account: account)
            UserDefaults.standard.removeObject(forKey: legacyKey)
            return legacy
        } catch {
            return nil
        }
    }
}

/// token / device_key 的业务封装：读取时顺带迁移，写入失败即视为未登录。
///
/// 放在这里而不是塞进 Session，是为了让「凭据怎么存」只有一处实现，
/// Session 只管状态。
enum CredentialStore {

    static let tokenAccount = "device-token"
    static let deviceKeyAccount = "device-key"

    /// 旧的明文键名（历史遗留，迁移后会被删除）
    static let legacyTokenKey = "token"
    static let legacyDeviceKeyKey = "deviceKey"

    // ---------------------------------------------------------------- token

    /// 读 token：Keychain 优先；没有则尝试从旧 UserDefaults 迁移一次。
    static func loadToken() -> String {
        KeychainStore.migrate(account: tokenAccount, legacyKey: legacyTokenKey) ?? ""
    }

    /// 写 token。返回 false 表示**没能存进安全存储**，调用方必须当作未登录处理。
    static func saveToken(_ value: String) -> Bool {
        if value.isEmpty {
            clearToken()
            return true
        }
        do {
            try KeychainStore.write(value, account: tokenAccount)
            UserDefaults.standard.removeObject(forKey: legacyTokenKey)
            return true
        } catch {
            // Keychain 不可用（缺少 entitlement、系统密钥库损坏等）。
            // 不打印错误细节，也不回退 UserDefaults。
            return false
        }
    }

    static func clearToken() {
        KeychainStore.delete(tokenAccount)
        UserDefaults.standard.removeObject(forKey: legacyTokenKey)
    }

    // ---------------------------------------------------------------- device_key

    /// 进程内缓存。
    ///
    /// 为什么必须有：Keychain 写失败时如果每次调用都重新生成 UUID，
    /// **同一次运行里**两次取 deviceKey 就会得到两个不同的值 ——
    /// 服务端会看到一串 pending 设备，等 register 的
    /// `ErrDeviceKeyConflict` 上线后还会直接 409。
    /// 所以"生成一次、本次运行内恒定"是硬要求，写失败也不能破。
    private static var cachedDeviceKey: String?

    /// 设备标识：存 Keychain，重装/清数据后**不变**（AGENTS §3 要求）。
    ///
    /// 旧实现是随机 UUID 存 UserDefaults，重装即变 → 每次重装都要管理员重新批准。
    /// 迁移时保留旧值，避免升级当天掉绑定。
    ///
    /// 兜底：Keychain 写不进去时仍返回本次生成的值（否则 App 连注册都做不了），
    /// 只是它活不过这次安装——这是最坏情况下可接受的下限，且**不会**导致
    /// 同一次运行里身份抖动（见 cachedDeviceKey）。
    ///
    /// 注意：解绑/清 token **绝不**调用这里删 device-key，
    /// 否则重新绑定会被服务端当成一台新设备，又要重新审批。
    static func loadDeviceKey() -> String {
        if let cached = cachedDeviceKey { return cached }
        let value: String
        if let existing = KeychainStore.migrate(
            account: deviceKeyAccount, legacyKey: legacyDeviceKeyKey) {
            value = existing
        } else {
            let fresh = UUID().uuidString.replacingOccurrences(of: "-", with: "")
            // 只在首次生成时尝试落盘；失败不影响本次运行内的稳定性
            try? KeychainStore.write(fresh, account: deviceKeyAccount)
            value = fresh
        }
        cachedDeviceKey = value
        return value
    }
}
