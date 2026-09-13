<?php
/**
 * dyparse 服务器解析 API
 * 结构参照旧版（class + $_GET + 简单 curl），避免触发虚拟主机 WAF。
 */

// 用 __DIR__ 保证 config.php 与 data.php 必须在同一目录
require_once __DIR__ . '/config.php';
require_once __DIR__ . '/abogus.php';

header('Content-Type: application/json; charset=utf-8');
error_reporting(0);
ini_set('display_errors', 0);

class DyParser {
    private $headers = [
        'User-Agent: Mozilla/5.0 (Linux; Android 13; SM-G998B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36',
        'Referer: https://www.douyin.com/'
    ];

    // ===== 最高画质候选收集（1:1 翻译自 dyparseX 参考项目 DouyinVideoDownloadResolver）=====
    private function collectVideoCandidates($item) {
        $video = isset($item['video']) && is_array($item['video']) ? $item['video'] : null;
        if ($video === null) return [];
        $candidates = [];

        $this->collectOriginalPlayEndpointCandidates($video, $candidates);

        if (isset($video['bit_rate']) && is_array($video['bit_rate'])) {
            foreach ($video['bit_rate'] as $index => $entry) {
                if (!is_array($entry)) continue;
                $this->collectAddressCandidates($entry, isset($entry['play_addr']) ? $entry['play_addr'] : null, 3000 - $index, isset($entry['bit_rate']) ? intval($entry['bit_rate']) : 0, $candidates);
                foreach ($entry as $key => $value) {
                    if (stripos($key, 'play_addr') === 0 || stripos($key, 'download_addr') === 0 || stripos($key, 'play_api') === 0) {
                        $this->collectAddressCandidates($entry, $value, 3200 - $index, isset($entry['bit_rate']) ? intval($entry['bit_rate']) : 0, $candidates);
                    }
                }
            }
        }

        $this->collectAddressCandidates($video, isset($video['play_addr_265']) ? $video['play_addr_265'] : null, 2500, 0, $candidates);
        $this->collectAddressCandidates($video, isset($video['play_addr_h264']) ? $video['play_addr_h264'] : null, 2400, 0, $candidates);
        $this->collectAddressCandidates($video, isset($video['play_addr']) ? $video['play_addr'] : null, 2000, 0, $candidates);
        $this->collectAddressCandidates($video, isset($video['download_addr']) ? $video['download_addr'] : null, 1000, 0, $candidates);
        foreach ($video as $key => $value) {
            if (stripos($key, 'play_addr') === 0 || stripos($key, 'download_addr') === 0 || stripos($key, 'play_api') === 0) {
                $this->collectAddressCandidates($video, $value, 1800, 0, $candidates);
            }
        }

        // distinctBy url
        $seen = [];
        $result = [];
        foreach ($candidates as $c) {
            if (isset($seen[$c['url']])) continue;
            if ($c['url'] === '') continue;
            $seen[$c['url']] = true;
            $result[] = $c;
        }
        return $result;
    }

    private function collectOriginalPlayEndpointCandidates($video, &$candidates) {
        $videoHeight = isset($video['height']) ? intval($video['height']) : ($this->parseResolutionHeight($video) ?: 0);
        $videoWidth = isset($video['width']) ? intval($video['width']) : (($videoHeight > 0) ? intval($videoHeight * 16 / 9) : 0);
        $videoDataSize = isset($video['data_size']) ? intval($video['data_size']) : 0;
        $videoQualityType = isset($video['quality_type']) ? intval($video['quality_type']) : 0;

        $this->addOriginalPlayEndpointCandidate(isset($video['play_addr']['uri']) ? $video['play_addr']['uri'] : null, $videoWidth, $videoHeight, 0, $videoDataSize, $videoQualityType, null, 2600, $candidates);
        $this->addOriginalPlayEndpointCandidate(isset($video['vid']) ? $video['vid'] : null, $videoWidth, $videoHeight, 0, $videoDataSize, $videoQualityType, null, 2550, $candidates);
        $this->addOriginalPlayEndpointCandidate(isset($video['download_addr']['uri']) ? $video['download_addr']['uri'] : null, $videoWidth, $videoHeight, 0, $videoDataSize, $videoQualityType, null, 2500, $candidates);

        if (isset($video['bit_rate']) && is_array($video['bit_rate'])) {
            foreach ($video['bit_rate'] as $index => $entry) {
                if (!is_array($entry)) continue;
                $playAddr = (isset($entry['play_addr']) && is_array($entry['play_addr'])) ? $entry['play_addr'] : null;
                $entryHeight = 0;
                if ($playAddr && isset($playAddr['height'])) $entryHeight = intval($playAddr['height']);
                elseif (isset($entry['height'])) $entryHeight = intval($entry['height']);
                elseif ($this->parseResolutionHeight($entry) !== null) $entryHeight = $this->parseResolutionHeight($entry);
                elseif ($playAddr && $this->parseResolutionHeight($playAddr) !== null) $entryHeight = $this->parseResolutionHeight($playAddr);
                else $entryHeight = $videoHeight;
                $entryWidth = 0;
                if ($playAddr && isset($playAddr['width'])) $entryWidth = intval($playAddr['width']);
                elseif (isset($entry['width'])) $entryWidth = intval($entry['width']);
                elseif ($entryHeight > 0) $entryWidth = intval($entryHeight * 16 / 9);
                else $entryWidth = $videoWidth;
                $entryBitRate = isset($entry['bit_rate']) ? intval($entry['bit_rate']) : 0;
                $entryDataSize = 0;
                if ($playAddr && isset($playAddr['data_size'])) $entryDataSize = intval($playAddr['data_size']);
                elseif (isset($entry['data_size'])) $entryDataSize = intval($entry['data_size']);
                $entryQualityType = 0;
                if (isset($entry['quality_type'])) $entryQualityType = intval($entry['quality_type']);
                elseif ($playAddr && isset($playAddr['quality_type'])) $entryQualityType = intval($playAddr['quality_type']);
                $ratio = ($entryHeight > 0) ? ($entryHeight . 'p') : null;

                $this->addOriginalPlayEndpointCandidate(isset($entry['play_addr']['uri']) ? $entry['play_addr']['uri'] : null, $entryWidth, $entryHeight, $entryBitRate, $entryDataSize, $entryQualityType, $ratio, 3600 - $index, $candidates);
                $this->addOriginalPlayEndpointCandidate(isset($entry['play_addr_h264']['uri']) ? $entry['play_addr_h264']['uri'] : null, $entryWidth, $entryHeight, $entryBitRate, $entryDataSize, $entryQualityType, $ratio, 3500 - $index, $candidates);
                $this->addOriginalPlayEndpointCandidate(isset($entry['play_addr_265']['uri']) ? $entry['play_addr_265']['uri'] : null, $entryWidth, $entryHeight, $entryBitRate, $entryDataSize, $entryQualityType, $ratio, 3450 - $index, $candidates);
                $this->addOriginalPlayEndpointCandidate(isset($entry['download_addr']['uri']) ? $entry['download_addr']['uri'] : null, $entryWidth, $entryHeight, $entryBitRate, $entryDataSize, $entryQualityType, $ratio, 3400 - $index, $candidates);
            }
        }
    }

