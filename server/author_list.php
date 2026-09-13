<?php
/**
 * dyparse 服务器解析 API - 作者作品列表
 * 服务器带 cookie + a_bogus 签名抓取作者全部作品（解决客户端匿名只能看到部分作品的问题）。
 *
 * 请求：GET /author_list.php
 *   url      : 作者主页链接/分享口令/用户 ID（必填）
 *   max_cursor: 分页游标（默认 0）
 *   count    : 每页数量（默认 18）
 *   X-Token / X-Time / X-Sign 鉴权（与 data.php 一致）
 *
 * 返回：success + author 信息 + items[]（aweme_id/desc/cover 等）+ max_cursor/has_more
 */

require_once __DIR__ . '/config.php';
require_once __DIR__ . '/abogus.php';

header('Content-Type: application/json; charset=utf-8');
error_reporting(0);
ini_set('display_errors', 0);

function fail($msg, $code = 400) {
    if (function_exists('http_response_code')) {
        @http_response_code($code);
    }
    echo json_encode([
        'success' => false,
        'error' => $msg,
        'code' => $code
    ], JSON_UNESCAPED_UNICODE);
    exit;
}

function raw_param($name) {
    $qs = isset($_SERVER['QUERY_STRING']) ? $_SERVER['QUERY_STRING'] : '';
    if (preg_match('/(?:^|&)' . $name . '=([^&]*)/', $qs, $m)) {
        return $m[1];
    }
    return '';
}

function authorize() {
    $token = isset($_SERVER['HTTP_X_TOKEN']) ? $_SERVER['HTTP_X_TOKEN'] : '';
    $time  = isset($_SERVER['HTTP_X_TIME']) ? $_SERVER['HTTP_X_TIME'] : '';
    $sign  = isset($_SERVER['HTTP_X_SIGN']) ? $_SERVER['HTTP_X_SIGN'] : '';
    if ($token === '' || $time === '' || $sign === '') {
        fail('unauthorized', 403);
    }
    if (!hash_equals(API_TOKEN, $token)) {
        fail('unauthorized', 403);
    }
    $nowMs = round(microtime(true) * 1000);
    if (abs($nowMs - intval($time)) > 300000) {
        fail('expired', 403);
    }
    $url = raw_param('url');
    $cursor = raw_param('max_cursor');
    $count = raw_param('count');
    $cursorSuffix = ($cursor !== '' && $cursor !== '0') ? '&max_cursor=' . $cursor : '';
    $countSuffix = ($count !== '' && $count !== '18') ? '&count=' . $count : '';
    $expected = hash_hmac('sha256', $token . $time . $url . $cursorSuffix . $countSuffix, API_HMAC_KEY);
    if (!hash_equals($expected, $sign)) {
        fail('bad signature', 403);
    }
}

function http_get($url, $headers, $timeout = 15) {
    $ch = curl_init($url);
    curl_setopt_array($ch, [
        CURLOPT_RETURNTRANSFER => true,
        CURLOPT_FOLLOWLOCATION => true,
        CURLOPT_MAXREDIRS => 5,
        CURLOPT_TIMEOUT => $timeout,
        CURLOPT_SSL_VERIFYPEER => false,
        CURLOPT_SSL_VERIFYHOST => false,
        CURLOPT_HTTPHEADER => $headers,
    ]);
    $body = curl_exec($ch);
    $status = curl_getinfo($ch, CURLINFO_HTTP_CODE);
    $final_url = curl_getinfo($ch, CURLINFO_EFFECTIVE_URL);
    curl_close($ch);
    return [$body, $status, $final_url];
}

