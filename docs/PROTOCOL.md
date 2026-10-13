# 深澜 Srun 登录协议要点（本项目实现所依据的规范）

> 本文档只描述**协议与算法规范**，不含任何第三方实现的代码。
> `engine/` 的实现即按本文档 + MIT 许可的参考实现编写（见 NOTICE 与 licenses/）。

## 1. 端点

| 用途 | 路径 | 关键参数 |
|---|---|---|
| 取挑战串 | `GET /cgi-bin/get_challenge` | `username`、`ip`；响应含 `challenge` |
| 登录 | `GET /cgi-bin/srun_portal` | `action=login` + 账号/摘要参数（见下） |
| 登出 | `GET /cgi-bin/srun_portal` | `action=logout` + `username`、`ac_id`、`ip` |
| 在线状态 | `GET /cgi-bin/rad_user_info` | 无参；响应 `error == "ok"` 表示在线 |

所有响应都是 JSONP：`callback({...})`；请求方自带 `callback` 与 `_=<毫秒>` 参数。查询串按
`application/x-www-form-urlencoded` 转义（空格→`+`，保留 `[0-9A-Za-z-_.~]`）。

## 2. 登录提交参数

| 参数 | 含义 |
|---|---|
| `action` | 固定 `login` |
| `username` | 学号；需要后缀时形如 `学号@stu`（西交校园网为 `stu`） |
| `password` | `"{MD5}" + hmac-md5(key=challenge, msg=明文密码)` 的十六进制 |
| `ac_id` | 接入控制器编号（西交各区域均为 `1`） |
| `ip` | **认证 IP —— 见第 4 节，必须用网关上报的 `client_ip`** |
| `info` | `"{SRBX1}" + 变体 base64(XEncode(infoJson, challenge))` |
| `chksum` | `sha1(...)` 的十六进制，**末尾带换行 `\n`** |
| `n` | `200` |
| `type` | `1` |
| `os` / `name` | 客户端自述字段（`Windows 2000` / `losedows` 为主流客户端默认值） |
| `double_stack` | `0`（双栈网络时认证 IP 传空） |

`infoJson` 的字段为 `acid`、`enc_ver`（`srun_bx1`）、`ip`、`password`、`username`；键序由实现自定（网关按 JSON 解析）。

`chksum` 的拼接顺序（`challenge` 作分隔符重复出现）：

```
challenge + username + challenge + md5hex + challenge + acid + challenge
         + ip + challenge + n + challenge + type + challenge + info
```

## 3. XEncode（XXTEA 变体）

1. 明文与密钥都按 **4 字节小端**打包成 32 位字；明文末组不足 4 字节时补 0；
2. 明文末尾**追加一个元素 = 明文字节长度**（密钥不追加）；
3. 密钥不足 4 组时补 0 到 4 组（参与运算的下标恒在 0..3）——短密钥能算出结果的前提；
4. 轮数 `6 + 52/(n+1)`（`n = 字数-1`），每轮累加 `DELTA = 0x9E3779B9`；
5. 每步混淆（`MX`）：

```
MX(z, y, sum, k) = ((z >> 5) ^ (y << 2))
                 + (((y >> 3) ^ (z << 4)) ^ (sum ^ y))
                 + (k ^ z)
```

   循环体内 `k` 取 `key[(p & 3) ^ ((sum >> 2) & 3)]`；最后一步用 `p = n`；
6. 全部运算按 32 位回绕；输出为原始字节（4 字节/字，小端），再过变体 base64。

## 4. 变体 base64

字母表（协议常量，与标准表不同）：

```
LVoJPiCN2R8G90yg+hmFHuacZ1OWMnrsSTXkYpUq/3dlbfKwv6xztjI7DeBE45QA
```

分组与 `=` 补位规则同标准 base64（每 3 字节 → 4 字符；余 1 字节 → 2 字符 + `==`；余 2 字节 → 3 字符 + `=`）。

## 5. 认证 IP（最易踩的坑）

登录请求的 `ip` **必须是网关自己认的地址**：`rad_user_info` 响应里的 `client_ip`（缺失时用 `online_ip`）。
本机网卡上读到的 IPv4 在多数区域与其一致，但在部分区域（实测：食堂，客户端 10.69.x 而网关认别的地址）
不一致 —— 此时用本机 IP 直接登录会被判 `login_error`。
（另一条等价的取法：门户登录页 HTML 里的 `id="user_ip"` 隐藏字段。）

## 6. 端点谱系

同一校园网内可能存在多台 srun 服务（各区域 AC + 总门户）。实测西安交大共有 4 个：
`10.6.8.2`（总门户，响应形态为旧版 `error`/`res`）、`10.6.18.2`、`10.6.21.2`、`10.6.27.2`。
登录请求必须发到**服务当前 IP 的那一台**，发错只会得到通用的 `login_error`。

## 7. 常见错误

| 响应 | 含义 |
|---|---|
| `login_error` | 账号/后缀/密码不对，或认证 IP 与网关认知不一致，或发错了 AC |
| `E2531` 等 | 账号欠费一类的业务错误，按网关原文提示 |
| `IP has been online, please logout` | 该 IP/账号已在线，等价于成功 |
| 返回网页（`<script>top.self.location.href=...`） | 该主机不是本区域的 AC，或需要跟随跳转到真门户 |

## 8. 本项目的验收标准

`engine/src/test` 全部跑绿即视为实现正确：12 条**逐字节** XEncode 向量
（`resources/golden.json`，为冻结夹具）+ mock 网关全链路 7 例（提交参数形态、chksum 重算一致、
在线判定、登出、错误透传、连接工厂注入与失败传播）。