    private function addOriginalPlayEndpointCandidate($uri, $width, $height, $bitRate, $dataSize, $qualityType, $ratio, $sourcePriority, &$candidates) {
        if ($uri === null || $uri === '') return;
        if (stripos($uri, 'http') === 0) return;
        if (stripos($uri, 'mp3') !== false) return;
        $normalizedHeight = max(0, intval($height));
        $normalizedWidth = ($width > 0) ? intval($width) : (($normalizedHeight > 0) ? intval($normalizedHeight * 16 / 9) : 0);
        $candidates[] = [
            'url' => $this->buildOriginalPlayEndpointUrl($uri, $ratio),
            'width' => $normalizedWidth,
            'height' => $normalizedHeight,
            'bitRate' => $bitRate,
            'dataSize' => $dataSize,
            'qualityType' => $qualityType,
            'sourcePriority' => $sourcePriority,
            'directUrl' => false,
        ];
    }

    private function collectAddressCandidates($container, $address, $sourcePriority, $fallbackBitRate, &$candidates) {
        if (!is_array($address)) return;
        $urls = [];
        if (isset($address['url_list']) && is_array($address['url_list'])) {
            foreach ($address['url_list'] as $u) {
                if (is_string($u) && $u !== '') $urls[] = $u;
            }
        }
        // sortedWith: directUrl 优先，watermark=0 优先
        usort($urls, function ($a, $b) {
            $aDirect = $this->isDirectMediaUrl($a) ? 0 : 1;
            $bDirect = $this->isDirectMediaUrl($b) ? 0 : 1;
            if ($aDirect !== $bDirect) return $aDirect - $bDirect;
            $aNoWm = (stripos($a, 'watermark=0') !== false) ? 0 : 1;
            $bNoWm = (stripos($b, 'watermark=0') !== false) ? 0 : 1;
            return $aNoWm - $bNoWm;
        });
        if (empty($urls)) return;

        $width = 0;
        if (isset($address['width'])) $width = intval($address['width']);
        elseif (isset($container['width'])) $width = intval($container['width']);
        $fallbackHeight = 0;
        if (isset($address['height'])) $fallbackHeight = intval($address['height']);
        elseif (isset($container['height'])) $fallbackHeight = intval($container['height']);
        elseif ($this->parseResolutionHeight($container) !== null) $fallbackHeight = $this->parseResolutionHeight($container);
        elseif ($this->parseResolutionHeight($address) !== null) $fallbackHeight = $this->parseResolutionHeight($address);
        $bitRate = 0;
        if (isset($address['bit_rate'])) $bitRate = intval($address['bit_rate']);
        elseif (isset($container['bit_rate'])) $bitRate = intval($container['bit_rate']);
        else $bitRate = $fallbackBitRate;
        $dataSize = 0;
        if (isset($address['data_size'])) $dataSize = intval($address['data_size']);
        elseif (isset($container['data_size'])) $dataSize = intval($container['data_size']);
        $qualityType = 0;
        if (isset($container['quality_type'])) $qualityType = intval($container['quality_type']);
        elseif (isset($address['quality_type'])) $qualityType = intval($address['quality_type']);

        foreach ($urls as $index => $url) {
            $height = $this->parseUrlRatioHeight($url);
            if ($height === null) $height = $fallbackHeight;
            $normalizedWidth = ($width > 0) ? intval($width) : (($height > 0) ? intval($height * 16 / 9) : 0);
            $candidates[] = [
                'url' => $url,
                'width' => $normalizedWidth,
                'height' => $height,
                'bitRate' => $bitRate,
                'dataSize' => $dataSize,
                'qualityType' => $qualityType,
                'sourcePriority' => $sourcePriority - $index,
                'directUrl' => $this->isDirectMediaUrl($url),
            ];
        }
    }

    private function buildOriginalPlayEndpointUrl($videoId, $ratio = null) {
        $encodedVideoId = rawurlencode($videoId);
        $encodedRatio = rawurlencode(($ratio !== null && $ratio !== '') ? $ratio : 'default');
        return 'https://www.iesdouyin.com/aweme/v1/play/' .
            '?video_id=' . $encodedVideoId . '&ratio=' . $encodedRatio . '&line=0' .
            '&is_play_url=1&watermark=0&source=PackSourceEnum_PUBLISH';
    }

    private function parseResolutionHeight($source) {
        if (!is_array($source)) return null;
        $candidates = [];
        if (isset($source['gear_name']) && is_string($source['gear_name'])) $candidates[] = $source['gear_name'];
        if (isset($source['quality_desc']) && is_string($source['quality_desc'])) $candidates[] = $source['quality_desc'];
        if (isset($source['ratio']) && is_string($source['ratio'])) $candidates[] = $source['ratio'];
        foreach ($candidates as $text) {
            if (preg_match('/(?<!\d)(2160|1440|1280|1080|960|720|540|480|360)(?:p)?(?!\d)/', $text, $m)) {
                return intval($m[1]);
            }
        }
        return null;
    }

