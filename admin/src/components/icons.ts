/** 内联 SVG 图标（不引 @vicons，省体积）：路径数据集中放这里，Icon.vue 只负责渲染 */
export type IconName =
  | 'dashboard'
  | 'sources'
  | 'channels'
  | 'epg'
  | 'devices'
  | 'settings'
  | 'refresh'
  | 'plus'
  | 'trash'
  | 'edit'
  | 'search'
  | 'preview'
  | 'grip'
  | 'key'
  | 'copy'
  | 'check'
  | 'close'
  | 'warning'
  | 'link'
  | 'up'
  | 'down'
  | 'play'
  | 'user'
  | 'server'

export const ICON_PATHS: Record<IconName, string> = {
  dashboard:
    '<rect x="3" y="3" width="7.5" height="7.5" rx="1.6"/><rect x="13.5" y="3" width="7.5" height="7.5" rx="1.6"/><rect x="3" y="13.5" width="7.5" height="7.5" rx="1.6"/><rect x="13.5" y="13.5" width="7.5" height="7.5" rx="1.6"/>',
  sources:
    '<ellipse cx="12" cy="6" rx="8" ry="3"/><path d="M4 6v5c0 1.7 3.6 3 8 3s8-1.3 8-3V6"/><path d="M4 11v5c0 1.7 3.6 3 8 3s8-1.3 8-3v-5"/>',
  channels: '<rect x="2" y="7" width="20" height="14" rx="2.4"/><path d="M17.5 2.5 12 7 6.5 2.5"/>',
  epg: '<rect x="3" y="4.5" width="18" height="16.5" rx="2.2"/><path d="M8 2.5v4M16 2.5v4M3 10h18"/>',
  devices: '<rect x="2" y="4" width="20" height="12.5" rx="2.2"/><path d="M8 21h8M12 16.5V21"/>',
  settings:
    '<path d="M4 6h9M18 6h2M4 12h3M11 12h9M4 18h9M18 18h2"/><circle cx="15.5" cy="6" r="2.2"/><circle cx="9.5" cy="12" r="2.2"/><circle cx="15.5" cy="18" r="2.2"/>',
  refresh: '<path d="M20.5 12a8.5 8.5 0 1 1-2.8-6.3"/><path d="M20.5 3.5V9h-5.5"/>',
  plus: '<path d="M12 5v14M5 12h14"/>',
  trash: '<path d="M3.5 6.5h17M9 6.5V4h6v2.5M6 6.5 7 20.5h10l1-14"/>',
  edit: '<path d="M12 20.5h9"/><path d="M16.8 3.6a2.1 2.1 0 0 1 3 3L7.2 19.2 3 20.5l1.3-4.2z"/>',
  search: '<circle cx="11" cy="11" r="7"/><path d="M20.5 20.5 16.3 16.3"/>',
  preview:
    '<path d="M2.5 12S6 5.5 12 5.5 21.5 12 21.5 12 18 18.5 12 18.5 2.5 12 2.5 12z"/><circle cx="12" cy="12" r="3"/>',
  grip: '<circle cx="9.5" cy="6" r="1.3"/><circle cx="14.5" cy="6" r="1.3"/><circle cx="9.5" cy="12" r="1.3"/><circle cx="14.5" cy="12" r="1.3"/><circle cx="9.5" cy="18" r="1.3"/><circle cx="14.5" cy="18" r="1.3"/>',
  key: '<circle cx="8" cy="15.5" r="4.2"/><path d="M11 12.5 20 3.5M17.5 3.5 21 7M14.5 6.5 18 10"/>',
  copy: '<rect x="9" y="9" width="12" height="12" rx="2.2"/><path d="M5.5 15V5.5a2 2 0 0 1 2-2H15"/>',
  check: '<path d="M20 6.5 9.5 17 4 11.5"/>',
  close: '<path d="M18 6 6 18M6 6l12 12"/>',
  warning: '<path d="M12 3.2 21 19.5H3z"/><path d="M12 9.5v4.5M12 17h.01"/>',
  link: '<path d="M10.5 13.5a4.5 4.5 0 0 0 6.4 0l2.3-2.3a4.5 4.5 0 0 0-6.4-6.4l-1.2 1.2"/><path d="M13.5 10.5a4.5 4.5 0 0 0-6.4 0l-2.3 2.3a4.5 4.5 0 0 0 6.4 6.4l1.2-1.2"/>',
  up: '<path d="M12 19V5M6 11l6-6 6 6"/>',
  down: '<path d="M12 5v14M6 13l6 6 6-6"/>',
  play: '<path d="M8 5.2 19 12 8 18.8z"/>',
  user: '<circle cx="12" cy="8" r="4"/><path d="M4.5 20.5c0-3.8 3.4-5.8 7.5-5.8s7.5 2 7.5 5.8"/>',
  server:
    '<rect x="3" y="4" width="18" height="7" rx="2"/><rect x="3" y="13" width="18" height="7" rx="2"/><path d="M7 7.5h.01M7 16.5h.01"/>',
}
