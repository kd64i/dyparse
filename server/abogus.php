<?php
/**
 * a_bogus 签名生成器（PHP 移植自客户端 DouyinABogusSigner.kt）
 * 用于 detail API 请求签名，绕过抖音 web 接口风控（无签名返回 403 blocked）。
 */

// ==================== 基础工具 ====================

/** SM3 哈希（国密），输入二进制串，输出 32 字节二进制串 */
function dy_sm3($input) {
    $iv = [0x7380166f, 0x4914b2b9, 0x172442d7, 0xda8a0600, 0xa96f30bc, 0x163138aa, 0xe38dee4d, 0xb0fb0e4e];
    // 填充
    $len = strlen($input);
    $bitLen = $len * 8;
    $total = $len + 1 + 8;
    $rem = $total % 64;
    if ($rem !== 0) $total += 64 - $rem;
    $padded = $input . "\x80" . str_repeat("\x00", $total - $len - 1 - 8);
    // 64 位大端长度
    $lenBytes = pack('J', $bitLen); // PHP 7+ 64位 J=unsigned long long
    $padded .= $lenBytes;

    $v = $iv;
    for ($off = 0; $off < $total; $off += 64) {
        $block = substr($padded, $off, 64);
        $v = dy_sm3_compress($v, $block);
    }
    $out = '';
    foreach ($v as $val) {
        $out .= pack('N', $val & 0xFFFFFFFF);
    }
    return $out;
}

function dy_sm3_rotl($x, $bits) {
    $bits &= 31;
    $x &= 0xFFFFFFFF;
    return (($x << $bits) | ($x >> (32 - $bits))) & 0xFFFFFFFF;
}

function dy_sm3_p0($x) {
    return ($x ^ dy_sm3_rotl($x, 9) ^ dy_sm3_rotl($x, 17)) & 0xFFFFFFFF;
}

function dy_sm3_p1($x) {
    return ($x ^ dy_sm3_rotl($x, 15) ^ dy_sm3_rotl($x, 23)) & 0xFFFFFFFF;
}

function dy_sm3_ff($x, $y, $z, $j) {
    return ($j <= 15) ? ($x ^ $y ^ $z) : (($x & $y) | ($x & $z) | ($y & $z));
}

function dy_sm3_gg($x, $y, $z, $j) {
    return ($j <= 15) ? ($x ^ $y ^ $z) : (($x & $y) | ((~$x) & $z));
}

function dy_sm3_compress($v, $block) {
    $w = array_fill(0, 68, 0);
    $w1 = array_fill(0, 64, 0);
    for ($i = 0; $i < 16; $i++) {
        $w[$i] = (ord($block[$i * 4]) << 24) | (ord($block[$i * 4 + 1]) << 16) |
            (ord($block[$i * 4 + 2]) << 8) | ord($block[$i * 4 + 3]);
    }
    for ($j = 16; $j < 68; $j++) {
        $w[$j] = (dy_sm3_p1($w[$j - 16] ^ $w[$j - 9] ^ dy_sm3_rotl($w[$j - 3], 15)) ^
            dy_sm3_rotl($w[$j - 13], 7) ^ $w[$j - 6]) & 0xFFFFFFFF;
    }
    for ($j = 0; $j < 64; $j++) {
        $w1[$j] = $w[$j] ^ $w[$j + 4];
    }
    $a = $v[0]; $b = $v[1]; $c = $v[2]; $d = $v[3];
    $e = $v[4]; $f = $v[5]; $g = $v[6]; $h = $v[7];
    for ($j = 0; $j < 64; $j++) {
        $tj = ($j <= 15) ? 0x79cc4519 : 0x7a879d8a;
        $ss1 = dy_sm3_rotl((dy_sm3_rotl($a, 12) + $e + dy_sm3_rotl($tj, $j)) & 0xFFFFFFFF, 7);
        $ss2 = $ss1 ^ dy_sm3_rotl($a, 12);
        $tt1 = (dy_sm3_ff($a, $b, $c, $j) + $d + $ss2 + $w1[$j]) & 0xFFFFFFFF;
        $tt2 = (dy_sm3_gg($e, $f, $g, $j) + $h + $ss1 + $w[$j]) & 0xFFFFFFFF;
        $d = $c;
        $c = dy_sm3_rotl($b, 9);
        $b = $a;
        $a = $tt1;
        $h = $g;
        $g = dy_sm3_rotl($f, 19);
        $f = $e;
        $e = dy_sm3_p0($tt2);
    }
    $v[0] = ($v[0] ^ $a) & 0xFFFFFFFF;
    $v[1] = ($v[1] ^ $b) & 0xFFFFFFFF;
    $v[2] = ($v[2] ^ $c) & 0xFFFFFFFF;
    $v[3] = ($v[3] ^ $d) & 0xFFFFFFFF;
    $v[4] = ($v[4] ^ $e) & 0xFFFFFFFF;
    $v[5] = ($v[5] ^ $f) & 0xFFFFFFFF;
    $v[6] = ($v[6] ^ $g) & 0xFFFFFFFF;
    $v[7] = ($v[7] ^ $h) & 0xFFFFFFFF;
    return $v;
}