    private function isDirectMediaUrl($url) {
        return stripos($url, '/aweme/v1/play/') === false;
    }

    private function parseUrlRatioHeight($url) {
        if (preg_match('/[?&]ratio=([^&]+)/i', $url, $m)) {
            $ratio = $m[1];
            if (strtolower($ratio) === 'default') return null;
            if (preg_match('/(?<!\d)(2160|1440|1280|1080|960|720|540|480|360)(?:p)?(?!\d)/', $ratio, $m2)) {
                return intval($m2[1]);
            }
        }
        return null;
    }

    /** 取最高画质候选（compareBy: height→width→pixel→bitRate→dataSize→qualityType→directUrl→sourcePriority） */
    private function pickHighestCandidate($candidates) {
        $best = null;
        foreach ($candidates as $c) {
            if ($best === null) { $best = $c; continue; }
            if ($c['height'] > $best['height']) { $best = $c; continue; }
            if ($c['height'] < $best['height']) continue;
            if ($c['width'] > $best['width']) { $best = $c; continue; }
            if ($c['width'] < $best['width']) continue;
            $pA = $c['height'] * $c['width'];
            $pB = $best['height'] * $best['width'];
            if ($pA > $pB) { $best = $c; continue; }
            if ($pA < $pB) continue;
            if ($c['bitRate'] > $best['bitRate']) { $best = $c; continue; }
            if ($c['bitRate'] < $best['bitRate']) continue;
            if ($c['dataSize'] > $best['dataSize']) { $best = $c; continue; }
            if ($c['dataSize'] < $best['dataSize']) continue;
            if ($c['qualityType'] > $best['qualityType']) { $best = $c; continue; }
            if ($c['qualityType'] < $best['qualityType']) continue;
            $cDirect = $c['directUrl'] ? 1 : 0;
            $bDirect = $best['directUrl'] ? 1 : 0;
            if ($cDirect > $bDirect) { $best = $c; continue; }
            if ($cDirect < $bDirect) continue;
            if ($c['sourcePriority'] > $best['sourcePriority']) { $best = $c; continue; }
        }
        return $best;
    }

    // token + HMAC 签名鉴权（X-Token / X-Time / X-Sign）
    // 用 403 而非 401：避免 IIS 对 401 触发认证挑战（弹登录框）
    private function authorize() {
        $token = isset($_SERVER['HTTP_X_TOKEN']) ? $_SERVER['HTTP_X_TOKEN'] : '';
        $time  = isset($_SERVER['HTTP_X_TIME']) ? $_SERVER['HTTP_X_TIME'] : '';
        $sign  = isset($_SERVER['HTTP_X_SIGN']) ? $_SERVER['HTTP_X_SIGN'] : '';
        if ($token === '' || $time === '' || $sign === '') {
            $this->fail('unauthorized', 403);
        }
        if (!hash_equals(API_TOKEN, $token)) {
            $this->fail('unauthorized', 403);
        }
        // 时间戳防重放：±300 秒
        $nowMs = round(microtime(true) * 1000);
        if (abs($nowMs - intval($time)) > 300000) {
            $this->fail('expired', 403);
        }
        // 待签名串 = token + time + 原始url + mode + original + highest后缀（与客户端一致）
        $url = $this->raw_param('url');
        $mode = $this->raw_param('mode');
        $original = $this->raw_param('original');
        $highest = $this->raw_param('highest');
        $modeSuffix = ($mode === 'cookie') ? '&mode=cookie' : '';
        $originalSuffix = ($original !== '' && $original !== '0') ? '&original=1' : '';
        $highestSuffix = ($highest !== '' && $highest !== '0') ? '&highest=1' : '';
        $expected = hash_hmac('sha256', $token . $time . $url . $modeSuffix . $originalSuffix . $highestSuffix, API_HMAC_KEY);
        if (!hash_equals($expected, $sign)) {
            $this->fail('bad signature', 403);
        }
    }

    // 从原始 QUERY_STRING 取参数（未解码，与客户端签名一致）
    private function raw_param($name) {
        $qs = isset($_SERVER['QUERY_STRING']) ? $_SERVER['QUERY_STRING'] : '';
        if (preg_match('/(?:^|&)' . $name . '=([^&]*)/', $qs, $m)) {
            return $m[1];
        }
        return '';
    }

    private function fail($msg, $code = 400) {
        if (function_exists('http_response_code')) {
            @http_response_code($code);
        }
        echo json_encode([
            'success' => false,
            'error' => $msg === '' ? 'unknown error' : $msg,
            'code' => $code
        ], JSON_UNESCAPED_UNICODE);
        exit;
    }

    private function get_redirected_url($url) {
        $ch = curl_init($url);
        curl_setopt_array($ch, [
            CURLOPT_RETURNTRANSFER => true,
            CURLOPT_FOLLOWLOCATION => true,
            CURLOPT_MAXREDIRS => 5,
            CURLOPT_TIMEOUT => 10,
            CURLOPT_SSL_VERIFYPEER => false,
            CURLOPT_SSL_VERIFYHOST => false,
            CURLOPT_HTTPHEADER => $this->headers,
        ]);
        curl_exec($ch);
        if (curl_errno($ch)) {
            curl_close($ch);
            return false;
        }
        $redirected_url = curl_getinfo($ch, CURLINFO_EFFECTIVE_URL);
        curl_close($ch);
        return $redirected_url;
    }

