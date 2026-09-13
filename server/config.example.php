<?php
/**
 * dyparse 服务器解析 API - 配置模板
 *
 * 使用方法：
 *   1. 复制本文件为同目录下的 config.php
 *   2. 把下面的三个值改成你自己的强随机值 / 你自己的 Cookie
 *   3. config.php 已被 .gitignore 忽略，永远不会被提交
 *
 * ⚠️ 安全说明：
 *  - 本模板不含任何真实凭据，可以安全公开。
 *  - 真实的 config.php 包含 token / cookie / 密钥，禁止提交到 git、禁止公开。
 *  - DOUYIN_COOKIE 是你抖音账号的完整登录态，一旦泄露等同于账号被接管，
 *    请只在服务器上保存，并定期更换。
 */

// ========== API 鉴权 ==========
// 客户端请求必须携带 X-Token 头，且值与此一致。
// 客户端的值通过 local.properties 的 SERVER_API_TOKEN 注入（见根目录 README）。
define('API_TOKEN', 'change_me_to_a_long_random_string');

// ========== 请求签名（HMAC）==========
// 客户端对请求参数做 HMAC-SHA256，签名放 X-Sign 头；服务器校验防篡改/防重放。
// 客户端的值通过 local.properties 的 SERVER_HMAC_KEY 注入。
define('API_HMAC_KEY', 'change_me_to_another_long_random_string');

// ========== 抖音 Cookie（手动配置）==========
// 服务器请求抖音时携带此 Cookie，可显著提升解析成功率、获取登录态数据。
// 获取方式：浏览器登录抖音 -> F12 -> Application -> Cookies -> 复制整段 Cookie 字符串
// 注意：Cookie 会过期，需要定期更新。
// 留空字符串表示不使用 Cookie（此时批量解析 / 原画质保存会受限）。
define('DOUYIN_COOKIE', '');

// Cookie 是否启用（true=批量解析/原画质/最高画质保存使用登录 cookie；false=全部匿名）
define('DOUYIN_COOKIE_ENABLED', false);

// ========== 请求头 ==========
// 服务器请求抖音时使用的 UA 和 Referer
define('DOUYIN_UA', 'Mozilla/5.0 (Linux; Android 13; SM-G998B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36');
define('DOUYIN_REFERER', 'https://www.douyin.com/');

// ========== 请求限速（防服务器 IP 被抖音风控）==========
// 同一 IP 两次请求的最小间隔（毫秒），0 表示不限
define('RATE_LIMIT_MS', 300);