/** RC4 加密（key=字节数组字符串，data=二进制串），输出二进制串 */
function dy_rc4($keyBytes, $data) {
    $s = range(0, 255);
    $j = 0;
    $kLen = strlen($keyBytes);
    for ($i = 0; $i < 256; $i++) {
        $j = ($j + $s[$i] + (ord($keyBytes[$i % $kLen]) & 255)) % 256;
        $t = $s[$i]; $s[$i] = $s[$j]; $s[$j] = $t;
    }
    $i = 0; $j = 0;
    $out = '';
    $len = strlen($data);
    for ($idx = 0; $idx < $len; $idx++) {
        $i = ($i + 1) % 256;
        $j = ($j + $s[$i]) % 256;
        $t = $s[$i]; $s[$i] = $s[$j]; $s[$j] = $t;
        $k = $s[($s[$i] + $s[$j]) % 256];
        $out .= chr((ord($data[$idx]) & 255) ^ $k);
    }
    return $out;
}

// ==================== a_bogus 生成 ====================

const DY_ABOGUS_ALPHABET = [
    "Dkdpgh2ZmsQB80/MfvV36XI1R45-WUAlEixNLwoqYTOPuzKFjJnry79HbGcaStCe",
    "ckdp1h4ZKsUB80/Mfvw36XIgR25+WQAlEi7NLboqYTOPuzmFjJnryx9HVGDaStCe"
];

const DY_ABOGUS_BIG_ARRAY = [
    121, 243, 55, 234, 103, 36, 47, 228, 30, 231, 106, 6, 115, 95, 78,
    101, 250, 207, 198, 50, 139, 227, 220, 105, 97, 143, 34, 28, 194,
    215, 18, 100, 159, 160, 43, 8, 169, 217, 180, 120, 247, 45, 90, 11,
    27, 197, 46, 3, 84, 72, 5, 68, 62, 56, 221, 75, 144, 79, 73, 161,
    178, 81, 64, 187, 134, 117, 186, 118, 16, 241, 130, 71, 89, 147,
    122, 129, 65, 40, 88, 150, 110, 219, 199, 255, 181, 254, 48, 4,
    195, 248, 208, 32, 116, 167, 69, 201, 17, 124, 125, 104, 96, 83,
    80, 127, 236, 108, 154, 126, 204, 15, 20, 135, 112, 158, 13, 1,
    188, 164, 210, 237, 222, 98, 212, 77, 253, 42, 170, 202, 26, 22,
    29, 182, 251, 10, 173, 152, 58, 138, 54, 141, 185, 33, 157, 31,
    252, 132, 233, 235, 102, 196, 191, 223, 240, 148, 39, 123, 92, 82,
    128, 109, 57, 24, 38, 113, 209, 245, 2, 119, 153, 229, 189, 214,
    230, 174, 232, 63, 52, 205, 86, 140, 66, 175, 111, 171, 246, 133,
    238, 193, 99, 60, 74, 91, 225, 51, 76, 37, 145, 211, 166, 151,
    213, 206, 0, 200, 244, 176, 218, 44, 184, 172, 49, 216, 93, 168,
    53, 21, 183, 41, 67, 85, 224, 155, 226, 242, 87, 177, 146, 70,
    190, 12, 162, 19, 137, 114, 25, 165, 163, 192, 23, 59, 9, 94, 179,
    107, 35, 7, 142, 131, 239, 203, 149, 136, 61, 249, 14, 156
];

/** 参数 SM3（字符串版）：默认加盐 "cus" */
function dy_params_to_array_str($param, $addSalt = true) {
    $value = $addSalt ? ($param . 'cus') : $param;
    return array_values(unpack('C*', dy_sm3($value)));
}

/** 参数 SM3（列表版） */
function dy_params_to_array_list($param) {
    $bytes = '';
    foreach ($param as $v) $bytes .= chr($v & 255);
    return array_values(unpack('C*', dy_sm3($bytes)));
}

/** 自定义 base64 */
function dy_base64_encode($input, $alphabetIndex) {
    $binary = '';
    $len = strlen($input);
    for ($i = 0; $i < $len; $i++) {
        $binary .= str_pad(decbin(ord($input[$i]) & 255), 8, '0', STR_PAD_LEFT);
    }
    $paddingLength = (6 - strlen($binary) % 6) % 6;
    $padded = $binary . str_repeat('0', $paddingLength);
    $alphabet = DY_ABOGUS_ALPHABET[$alphabetIndex];
    $output = '';
    $blen = strlen($padded);
    for ($index = 0; $index < $blen; $index += 6) {
        $output .= $alphabet[bindec(substr($padded, $index, 6))];
    }
    $output .= str_repeat('=', intdiv($paddingLength, 2));
    return $output;
}