    // 请求带 cookie：始终先预热拿 ttwid/msToken（detail API 必需的匿名会话基础），
    // $use_manual=true 时再合并手动配置的登录 cookie（批量/原画质/最高画质），
    // 手动 cookie 覆盖预热同名键。没有 ttwid 时 detail API 会被风控拒绝（匿名数据/解析不全）。
    // 预热结果缓存 30 分钟，避免每次解析都请求 douyin.com 首页（加快单作品解析）。
    private function get_cookie_header($use_manual) {
        $manual = ($use_manual && defined('DOUYIN_COOKIE') && DOUYIN_COOKIE !== '') ? DOUYIN_COOKIE : '';
        // 预热拿 ttwid/msToken（缓存）
        $cacheFile = sys_get_temp_dir() . '/dyparse_ttwid_cache';
        $warm = [];
        $useCache = false;
        if (is_file($cacheFile)) {
            $mtime = @filemtime($cacheFile);
            if ($mtime !== false && (time() - $mtime) < 1800) {
                $cached = @file_get_contents($cacheFile);
                if ($cached !== false && $cached !== '') {
                    $warm = explode('; ', $cached);
                    $useCache = true;
                }
            }
        }
        if (!$useCache) {
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
                    $warm = $m[1];
                    @file_put_contents($cacheFile, implode('; ', $warm));
                }
            }
        }
        $map = [];
        foreach ($warm as $c) {
            $c = trim($c);
            if ($c === '') continue;
            $kv = explode('=', $c, 2);
            if (count($kv) === 2) $map[trim($kv[0])] = trim($kv[1]);
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

    private function request($url, $timeout = 15, $use_manual = false) {
        $headers = $this->headers;
        $cookie = $this->get_cookie_header($use_manual);
        if ($cookie !== '') {
            $headers[] = 'Cookie: ' . $cookie;
        }
        $ch = curl_init($url);
        curl_setopt_array($ch, [
            CURLOPT_RETURNTRANSFER => true,
            CURLOPT_FOLLOWLOCATION => true,
            CURLOPT_TIMEOUT => $timeout,
            CURLOPT_SSL_VERIFYPEER => false,
            CURLOPT_SSL_VERIFYHOST => false,
            CURLOPT_HTTPHEADER => $headers,
        ]);
        $body = curl_exec($ch);
        $final_url = curl_getinfo($ch, CURLINFO_EFFECTIVE_URL);
        curl_close($ch);
        return [$body, $final_url];
    }

    private function get_video_info($video_id, $want_original, $want_highest, $use_manual) {
        // 1) detail API（带 a_bogus 签名 + msToken，绕过 403 blocked 风控）
        // 参数与客户端 buildSignedAwemeDetailUrl 完全一致（www.douyin.com 域名）
        $cookie_str = $this->get_cookie_header($use_manual);
        $msToken = '';
        if (preg_match('/(?:^|;\s*)msToken=([^;]+)/i', $cookie_str, $mm)) {
            $msToken = trim($mm[1]);
        }
        if ($msToken === '') {
            // 生成随机 msToken（客户端 generateMsToken：182 随机字符 + ==）
            $alphabet = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789';
            $msToken = '';
            for ($i = 0; $i < 182; $i++) {
                $msToken .= $alphabet[mt_rand(0, strlen($alphabet) - 1)];
            }
            $msToken .= '==';
        }

        $detail_query = 'device_platform=webapp&aid=6383&channel=channel_pc_web&aweme_id=' . $video_id
            . '&update_version_code=170400&pc_client_type=1&pc_libra_divert=Windows'
            . '&support_h265=1&support_dash=1&version_code=290100&version_name=29.1.0'
            . '&cookie_enabled=true&screen_width=1920&screen_height=1080'
            . '&browser_language=zh-CN&browser_platform=Win32&browser_name=Chrome'
            . '&browser_version=130.0.0.0&browser_online=true&engine_name=Blink'
            . '&engine_version=130.0.0.0&os_name=Windows&os_version=10'
            . '&cpu_core_num=12&device_memory=8&platform=PC&downlink=10'
            . '&effective_type=4g&round_trip_time=100&msToken=' . $msToken;
        $signed_ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36";
        $abogus = dy_sign_query($detail_query, $signed_ua);
        $detail_url = 'https://www.douyin.com/aweme/v1/web/aweme/detail/?' . $detail_query . '&a_bogus=' . $abogus;

        // detail API 请求头：用与签名一致的 Windows UA
        $saved_headers = $this->headers;
        $this->headers = [
            'User-Agent: ' . $signed_ua,
            'Referer: https://www.douyin.com/',
        ];
        list($body, $final) = $this->request($detail_url, 15, $use_manual);
        $this->headers = $saved_headers;
        $item = null;
        $error = '';
        if ($body) {
            $json = json_decode($body, true);
            if (is_array($json) && isset($json['aweme_detail']) && is_array($json['aweme_detail'])) {
                $item = $json['aweme_detail'];
            } else {
                $error = 'detail no data';
            }
        } else {
            $error = 'detail fail';
        }

        // 2) 兜底：分享页
        if (!$item) {
            $share_url = 'https://www.iesdouyin.com/share/video/' . $video_id . '/';
            list($html, $final2) = $this->request($share_url, 15, $use_manual);
            if ($html) {
                if (preg_match('/window\._ROUTER_DATA\s*=\s*(\{.*?\});?\s*<\/script>/s', $html, $m)) {
                    $data = json_decode($m[1], true);
                } elseif (preg_match('/<script[^>]*id="RENDER_DATA"[^>]*>(.*?)<\/script>/s', $html, $m)) {
                    $data = json_decode(urldecode($m[1]), true);
                } else {
                    $data = null;
                }
                if (is_array($data)) {
                    $item = isset($data['loaderData']['video_(id)/page']['videoInfoRes']['item_list'][0])
                        ? $data['loaderData']['video_(id)/page']['videoInfoRes']['item_list'][0]
                        : (isset($data['videoInfoRes']['item_list'][0])
                            ? $data['videoInfoRes']['item_list'][0]
                            : (isset($data['aweme_detail']) ? $data['aweme_detail'] : null));
                }
            }
        }

        if (!$item) {
            $this->fail('解析失败：' . ($error !== '' ? $error : '无数据'), 500);
        }

        // 提取结果
        $author = isset($item['author']['nickname']) ? $item['author']['nickname'] : '未知作者';
        $author_uid = isset($item['author']['uid']) ? $item['author']['uid'] : null;
        $author_sec_uid = isset($item['author']['sec_uid']) ? $item['author']['sec_uid'] : null;
        $title = isset($item['desc']) ? $item['desc'] : '无标题';
        $aweme_id = isset($item['aweme_id']) ? $item['aweme_id'] : $video_id;
        $timestamp = isset($item['create_time']) ? intval($item['create_time']) : time();

        $gallery = [];
        // 图集来源：支持多种结构（image_post_info.images / image_list / images / image_infos / original_images）
        $image_sources = [];
        if (isset($item['image_post_info']['images']) && is_array($item['image_post_info']['images'])) {
            $image_sources = $item['image_post_info']['images'];
        } elseif (isset($item['image_list']) && is_array($item['image_list'])) {
            $image_sources = $item['image_list'];
        } elseif (isset($item['images']) && is_array($item['images'])) {
            $image_sources = $item['images'];
        } elseif (isset($item['image_infos']) && is_array($item['image_infos'])) {
            $image_sources = $item['image_infos'];
        } elseif (isset($item['original_images']) && is_array($item['original_images'])) {
            $image_sources = $item['original_images'];
        }
        if (!empty($image_sources)) {
            $idx = 0;
            foreach ($image_sources as $img) {
                if (!is_array($img)) continue;
                $u = isset($img['url_list'][0]) ? $img['url_list'][0]
                    : (isset($img['download_url_list'][0]) ? $img['download_url_list'][0] : null);
                // 实况图：从图集项的 video 字段提取实况视频地址（对齐客户端 DouyinGalleryMediaResolver）
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
                if ($live_photo === null && isset($img['video_play_addr']['uri'])) {
                    $lv = $img['video_play_addr']['uri'];
                    if (strpos($lv, 'http') === 0) {
                        $live_photo = $lv;
                    } elseif (strpos($lv, 'mp3') === false) {
                        $live_photo = 'http://www.iesdouyin.com/aweme/v1/play/?video_id=' . $lv . '&ratio=1080p&line=0';
                    } else {
                        $live_photo = $lv;
                    }
                }
                if ($u || $live_photo) {
                    $gallery[] = [
                        'index' => $idx,
                        'image_url' => $u,
                        'live_photo_raw_url' => $live_photo,
                        'has_live_photo' => $live_photo !== null,
                    ];
                    $idx++;
                }
            }
        }

        $cover = null;
        if (isset($item['video']['cover']['url_list'][0])) $cover = $item['video']['cover']['url_list'][0];
        elseif (isset($item['video']['origin_cover']['url_list'][0])) $cover = $item['video']['origin_cover']['url_list'][0];
        elseif (isset($gallery[0]['image_url'])) $cover = $gallery[0]['image_url'];

        // ===== 地址提取：一次请求同时准备 默认(1080p)/原画质/最高画质 三个地址 =====
        // 默认地址：play_addr（ratio=1080p）
        $default_uri = null;
        if (isset($item['video']['play_addr']['uri'])) $default_uri = $item['video']['play_addr']['uri'];
        elseif (isset($item['video']['bit_rate'][0]['play_addr']['uri'])) $default_uri = $item['video']['bit_rate'][0]['play_addr']['uri'];

        // 原画质地址：download_addr（ratio=default）
        $original_uri = null;
        if (isset($item['video']['download_addr']['uri'])) {
            $original_uri = $item['video']['download_addr']['uri'];
        }
        if ($original_uri === null && isset($item['video']['download_addr']['url_list'][0])) {
            $original_uri = $item['video']['download_addr']['url_list'][0];
        }
        if ($original_uri === null) $original_uri = $default_uri;

        // 最高画质地址：1:1 照搬参考项目（dyparseX）逻辑——
        // collectVideoCandidates 收集全部候选，pickHighestCandidate 按
        // height→width→pixel→bitRate→dataSize→qualityType→directUrl→sourcePriority 取最高
        $highest_uri = null;
        $highest_play = null;
        {
            $candidates = $this->collectVideoCandidates($item);
            $best_candidate = $this->pickHighestCandidate($candidates);
            if ($best_candidate !== null) {
                $highest_play = $best_candidate['url'];
                $highest_uri = $best_candidate['url'];
            }
        }
        if ($highest_play === null || $highest_play === '') {
            $highest_play = $original_uri;
        }
        if ($highest_play === null || $highest_play === '') {
            $highest_play = $default_uri;
        }

        $build_play = function ($uri, $ratio) {
            if (!$uri) return null;
            if (strpos($uri, 'mp3') !== false) return $uri;
            if (strpos($uri, 'http') === 0) return $uri; // 已是完整 URL 直接用
            return 'http://www.iesdouyin.com/aweme/v1/play/?video_id=' . $uri . '&ratio=' . $ratio . '&line=0';
        };

        // 默认播放地址（1080p）
        $play = $build_play($default_uri, '1080p');
        if (!$play && isset($item['video']['play_addr']['url_list'][0])) $play = $item['video']['play_addr']['url_list'][0];

        // 原画质地址（default）
        $quality_play = $build_play($original_uri, 'default');
        if (!$quality_play && isset($item['video']['download_addr']['url_list'][0])) $quality_play = $item['video']['download_addr']['url_list'][0];

        // 按请求选择主地址：highest > original > 默认
        $selected_play = $play;
        if ($want_highest) {
            $selected_play = $highest_play ?: $quality_play ?: $play;
        } elseif ($want_original) {
            $selected_play = $quality_play ?: $play;
        }
        // 保存时用的"原画质/最高画质"地址：highest 时用最高画质，否则用原画质
        $save_quality_play = $want_highest ? ($highest_play ?: $quality_play) : $quality_play;

        // 关键：跟随重定向拿最终可播放地址（aweme/v1/play 会 302 到真实 CDN 地址）
        // play_url = 最终地址（可直接播放）；raw_play_url = 原始模板地址（供过期后刷新）
        $raw_play = $selected_play;
        $final_play = $selected_play;
        if ($selected_play && strpos($selected_play, 'http') === 0) {
            $followed = $this->get_redirected_url($selected_play);
            if ($followed) {
                $final_play = $followed;
            }
        }
        // 保存用画质地址也跟随重定向（供保存时直接用，避免二次请求）
        $quality_final = $save_quality_play;
        if ($save_quality_play && strpos($save_quality_play, 'http') === 0 && $save_quality_play !== $selected_play) {
            $followed2 = $this->get_redirected_url($save_quality_play);
            if ($followed2) {
                $quality_final = $followed2;
            }
        }

        $duration = 0.0;
        if (isset($item['video']['duration'])) $duration = floatval($item['video']['duration']) / 1000.0;
        $type = empty($gallery) ? 'video' : 'image';
        $images = null;
        if ($type === 'image') {
            $images = [];
            foreach ($gallery as $g) $images[] = $g['image_url'];
        }

        return [
            'success' => true,
            'author' => $author,
            'author_uid' => $author_uid,
            'author_sec_uid' => $author_sec_uid,
            'title' => $title,
            'video_id' => $aweme_id,
            'type' => $type,
            'play_url' => $final_play,
            'raw_play_url' => $raw_play,
            // 原画质/最高画质地址（保存时直接用，避免二次请求）
            'original_play_url' => $quality_final,
            'cover' => $cover,
            'images' => $images,
            'gallery_media' => $gallery,
            'duration' => $duration,
            'timestamp' => $timestamp,
            'resolved_url' => $final,
            'input_url' => isset($_GET['url']) ? $_GET['url'] : '',
            // 诊断字段（客户端忽略）：
            'auth_mode' => $use_manual ? 'cookie' : 'anon',
            'cookie_enabled' => defined('DOUYIN_COOKIE_ENABLED') && DOUYIN_COOKIE_ENABLED ? 'true' : 'false',
        ];
    }

    // 诊断：返回服务器版本/配置/参数解析状态（浏览器访问 data.php?diag=1）
    private function diag() {
        $warm = $this->try_warm();
        $manual = (defined('DOUYIN_COOKIE') && DOUYIN_COOKIE !== '') ? DOUYIN_COOKIE : '';
        $cookieHeader = $this->get_cookie_header(true);
        echo json_encode([
            'version' => 'dyparse-v9-cookie-fix',
            'php' => PHP_VERSION,
            'query_string' => isset($_SERVER['QUERY_STRING']) ? $_SERVER['QUERY_STRING'] : '',
            'mode_received' => isset($_GET['mode']) ? $_GET['mode'] : '',
            'original_received' => isset($_GET['original']) ? $_GET['original'] : '',
            'highest_received' => isset($_GET['highest']) ? $_GET['highest'] : '',
            'use_manual_when_mode_cookie' => (isset($_GET['mode']) && $_GET['mode'] === 'cookie') ? 'yes' : 'no',
            'douyin_cookie_defined' => defined('DOUYIN_COOKIE') ? 'yes' : 'no',
            'douyin_cookie_len' => strlen($manual),
            'douyin_cookie_has_sessionid' => (strpos($manual, 'sessionid') !== false) ? 'yes' : 'no',
            'warm_ok' => ($warm !== '') ? 'yes' : 'no',
            'warm_cookie_preview' => substr($warm, 0, 60),
            'final_cookie_header_len' => strlen($cookieHeader),
            'final_cookie_preview' => substr($cookieHeader, 0, 80),
            'success' => true,
        ], JSON_UNESCAPED_UNICODE);
        exit;
    }

    // 单独预热（供 diag 使用）
    private function try_warm() {
        $ch = curl_init('https://www.douyin.com/');
        curl_setopt_array($ch, [
            CURLOPT_RETURNTRANSFER => true,
            CURLOPT_HEADER => true,
            CURLOPT_NOBODY => true,
            CURLOPT_TIMEOUT => 8,
            CURLOPT_SSL_VERIFYPEER => false,
            CURLOPT_SSL_VERIFYHOST => false,
            CURLOPT_USERAGENT => DOUYIN_UA,
        ]);
        $resp = curl_exec($ch);
        curl_close($ch);
        $cookies = [];
        if ($resp) {
            preg_match_all('/^Set-Cookie:\s*([^;]+)/mi', $resp, $m);
            if (!empty($m[1])) $cookies = $m[1];
        }
        return implode('; ', $cookies);
    }

    // 诊断2：测试分享页/接口带 cookie 的实际返回（data.php?diag2=1&url=xxx）
    private function diag2() {
        $input = urldecode(trim(isset($_GET['url']) ? $_GET['url'] : ''));
        if ($input === '') {
            echo json_encode(['error' => 'need url'], JSON_UNESCAPED_UNICODE);
            exit;
        }
        // 提取视频 ID
        $video_id = $input;
        if (!is_numeric($input)) {
            preg_match('/https?:\/\/[^\s]+/', $input, $m);
            if (!empty($m[0])) {
                $rd = $this->get_redirected_url(trim($m[0], " '\""));
                if ($rd) {
                    preg_match('/(\d+)/', $rd, $m2);
                    if (!empty($m2[1])) $video_id = $m2[1];
                }
            }
        }
        $headers = $this->headers;
        $cookie = $this->get_cookie_header(true);
        if ($cookie !== '') $headers[] = 'Cookie: ' . $cookie;

        // 1) 分享页
        $share_url = 'https://www.iesdouyin.com/share/video/' . $video_id . '/';
        $ch = curl_init($share_url);
        curl_setopt_array($ch, [
            CURLOPT_RETURNTRANSFER => true,
            CURLOPT_FOLLOWLOCATION => true,
            CURLOPT_TIMEOUT => 15,
            CURLOPT_SSL_VERIFYPEER => false,
            CURLOPT_SSL_VERIFYHOST => false,
            CURLOPT_HTTPHEADER => $headers,
        ]);
        $share_html = curl_exec($ch);
        $share_status = curl_getinfo($ch, CURLINFO_HTTP_CODE);
        curl_close($ch);

        // 2) detail API
        $detail_url = 'https://www.iesdouyin.com/aweme/v1/web/aweme/detail/?aweme_id=' . $video_id
            . '&aid=6383&device_platform=webapp&channel=channel_pc_web&version_code=170400';
        $ch2 = curl_init($detail_url);
        curl_setopt_array($ch2, [
            CURLOPT_RETURNTRANSFER => true,
            CURLOPT_FOLLOWLOCATION => true,
            CURLOPT_TIMEOUT => 15,
            CURLOPT_SSL_VERIFYPEER => false,
            CURLOPT_SSL_VERIFYHOST => false,
            CURLOPT_HTTPHEADER => $headers,
        ]);
        $detail_body = curl_exec($ch2);
        $detail_status = curl_getinfo($ch2, CURLINFO_HTTP_CODE);
        curl_close($ch2);

        echo json_encode([
            'video_id' => $video_id,
            'share_status' => $share_status,
            'share_html_len' => strlen($share_html),
            'share_has_router_data' => (strpos($share_html, '_ROUTER_DATA') !== false) ? 'yes' : 'no',
            'share_has_render_data' => (strpos($share_html, 'RENDER_DATA') !== false) ? 'yes' : 'no',
            'share_has_aweme_detail' => (strpos($share_html, 'aweme_detail') !== false) ? 'yes' : 'no',
            'detail_status' => $detail_status,
            'detail_body_len' => strlen($detail_body),
            'detail_has_aweme_detail' => (strpos($detail_body, 'aweme_detail') !== false) ? 'yes' : 'no',
            'detail_status_code' => (preg_match('/"status_code":(\d+)/', $detail_body, $m) ? $m[1] : 'unknown'),
            'detail_preview' => substr($detail_body, 0, 300),
            'success' => true,
        ], JSON_UNESCAPED_UNICODE);
        exit;
    }

    // 诊断3：解析分享页 ROUTER_DATA，看能否提取完整 item（含 bit_rate）
    private function diag3() {
        $input = urldecode(trim(isset($_GET['url']) ? $_GET['url'] : ''));
        if ($input === '') {
            echo json_encode(['error' => 'need url'], JSON_UNESCAPED_UNICODE);
            exit;
        }
        $video_id = $input;
        if (!is_numeric($input)) {
            preg_match('/https?:\/\/[^\s]+/', $input, $m);
            if (!empty($m[0])) {
                $rd = $this->get_redirected_url(trim($m[0], " '\""));
                if ($rd) {
                    preg_match('/(\d+)/', $rd, $m2);
                    if (!empty($m2[1])) $video_id = $m2[1];
                }
            }
        }
        $headers = $this->headers;
        $cookie = $this->get_cookie_header(true);
        if ($cookie !== '') $headers[] = 'Cookie: ' . $cookie;

        $share_url = 'https://www.iesdouyin.com/share/video/' . $video_id . '/';
        $ch = curl_init($share_url);
        curl_setopt_array($ch, [
            CURLOPT_RETURNTRANSFER => true,
            CURLOPT_FOLLOWLOCATION => true,
            CURLOPT_TIMEOUT => 15,
            CURLOPT_SSL_VERIFYPEER => false,
            CURLOPT_SSL_VERIFYHOST => false,
            CURLOPT_HTTPHEADER => $headers,
        ]);
        $html = curl_exec($ch);
        curl_close($ch);

        $out = ['video_id' => $video_id, 'html_len' => strlen($html)];
        // 提取 _ROUTER_DATA
        if (preg_match('/window\._ROUTER_DATA\s*=\s*(\{.*?\});?\s*<\/script>/s', $html, $m)) {
            $data = json_decode($m[1], true);
            $out['router_data_parsed'] = is_array($data) ? 'yes' : 'no';
            if (is_array($data)) {
                // 探索结构
                $out['top_keys'] = array_keys($data);
                $item = $this->find_item_deep($data);
                if ($item) {
                    $out['item_found'] = 'yes';
                    $out['item_keys'] = array_keys($item);
                    $out['has_video'] = isset($item['video']) ? 'yes' : 'no';
                    if (isset($item['video'])) {
                        $out['video_keys'] = array_keys($item['video']);
                        $out['has_bit_rate'] = isset($item['video']['bit_rate']) ? 'yes' : 'no';
                        if (isset($item['video']['bit_rate'])) {
                            $out['bit_rate_count'] = count($item['video']['bit_rate']);
                            $out['bit_rate_vals'] = [];
                            foreach ($item['video']['bit_rate'] as $br) {
                                $out['bit_rate_vals'][] = isset($br['bit_rate']) ? $br['bit_rate'] : '?';
                            }
                        }
                        $out['has_download_addr'] = isset($item['video']['download_addr']) ? 'yes' : 'no';
                        $out['has_play_addr'] = isset($item['video']['play_addr']) ? 'yes' : 'no';
                    }
                    $out['desc'] = isset($item['desc']) ? mb_substr($item['desc'], 0, 50) : '';
                } else {
                    $out['item_found'] = 'no';
                    // 输出 loaderData 结构前几层
                    if (isset($data['loaderData'])) {
                        $out['loaderData_keys'] = array_keys($data['loaderData']);
                        foreach ($data['loaderData'] as $k => $v) {
                            if (is_array($v)) {
                                $out['loaderData_' . $k . '_keys'] = array_keys($v);
                            }
                        }
                    }
                }
            }
        } else {
            $out['router_data_found'] = 'no';
        }
        $out['success'] = true;
        echo json_encode($out, JSON_UNESCAPED_UNICODE);
        exit;
    }

    // 深挖 item（探索 ROUTER_DATA 结构）
    private function find_item_deep($data) {
        $candidates = [
            ['loaderData', 'video_(id)/page', 'videoInfoRes', 'item_list', 0],
            ['loaderData', 'video_(id)/page', 'videoInfoRes', 'item_list'],
            ['loaderData', 'video_(id)/page', 'videoInfoRes'],
            ['loaderData', 'video_(id)/page'],
            ['videoInfoRes', 'item_list', 0],
            ['aweme_detail'],
        ];
        foreach ($candidates as $path) {
            $cur = $data;
            $ok = true;
            foreach ($path as $p) {
                if (is_array($cur) && isset($cur[$p])) {
                    $cur = $cur[$p];
                } else {
                    $ok = false;
                    break;
                }
            }
            if ($ok && is_array($cur)) {
                if (is_array($cur) && isset($cur[0]) && is_array($cur[0])) return $cur[0];
                if (isset($cur['aweme_id']) || isset($cur['desc']) || isset($cur['video'])) return $cur;
            }
        }
        return null;
    }

    // 诊断4：测试 a_bogus 签名后的 detail API（data.php?diag4=1&url=xxx）
    private function diag4() {
        $input = urldecode(trim(isset($_GET['url']) ? $_GET['url'] : ''));
        if ($input === '') {
            echo json_encode(['error' => 'need url'], JSON_UNESCAPED_UNICODE);
            exit;
        }
        $video_id = $input;
        if (!is_numeric($input)) {
            preg_match('/https?:\/\/[^\s]+/', $input, $m);
            if (!empty($m[0])) {
                $rd = $this->get_redirected_url(trim($m[0], " '\""));
                if ($rd) {
                    preg_match('/(\d+)/', $rd, $m2);
                    if (!empty($m2[1])) $video_id = $m2[1];
                }
            }
        }
        $headers = $this->headers;
        $cookie = $this->get_cookie_header(true);
        // 请求头 UA 必须与签名 UA 一致
        $signed_ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36";
        $headers = [
            'User-Agent: ' . $signed_ua,
            'Referer: https://www.douyin.com/',
        ];
        if ($cookie !== '') $headers[] = 'Cookie: ' . $cookie;

        // 与 get_video_info 一致的 detail API 构建（www.douyin.com + msToken + a_bogus）
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
        $detail_query = 'device_platform=webapp&aid=6383&channel=channel_pc_web&aweme_id=' . $video_id
            . '&update_version_code=170400&pc_client_type=1&pc_libra_divert=Windows'
            . '&support_h265=1&support_dash=1&version_code=290100&version_name=29.1.0'
            . '&cookie_enabled=true&screen_width=1920&screen_height=1080'
            . '&browser_language=zh-CN&browser_platform=Win32&browser_name=Chrome'
            . '&browser_version=130.0.0.0&browser_online=true&engine_name=Blink'
            . '&engine_version=130.0.0.0&os_name=Windows&os_version=10'
            . '&cpu_core_num=12&device_memory=8&platform=PC&downlink=10'
            . '&effective_type=4g&round_trip_time=100&msToken=' . $msToken;
        $abogus = dy_sign_query($detail_query, $signed_ua);
        $detail_url = 'https://www.douyin.com/aweme/v1/web/aweme/detail/?' . $detail_query . '&a_bogus=' . $abogus;

        $ch = curl_init($detail_url);
        curl_setopt_array($ch, [
            CURLOPT_RETURNTRANSFER => true,
            CURLOPT_FOLLOWLOCATION => true,
            CURLOPT_TIMEOUT => 15,
            CURLOPT_SSL_VERIFYPEER => false,
            CURLOPT_SSL_VERIFYHOST => false,
            CURLOPT_HTTPHEADER => $headers,
        ]);
        $body = curl_exec($ch);
        $status = curl_getinfo($ch, CURLINFO_HTTP_CODE);
        curl_close($ch);

        $json = json_decode($body, true);
        echo json_encode([
            'video_id' => $video_id,
            'abogus' => $abogus,
            'detail_status' => $status,
            'body_len' => strlen($body),
            'status_code' => (is_array($json) && isset($json['status_code'])) ? $json['status_code'] : 'unknown',
            'has_aweme_detail' => (is_array($json) && isset($json['aweme_detail'])) ? 'yes' : 'no',
            'has_bit_rate' => (is_array($json) && isset($json['aweme_detail']['video']['bit_rate'])) ? 'yes' : 'no',
            'preview' => substr($body, 0, 200),
            'success' => true,
        ], JSON_UNESCAPED_UNICODE);
        exit;
    }

    public function parse() {
        // 诊断模式：浏览器访问 data.php?diag=1 查看服务器状态（不鉴权）
        if (isset($_GET['diag']) && $_GET['diag'] === '1') {
            $this->diag();
        }
        if (isset($_GET['diag2']) && $_GET['diag2'] === '1') {
            $this->diag2();
        }
        if (isset($_GET['diag3']) && $_GET['diag3'] === '1') {
            $this->diag3();
        }
        if (isset($_GET['diag4']) && $_GET['diag4'] === '1') {
            $this->diag4();
        }
        $this->authorize();
        $input = urldecode(trim(isset($_GET['url']) ? $_GET['url'] : ''));
        if ($input === '') {
            $this->fail('请输入抖音链接或作品 ID');
        }
        $want_original = isset($_GET['original']) && $_GET['original'] === '1';
        $want_highest = isset($_GET['highest']) && $_GET['highest'] === '1';
        // mode=cookie → 用服务器手动配置的登录 cookie（批量/保存原画质/最高画质）；否则匿名（单条）
        $use_manual = isset($_GET['mode']) && $_GET['mode'] === 'cookie';

        if (is_numeric($input)) {
            $video_id = trim($input);
            $resolved = '';
        } else {
            preg_match('/https?:\/\/[^\s]+/', $input, $m);
            if (empty($m[0])) {
                $this->fail('无效的链接格式');
            }
            $redirected = $this->get_redirected_url(trim($m[0], " '\""));
            if (!$redirected) {
                $this->fail('无法获取重定向 URL');
            }
            preg_match('/(\d+)/', $redirected, $m2);
            $video_id = isset($m2[1]) ? $m2[1] : '';
            $resolved = $redirected;
            if ($video_id === '') {
                $this->fail('无法提取视频 ID');
            }
        }

        $result = $this->get_video_info($video_id, $want_original, $want_highest, $use_manual);
        echo json_encode($result, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES);
    }
}

// 顶层 try-catch：任何未捕获异常都输出具体错误，避免"未知错误"
try {
    $parser = new DyParser();
    $parser->parse();
} catch (Exception $e) {
    http_response_code(500);
    echo json_encode([
        'success' => false,
        'error' => 'server error: ' . $e->getMessage(),
        'code' => 500
    ], JSON_UNESCAPED_UNICODE);
}
