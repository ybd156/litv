import { http } from '../http'

/**
 * 管理台预览用的播放信息。
 *
 * `url` 是**带签名的短期地址**：AGENTS §3 要求它不进日志、不进错误上报，
 * 所以签发入口只放在这里，方便审「谁会手里拿着它」。
 */
export interface WebPlay {
  kind: string
  url: string
  channel: { id: number; name: string; logo: string }
}

/**
 * 取某频道的预览播放地址（服务端按 HMAC 签名，2 小时过期）。
 *
 * 为什么单独成模块而不是在视图里直接 request：
 * 预览页是唯一会拿到可播放 URL 的地方；收在这里才能一眼看出
 * 「有没有人把它打进了日志 / toast」。
 */
export function webPlay(channelId: number): Promise<WebPlay> {
  return http.get<WebPlay>(`web/play/${channelId}`)
}
