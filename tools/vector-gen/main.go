package main

import (
	"crypto/hmac"
	"crypto/md5"
	"crypto/sha1"
	"encoding/hex"
	"encoding/json"
	"flag"
	"fmt"
	"os"
	"path/filepath"

	"vecgen/vecgen"
)

func hmacMd5Hex(token, password string) string {
	mac := hmac.New(md5.New, []byte(token))
	mac.Write([]byte(password))
	return hex.EncodeToString(mac.Sum(nil))
}

func sha1HexNl(content string) string {
	h := sha1.New()
	h.Write([]byte(content))
	bs := h.Sum(nil)
	return fmt.Sprintf("%x\n", bs)
}

type Vector struct {
	Str string `json:"str"`
	Key string `json:"key"`
	Xen string `json:"xen"` // B64(XEncode(str,key))
}

type ChecksumCase struct {
	Token    string `json:"token"`
	Username string `json:"username"`
	Password string `json:"password"`
	Acid     string `json:"acid"`
	IP       string `json:"ip"`
	N        string `json:"n"`
	Type     string `json:"type"`
	Info     string `json:"info"` // 提交的 {SRBX1}... 值
	Hmac     string `json:"hmac"` // hmac-md5(token,password) hex
	Chksum   string `json:"chksum"`
}

type Golden struct {
	Vectors  []Vector     `json:"vectors"`
	Checksum ChecksumCase `json:"checksum"`
}

func main() {
	outPath := flag.String("out", "engine/src/test/resources/golden.json", "golden.json 输出路径（相对当前工作目录；在仓库根执行即默认写回 engine 测试资源）")
	flag.Parse()
	raw := [][2]string{
		{"sample-pass-0000", "12345678"},
		{"2300000000", "abcdefgh"},
		{"", "12345678"},
		{"", ""},
		{"sample-pass-0000", "ab"}, // 短 key（<4 字节 padding）
		{"p@ss/w+rd=1234&%", "5b4f1a8e2c09d3f7"},
		{"西安交通大学校园网登录测试", "token123"},
		{"Windows 2000", "losedows"},
		{"losedows", "Windows 2000"},
		{"{\"acid\":\"1\",\"enc_ver\":\"srun_bx1\",\"ip\":\"10.18.17.26\",\"password\":\"sample-pass-0000\",\"username\":\"2300000000\"}", "5b4f1a8e2c09d3f7"},
		{"This is a very long plaintext string used to exercise the XTEA variant block cipher with more than one block. 0123456789012345678901234567890123456789", "key123"},
		{"n=200&type=1&acid=1&os=Windows 2000&name=losedows", "tok"},
	}
	var out []Vector
	for _, v := range raw {
		out = append(out, Vector{v[0], v[1], vecgen.SrunB64(vecgen.XEncode(v[0], v[1]))})
	}

	// 与 DoLogin 完全同构的 chksum 用例（固定 token，不触网）
	token := "5b4f1a8e2c09d3f7"
	username := "2300000000"
	password := "sample-pass-0000"
	acid := "1"
	ip := "10.18.17.26"
	n := "200"
	typ := "1"
	infoJSON := fmt.Sprintf(`{"acid":"%s","enc_ver":"srun_bx1","ip":"%s","password":"%s","username":"%s"}`, acid, ip, password, username)
	info := "{SRBX1}" + vecgen.SrunB64(vecgen.XEncode(infoJSON, token))
	hm := hmacMd5Hex(token, password)
	chk := sha1HexNl(token + username + token + hm + token + acid + token + ip + token + n + token + typ + token + info)

	g := Golden{
		Vectors: out,
		Checksum: ChecksumCase{
			Token: token, Username: username, Password: password,
			Acid: acid, IP: ip, N: n, Type: typ, Info: info, Hmac: hm, Chksum: chk,
		},
	}
	data, _ := json.MarshalIndent(g, "", "  ")
	path := *outPath
	if err := os.MkdirAll(filepath.Dir(path), 0755); err != nil {
		panic(err)
	}
	if err := os.WriteFile(path, data, 0644); err != nil {
		panic(err)
	}
	fmt.Println("golden written:", len(out), "vectors")
	fmt.Println("chksum hmac:", hm)
	fmt.Println("chksum val :", chk[:40]+"...")
	fmt.Println("info sample:", info[:60]+"...")
}