// 预热拿 ttwid + 合并手动 cookie（预热结果缓存 30 分钟，避免每页翻页重复请求 douyin.com）
function get_cookie_header() {
    $manual = (defined('DOUYIN_COOKIE') && DOUYIN_COOKIE !== '') ? DOUYIN_COOKIE : '';

    // 缓存文件：sys_get_temp_dir()/dyparse_ttwid_cache
    $cacheFile = sys_get_temp_dir() . '/dyparse_ttwid_cache';
    $warm = '';
    $cacheValid = false;
    if (is_file($cacheFile)) {
        $mtime = @filemtime($cacheFile);
        if ($mtime !== false && (time() - $mtime) < 1800) {
            $cached = @file_get_contents($cacheFile);
            if ($cached !== false && $cached !== '') {
                $warm = $cached;
                $cacheValid = true;
            }
        }
    }

    if (!$cacheValid) {
        $ch = curl_init('https://www.douyin.com/');
        curl_setopt_array($ch, [
            CURLOPT_RETURNTRANSFER => true,
            CURLOPT_HEADER => true,
            CURLOPT_NOBODY => true,
            CURLOPT_TIMEOUT => 4,
            CURLOPT_SSL_VERIFYPEER => false,
            CURLOPT_SSL_VERIFYHOST => false,
            CURLOPT_USERAGENT => DOUYIN_UA,
        ]);
        $resp = curl_exec($ch);
        curl_close($ch);
        if ($resp) {
            preg_match_all('/^Set-Cookie:\s*([^;]+)/mi', $resp, $m);
            if (!empty($m[1])) {
                $warm = implode('; ', $m[1]);
                @file_put_contents($cacheFile, $warm);
            }
        }
    }

    $map = [];
    if ($warm !== '') {
        foreach (explode(';', $warm) as $c) {
            $c = trim($c);
            if ($c === '') continue;
            $kv = explode('=', $c, 2);
            if (count($kv) === 2) $map[trim($kv[0])] = trim($kv[1]);
        }
    }
    if ($manual !== '') {
        foreach (explode(';', $manual) as $c) {
            $c = trim($c);
            if ($c === '') continue;
            $kv = explode('=', $c, 2);
            if (count($kv) === 2) $map[trim($kv[0])] = trim($kv[1]);
        }
    }
    $parts = [];
    foreach ($map as $k => $v) {
        $parts[] = $k . '=' . $v;
    }
    return implode('; ', $parts);
}

// 提取 sec_user_id：从作者主页链接或分享口令
function resolve_sec_user_id($input) {
    if (preg_match('/user\/([\w\-]+)/', $input, $m)) {
        return $m[1];
    }
    if (preg_match('/sec_uid=([\w\-]+)/', $input, $m2)) {
        return $m2[1];
    }
    // 分享口令：跟随重定向
    preg_match('/https?:\/\/[^\s]+/', $input, $m3);
    if (!empty($m3[0])) {
        $signed_ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36";
        $ch = curl_init(trim($m3[0], " '\""));
        curl_setopt_array($ch, [
            CURLOPT_RETURNTRANSFER => true,
            CURLOPT_FOLLOWLOCATION => true,
            CURLOPT_MAXREDIRS => 5,
            CURLOPT_TIMEOUT => 10,
            CURLOPT_SSL_VERIFYPEER => false,
            CURLOPT_SSL_VERIFYHOST => false,
            CURLOPT_HTTPHEADER => ['User-Agent: ' . $signed_ua],
        ]);
        curl_exec($ch);
        $final_url = curl_getinfo($ch, CURLINFO_EFFECTIVE_URL);
        curl_close($ch);
        if (preg_match('/user\/([\w\-]+)/', $final_url, $m4)) {
            return $m4[1];
        }
        if (preg_match('/sec_uid=([\w\-]+)/', $final_url, $m5)) {
            return $m5[1];
        }
    }
    return '';
}

// ========== 主流程 ==========

// 诊断：data.php?diag=1 查看服务器状态（不鉴权）
if (isset($_GET['diag']) && $_GET['diag'] === '1') {
    echo json_encode([
        'version' => 'author-list-v1',
        'php' => PHP_VERSION,
        'cookie_defined' => defined('DOUYIN_COOKIE') ? 'yes' : 'no',
        'cookie_len' => defined('DOUYIN_COOKIE') ? strlen(DOUYIN_COOKIE) : 0,
        'success' => true,
    ], JSON_UNESCAPED_UNICODE);
    exit;
}