/** transformBytes：bigArray 置换 XOR */
function dy_transform_bytes($bytesList) {
    $bigArray = DY_ABOGUS_BIG_ARRAY;
    $result = '';
    $indexB = $bigArray[1];
    $initialValue = 0;
    $valueE = 0;
    $size = count($bigArray);
    foreach ($bytesList as $index => $byte) {
        $sumInitial = 0;
        if ($index === 0) {
            $initialValue = $bigArray[$indexB];
            $sumInitial = ($indexB + $initialValue) % $size;
            $bigArray[1] = $initialValue;
            $bigArray[$indexB] = $indexB;
        } else {
            $sumInitial = ($initialValue + $valueE) % $size;
        }
        $valueF = $bigArray[$sumInitial];
        $result .= chr((($byte & 255) ^ $valueF) & 255);

        $swapIndex = ($index + 2) % $size;
        $valueE = $bigArray[$swapIndex];
        $sumInitial = ($indexB + $valueE) % $size;
        $initialValue = $bigArray[$sumInitial];
        $bigArray[$sumInitial] = $bigArray[$swapIndex];
        $bigArray[$swapIndex] = $initialValue;
        $indexB = $sumInitial;
    }
    return $result;
}

/** abogusEncode：3 字节 → 4 字符（自定义 alphabet） */
function dy_abogus_encode($input, $alphabetIndex) {
    $alphabet = DY_ABOGUS_ALPHABET[$alphabetIndex];
    $output = '';
    $index = 0;
    $len = strlen($input);
    while ($index < $len) {
        $b0 = ord($input[$index]) & 255;
        $b1 = ($index + 1 < $len) ? ord($input[$index + 1]) & 255 : 0;
        $b2 = ($index + 2 < $len) ? ord($input[$index + 2]) & 255 : 0;
        $n = ($b0 << 16) | ($b1 << 8) | $b2;
        $shifts = [18, 12, 6, 0];
        $masks = [0xFC0000, 0x03F000, 0x0FC0, 0x3F];
        for ($i = 0; $i < 4; $i++) {
            if ($shifts[$i] === 6 && $index + 1 >= $len) break;
            if ($shifts[$i] === 0 && $index + 2 >= $len) break;
            $output .= $alphabet[($n & $masks[$i]) >> $shifts[$i]];
        }
        $index += 3;
    }
    $output .= str_repeat('=', (4 - strlen($output) % 4) % 4);
    return $output;
}

/** 随机字节（3 组 × 4 字节 = 12 字节） */
function dy_random_bytes($length = 3) {
    $out = '';
    for ($r = 0; $r < $length; $r++) {
        $rv = mt_rand(0, 9999);
        $out .= chr((($rv & 255) & 170) | 1);
        $out .= chr((($rv & 255) & 85) | 2);
        $out .= chr((($rv >> 8) & 170) | 5);
        $out .= chr((($rv >> 8) & 85) | 40);
    }
    return $out;
}

/** 浏览器指纹 */
function dy_browser_fp() {
    $innerWidth = mt_rand(1024, 1920);
    $innerHeight = mt_rand(768, 1080);
    $outerWidth = $innerWidth + mt_rand(24, 32);
    $outerHeight = $innerHeight + mt_rand(75, 90);
    $screenY = (mt_rand(0, 1) === 0) ? 0 : 30;
    $sizeWidth = mt_rand(1024, 1920);
    $sizeHeight = mt_rand(768, 1080);
    $availWidth = mt_rand(1280, 1920);
    $availHeight = mt_rand(800, 1080);
    return "$innerWidth|$innerHeight|$outerWidth|$outerHeight|0|$screenY|0|0|$sizeWidth|$sizeHeight|$availWidth|$availHeight|$innerWidth|$innerHeight|24|24|Win32";
}

