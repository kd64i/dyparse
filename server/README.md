# dyparse 服务器解析 API

App 的解析逻辑完全在服务器执行，App 只调用本 API。

服务器端解析流程：**detail API（带 a_bogus 签名 + cookie）→ 分享页直抓兜底**。
- `abogus.php` 提供 a_bogus 签名（PHP 实现），detail API 无签名会被抖音返回 403 blocked。
- 分享页直抓不需要签名，作为 detail API 失败时的兜底。

## 部署步骤

1. 将本目录下 **`data.php`、`config.php`、`abogus.php`、`author_list.php`** 上传到虚拟主机（建议放在 web 根目录下的 `api/` 子目录）
   - **四个文件必须在同一目录**（用 `__DIR__` 互相引入）
   - `abogus.php` 是 a_bogus 签名实现（detail API 必需，否则抖音返回 403 blocked）
   - `author_list.php` 是作者作品列表接口（批量解析用，服务器带 cookie 抓完整列表）
2. 复制 `config.example.php` 为 `config.php` 并编辑：
   - `API_TOKEN`：与客户端一致（客户端读 `local.properties` 的 `SERVER_API_TOKEN`）
   - `API_HMAC_KEY`：与客户端一致（客户端读 `local.properties` 的 `SERVER_HMAC_KEY`）
   - `DOUYIN_COOKIE`：填入你自己的抖音登录 Cookie（浏览器登录抖音 → F12 → Application → Cookies → 复制整段）
   - `DOUYIN_COOKIE_ENABLED`：设为 `true`
   - ⚠️ `config.php` 已被 `.gitignore` 忽略，**不要提交、不要公开**。它包含的登录 Cookie 等同于账号凭据。
3. 测试：浏览器访问 `https://<你的域名>/api/data.php?diag4=1&url=<抖音链接>` 验证 a_bogus 签名生效（detail_status 应为 200、has_aweme_detail=yes）

## 诊断端点（无需鉴权，浏览器直接访问）

- `data.php?diag=1` — 服务器状态（版本/cookie 配置/预热结果）
- `data.php?diag2=1&url=xxx` — 分享页与 detail API 实际返回对比
- `data.php?diag3=1&url=xxx` — 分享页 ROUTER_DATA 数据结构
- `data.php?diag4=1&url=xxx` — **a_bogus 签名后的 detail API 是否可用**（部署后必测）

## API 规范

```
请求：GET /data.php?url=<分享链接|作品ID>[&mode=cookie][&original=1][&highest=1]
头：
  X-Token: <API_TOKEN>
  X-Time:  <毫秒时间戳>
  X-Sign:  HMAC-SHA256(API_TOKEN + X-Time + 编码url + mode/original/highest后缀, API_HMAC_KEY)
```

参数说明：
- `mode=cookie` — 服务器用手动配置的登录 cookie（批量/保存原画质/最高画质）；不带则匿名（单条解析）
- `original=1` — 原画质（download_addr，ratio=default）
- `highest=1` — 最高画质（bit_rate 最高档，优先级高于 original）

返回（JSON）：
```json
{
  "success": true,
  "author": "作者名",
  "author_uid": "...",
  "author_sec_uid": "...",
  "title": "标题",
  "video_id": "作品ID",
  "type": "video" | "image",
  "play_url": "最终播放地址（已跟随重定向）",
  "raw_play_url": "原始播放地址（供过期后刷新）",
  "cover": "封面URL",
  "images": ["图1", "图2"] | null,
  "gallery_media": [{"index":0,"image_url":"...","has_live_photo":false}],
  "duration": 12.3,
  "timestamp": 1700000000,
  "auth_mode": "cookie" | "anon"
}
```

错误：
```json
{ "success": false, "error": "原因", "code": 400 }
```

## 安全说明

- **鉴权**：token + HMAC 签名 + 时间戳防重放（±300 秒）
- **Cookie 保护**：Cookie 只在服务器内存/代码中使用，不出现在响应中；`config.php` 禁止公开
- **限速**：`RATE_LIMIT_MS` 控制同一 IP 请求间隔，防服务器 IP 被抖音风控

## 字段映射

服务器返回字段与 App 的 `ParseResult.Success` 对应：
`author`→author, `author_uid`→authorUid, `author_sec_uid`→authorSecUid, `title`→title, `type`→type, `play_url`→playUrl, `raw_play_url`→rawPlayUrl, `images`→images, `gallery_media`→galleryMedia, `duration`→duration, `timestamp`→timestamp, `video_id`→videoId, `cover`→cover, `input_url`→inputUrl, `resolved_url`→resolvedUrl