// 诊断2：检查列表 item 是否含完整媒体数据（diag2=1&url=作者主页）
if (isset($_GET['diag2']) && $_GET['diag2'] === '1') {
    $input = urldecode(trim(isset($_GET['url']) ? $_GET['url'] : ''));
    $secUserId = resolve_sec_user_id($input);
    if ($secUserId === '') {
        echo json_encode(['error' => '无法提取作者 ID'], JSON_UNESCAPED_UNICODE);
        exit;
    }
    $cookie = get_cookie_header();
    $msToken = '';
    if (preg_match('/(?:^|;\s*)msToken=([^;]+)/i', $cookie, $mm)) {
        $msToken = trim($mm[1]);
    }
    if ($msToken === '') {
        $alphabet = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789';
        $msToken = '';
        for ($i = 0; $i < 182; $i++) $msToken .= $alphabet[mt_rand(0, strlen($alphabet) - 1)];
        $msToken .= '==';
    }
    $query = 'device_platform=webapp&aid=6383&channel=channel_pc_web&sec_user_id=' . $secUserId
        . '&max_cursor=0&locate_query=false&show_live_replay_strategy=1&need_time_list=1'
        . '&time_list_query=0&whale_cut_token=&cut_version=1&count=18'
        . '&publish_video_strategy_type=2&from_user_page=1'
        . '&update_version_code=170400&pc_client_type=1&pc_libra_divert=Windows'
        . '&support_h265=1&support_dash=1&cpu_core_num=12&version_code=290100&version_name=29.1.0'
        . '&cookie_enabled=true&screen_width=1920&screen_height=1080'
        . '&browser_language=zh-CN&browser_platform=Win32&browser_name=Chrome'
        . '&browser_version=130.0.0.0&browser_online=true&engine_name=Blink'
        . '&engine_version=130.0.0.0&os_name=Windows&os_version=10&device_memory=8'
        . '&platform=PC&downlink=10&effective_type=4g&round_trip_time=100&msToken=' . $msToken;
    $signed_ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36";
    $abogus = dy_sign_query($query, $signed_ua);
    $url = 'https://www.douyin.com/aweme/v1/web/aweme/post/?' . $query . '&a_bogus=' . $abogus;
    $headers = ['User-Agent: ' . $signed_ua, 'Referer: https://www.douyin.com/'];
    if ($cookie !== '') $headers[] = 'Cookie: ' . $cookie;
    list($body, $status, $final) = http_get($url, $headers, 20);
    $json = json_decode($body, true);
    $out = [
        'sec_user_id' => $secUserId,
        'http_status' => $status,
        'status_code' => (is_array($json) && isset($json['status_code'])) ? $json['status_code'] : 'unknown',
        'aweme_list_count' => (is_array($json) && isset($json['aweme_list'])) ? count($json['aweme_list']) : 0,
        'success' => true,
    ];
    if (is_array($json) && isset($json['aweme_list'][0])) {
        $first = $json['aweme_list'][0];
        $out['first_aweme_id'] = isset($first['aweme_id']) ? $first['aweme_id'] : '?';
        $out['first_aweme_id_type'] = isset($first['aweme_id']) ? gettype($first['aweme_id']) : 'missing';
        $out['first_has_video'] = isset($first['video']) ? 'yes' : 'no';
        if (isset($first['video'])) {
            $out['video_keys'] = array_keys($first['video']);
            $out['has_play_addr'] = isset($first['video']['play_addr']) ? 'yes' : 'no';
            if (isset($first['video']['play_addr'])) {
                $out['play_addr_keys'] = array_keys($first['video']['play_addr']);
                $out['play_addr_uri'] = isset($first['video']['play_addr']['uri']) ? 'yes' : 'no';
                $out['play_addr_uri_value'] = isset($first['video']['play_addr']['uri'])
                    ? substr($first['video']['play_addr']['uri'], 0, 30) : '';
                $out['play_addr_uri_type'] = isset($first['video']['play_addr']['uri'])
                    ? gettype($first['video']['play_addr']['uri']) : 'missing';
                $out['play_addr_url_list'] = isset($first['video']['play_addr']['url_list'][0]) ? 'yes' : 'no';
            }
            $out['has_bit_rate'] = isset($first['video']['bit_rate']) ? 'yes' : 'no';
        }
        $out['has_images'] = isset($first['images']) ? 'yes' : 'no';
        // flat 模拟（与主流程相同逻辑）
        $raw_play = null;
        if (isset($first['video']['play_addr']['uri']) && $first['video']['play_addr']['uri'] !== '') {
            $uri = $first['video']['play_addr']['uri'];
            if (strpos($uri, 'mp3') === false) {
                $raw_play = 'http://www.iesdouyin.com/aweme/v1/play/?video_id=' . $uri . '&ratio=1080p&line=0';
            } else {
                $raw_play = $uri;
            }
        }
        $out['flat_raw_play_url_generated'] = $raw_play !== null ? 'yes' : 'no';
        $out['flat_raw_play_url_preview'] = $raw_play ? substr($raw_play, 0, 60) : 'NULL';
    }
    echo json_encode($out, JSON_UNESCAPED_UNICODE);
    exit;
}