/** 生成 a_bogus */
function dy_abogus_generate($params, $body, $userAgent) {
    $AID = 6383;
    $PAGE_ID = 0;
    $options = [0, 1, 14];
    $sortIndex = [
        18, 20, 52, 26, 30, 34, 58, 38, 40, 53, 42, 21, 27, 54, 55, 31, 35,
        57, 39, 41, 43, 22, 28, 32, 60, 36, 23, 29, 33, 37, 44, 45, 59,
        46, 47, 48, 49, 50, 24, 25, 65, 66, 70, 71
    ];
    $sortIndex2 = [
        18, 20, 26, 30, 34, 38, 40, 42, 21, 27, 31, 35, 39, 41, 43, 22, 28,
        32, 36, 23, 29, 33, 37, 44, 45, 46, 47, 48, 49, 50, 24, 25, 52,
        53, 54, 55, 57, 58, 59, 60, 65, 66, 70, 71
    ];

    $ab = [8 => 3, 18 => 44, 66 => 0, 69 => 0, 70 => 0, 71 => 0];

    $browserFp = dy_browser_fp();

    $startMs = (int) round(microtime(true) * 1000);
    $array1 = dy_params_to_array_list(dy_params_to_array_str($params));
    $array2 = dy_params_to_array_list(dy_params_to_array_str($body));
    $uaEncrypted = dy_rc4("\x00\x01\x0E", $userAgent);
    // toOrdString(uaEncrypted) -> base64(1)
    $array3 = dy_params_to_array_str(dy_base64_encode($uaEncrypted, 1), false);
    $endMs = (int) round(microtime(true) * 1000);

    $ab[20] = ($startMs >> 24) & 255;
    $ab[21] = ($startMs >> 16) & 255;
    $ab[22] = ($startMs >> 8) & 255;
    $ab[23] = $startMs & 255;
    $ab[24] = ($startMs >> 32) & 255;
    $ab[25] = ($startMs >> 40) & 255;

    $ab[26] = ($options[0] >> 24) & 255;
    $ab[27] = ($options[0] >> 16) & 255;
    $ab[28] = ($options[0] >> 8) & 255;
    $ab[29] = $options[0] & 255;

    $ab[30] = intdiv($options[1], 256) & 255;
    $ab[31] = ($options[1] % 256) & 255;
    $ab[32] = ($options[1] >> 24) & 255;
    $ab[33] = ($options[1] >> 16) & 255;

    $ab[34] = ($options[2] >> 24) & 255;
    $ab[35] = ($options[2] >> 16) & 255;
    $ab[36] = ($options[2] >> 8) & 255;
    $ab[37] = $options[2] & 255;

    $ab[38] = isset($array1[21]) ? $array1[21] : 0;
    $ab[39] = isset($array1[22]) ? $array1[22] : 0;
    $ab[40] = isset($array2[21]) ? $array2[21] : 0;
    $ab[41] = isset($array2[22]) ? $array2[22] : 0;
    $ab[42] = isset($array3[23]) ? $array3[23] : 0;
    $ab[43] = isset($array3[24]) ? $array3[24] : 0;

    $ab[44] = ($endMs >> 24) & 255;
    $ab[45] = ($endMs >> 16) & 255;
    $ab[46] = ($endMs >> 8) & 255;
    $ab[47] = $endMs & 255;
    $ab[48] = isset($ab[8]) ? $ab[8] : 0;
    $ab[49] = ($endMs >> 32) & 255;
    $ab[50] = ($endMs >> 40) & 255;

    $ab[51] = ($PAGE_ID >> 24) & 255;
    $ab[52] = ($PAGE_ID >> 16) & 255;
    $ab[53] = ($PAGE_ID >> 8) & 255;
    $ab[54] = $PAGE_ID & 255;
    $ab[55] = $PAGE_ID;
    $ab[56] = $AID;
    $ab[57] = $AID & 255;
    $ab[58] = ($AID >> 8) & 255;
    $ab[59] = ($AID >> 16) & 255;
    $ab[60] = ($AID >> 24) & 255;
    $ab[64] = strlen($browserFp);
    $ab[65] = strlen($browserFp);

    $sortedValues = [];
    foreach ($sortIndex as $si) {
        $sortedValues[] = isset($ab[$si]) ? $ab[$si] : 0;
    }

    $abXor = ((strlen($browserFp) & 255) >> 8) & 255;
    $s2count = count($sortIndex2);
    for ($index = 0; $index < max(0, $s2count - 1); $index++) {
        if ($index === 0) {
            $abXor = isset($ab[$sortIndex2[$index]]) ? $ab[$sortIndex2[$index]] : 0;
        }
        $abXor ^= (isset($ab[$sortIndex2[$index + 1]]) ? $ab[$sortIndex2[$index + 1]] : 0);
    }

    $fpLen = strlen($browserFp);
    for ($i = 0; $i < $fpLen; $i++) {
        $sortedValues[] = ord($browserFp[$i]) & 255;
    }
    $sortedValues[] = $abXor;

    $abogusBytes = dy_random_bytes() . dy_transform_bytes($sortedValues);
    return dy_abogus_encode($abogusBytes, 0);
}

/** 便捷：给 query 串生成完整 a_bogus 参数值 */
function dy_sign_query($query, $userAgent) {
    return dy_abogus_generate($query, '', $userAgent);
}