authorize();

$rawUrl = raw_param('url');
$input = urldecode($rawUrl);
$input = trim($input);
if ($input === '') {
    fail('请输入作者主页链接');
}
$max_cursor = isset($_GET['max_cursor']) ? intval($_GET['max_cursor']) : 0;
$count = isset($_GET['count']) ? intval($_GET['count']) : 18;
if ($count < 1 || $count > 100) $count = 18;

$secUserId = resolve_sec_user_id($input);
if ($secUserId === '') {
    fail('无法提取作者 ID');
}

// 构建 aweme/post 请求（与客户端 buildSignedAuthorPostApiUrl 一致）
$cookie = get_cookie_header();
$msToken = '';
if (preg_match('/(?:^|;\s*)msToken=([^;]+)/i', $cookie, $mm)) {
    $msToken = trim($mm[1]);
}
if ($msToken === '') {
    $alphabet = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789';
    $msToken = '';
    for ($i = 0; $i < 182; $i++) {
        $msToken .= $alphabet[mt_rand(0, strlen($alphabet) - 1)];
    }
    $msToken .= '==';
}

$query = 'device_platform=webapp&aid=6383&channel=channel_pc_web&sec_user_id=' . $secUserId
    . '&max_cursor=' . $max_cursor
    . '&locate_query=false&show_live_replay_strategy=1&need_time_list=1&time_list_query=0'
    . '&whale_cut_token=&cut_version=1&count=' . $count
    . '&publish_video_strategy_type=2&from_user_page=1'
    . '&update_version_code=170400&pc_client_type=1&pc_libra_divert=Windows'
    . '&support_h265=1&support_dash=1&cpu_core_num=12&version_code=290100&version_name=29.1.0'
    . '&cookie_enabled=true&screen_width=1920&screen_height=1080'
    . '&browser_language=zh-CN&browser_platform=Win32&browser_name=Chrome'
    . '&browser_version=130.0.0.0&browser_online=true&engine_name=Blink'
    . '&engine_version=130.0.0.0&os_name=Windows&os_version=10&device_memory=8'
    . '&platform=PC&downlink=10&effective_type=4g&round_trip_time=100&msToken=' . $msToken;

$signed_ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36";
$abogus = dy_sign_query($query, $signed_ua);
$url = 'https://www.douyin.com/aweme/v1/web/aweme/post/?' . $query . '&a_bogus=' . $abogus;

$headers = [
    'User-Agent: ' . $signed_ua,
    'Referer: https://www.douyin.com/',
];
if ($cookie !== '') $headers[] = 'Cookie: ' . $cookie;

list($body, $status, $final) = http_get($url, $headers, 20);
if ($body === false || $body === '') {
    fail('作者列表请求失败', 500);
}
$json = json_decode($body, true);
if (!is_array($json)) {
    fail('作者列表响应解析失败（HTTP ' . $status . '）', 500);
}
if (isset($json['status_code']) && intval($json['status_code']) !== 0) {
    fail('作者列表接口返回 status ' . $json['status_code'], 500);
}
if (!isset($json['aweme_list']) || !is_array($json['aweme_list'])) {
    fail('作者列表无数据', 500);
}

$items = [];
$flatOkCount = 0;
$flatFailCount = 0;
foreach ($json['aweme_list'] as $aw) {
    // 拍平关键字段到顶层（客户端直接读取，避免深嵌套提取失败），同时保留完整结构
    $raw_play = null;
    if (isset($aw['video']['play_addr']['uri']) && $aw['video']['play_addr']['uri'] !== '') {
        $uri = $aw['video']['play_addr']['uri'];
        if (strpos($uri, 'mp3') === false) {
            $raw_play = 'http://www.iesdouyin.com/aweme/v1/play/?video_id=' . $uri . '&ratio=1080p&line=0';
        } else {
            $raw_play = $uri;
        }
    }
    if ($raw_play === null && isset($aw['video']['play_addr']['url_list'][0])) {
        $raw_play = $aw['video']['play_addr']['url_list'][0];
    }
    if ($raw_play === null && isset($aw['video']['bit_rate'][0]['play_addr']['uri'])) {
        $raw_play = 'http://www.iesdouyin.com/aweme/v1/play/?video_id=' . $aw['video']['bit_rate'][0]['play_addr']['uri'] . '&ratio=1080p&line=0';
    }

    $cover = null;
    if (isset($aw['video']['cover']['url_list'][0])) {
        $cover = $aw['video']['cover']['url_list'][0];
    } elseif (isset($aw['video']['origin_cover']['url_list'][0])) {
        $cover = $aw['video']['origin_cover']['url_list'][0];
    } elseif (isset($aw['images'][0]['url_list'][0])) {
        $cover = $aw['images'][0]['url_list'][0];
    }

    $images = [];
    $live_photos = [];
    $image_sources = [];
    if (isset($aw['image_post_info']['images']) && is_array($aw['image_post_info']['images'])) {
        $image_sources = $aw['image_post_info']['images'];
    } elseif (isset($aw['image_list']) && is_array($aw['image_list'])) {
        $image_sources = $aw['image_list'];
    } elseif (isset($aw['images']) && is_array($aw['images'])) {
        $image_sources = $aw['images'];
    } elseif (isset($aw['image_infos']) && is_array($aw['image_infos'])) {
        $image_sources = $aw['image_infos'];
    } elseif (isset($aw['original_images']) && is_array($aw['original_images'])) {
        $image_sources = $aw['original_images'];
    }
    foreach ($image_sources as $img) {
        if (!is_array($img)) continue;
        $u = isset($img['url_list'][0]) ? $img['url_list'][0]
            : (isset($img['download_url_list'][0]) ? $img['download_url_list'][0] : null);
        $images[] = $u;
        // 实况图视频地址（与客户端 DouyinGalleryMediaResolver 一致）
        $live_photo = null;
        $live_video = isset($img['video']) && is_array($img['video']) ? $img['video'] : null;
        if ($live_video !== null) {
            if (isset($live_video['play_addr']['uri'])) {
                $live_uri = $live_video['play_addr']['uri'];
                if (strpos($live_uri, 'http') === 0) {
                    $live_photo = $live_uri;
                } elseif (strpos($live_uri, 'mp3') === false) {
                    $live_photo = 'http://www.iesdouyin.com/aweme/v1/play/?video_id=' . $live_uri . '&ratio=1080p&line=0';
                } else {
                    $live_photo = $live_uri;
                }
            }
            if ($live_photo === null) {
                foreach (['play_addr_h264', 'play_addr', 'play_addr_lowbr', 'download_addr'] as $lk) {
                    if (isset($live_video[$lk]['url_list'][0])) {
                        $live_photo = $live_video[$lk]['url_list'][0];
                        break;
                    }
                    if (isset($live_video[$lk]['uri'])) {
                        $live_uri2 = $live_video[$lk]['uri'];
                        if (strpos($live_uri2, 'http') === 0) {
                            $live_photo = $live_uri2;
                        } elseif (strpos($live_uri2, 'mp3') === false) {
                            $live_photo = 'http://www.iesdouyin.com/aweme/v1/play/?video_id=' . $live_uri2 . '&ratio=1080p&line=0';
                        } else {
                            $live_photo = $live_uri2;
                        }
                        break;
                    }
                }
            }
        }
        $live_photos[] = $live_photo;
    }

    $item = $aw;
    // 拍平字段（顶层）
    $item['flat_raw_play_url'] = $raw_play;
    $item['flat_cover'] = $cover;
    $item['flat_images'] = $images;
    $item['flat_live_photos'] = $live_photos;
    $item['flat_type'] = !empty($images) ? 'image' : 'video';
    if ($raw_play !== null) {
        $flatOkCount++;
    } else {
        $flatFailCount++;
    }
    $items[] = $item;
}

echo json_encode([
    'success' => true,
    'author_name' => isset($json['user']['nickname']) ? $json['user']['nickname'] : null,
    'author_uid' => isset($json['user']['uid']) ? $json['user']['uid'] : null,
    'author_sec_uid' => $secUserId,
    'avatar_url' => isset($json['user']['avatar_thumb']['url_list'][0])
        ? $json['user']['avatar_thumb']['url_list'][0] : null,
    'max_cursor' => isset($json['max_cursor']) ? intval($json['max_cursor']) : 0,
    'has_more' => isset($json['has_more']) ? (bool)$json['has_more'] : false,
    'total_count' => isset($json['total']) ? intval($json['total']) : null,
    'items' => $items,
    'auth_mode' => 'cookie',
    // 诊断：flat 字段覆盖率（客户端是否拿到完整播放地址）
    'flat_ok_count' => $flatOkCount,
    'flat_fail_count' => $flatFailCount,
], JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES);
