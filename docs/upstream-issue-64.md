## Summary

Two bugs in `pkg/srun/XEncode.go` (current `main`), found via static analysis plus minimal local Go repros. Neither has been reproduced against a live gateway with the triggering conditions; no production data is involved.

---

### Bug 1: key-padding loop never reaches 4 entries → potential `index out of range` panic

Lines 55–56 in `XEncode` (same pattern at lines 100–101 in `XDecode`):

```go
if len(pwdk) < 4 {
    for i := 0; i < (4 - len(pwdk)); i++ {
        pwdk = append(pwdk, 0)
    }
}
```

In Go the loop condition is re-evaluated on **every** iteration, and `append` grows `len(pwdk)` inside the loop body. Starting from `len(pwdk) == 2`:

- `i = 0`: `0 < 4-2` → append → `len` becomes 3
- `i = 1`: `1 < 4-3` (i.e. `1 < 1`) is **false** → loop exits

The slice ends with **3** entries, never 4. Later `pwdk[(p&3)^e]` can address index 3 (e.g. round 1: `d = 0x9E3779B9`, `e = (d>>2)&3 == 2`, and with `p == 1` → `(1&3)^2 == 3`), which panics with `index out of range [3] with length 3`.

Minimal repro of the loop behavior (standalone, no dependencies):

```go
package main

import "fmt"

func main() {
	pwdk := []uint32{1, 2} // a key < 16 bytes yields 2 groups from sensCode
	if len(pwdk) < 4 {
		for i := 0; i < (4 - len(pwdk)); i++ {
			pwdk = append(pwdk, 0)
		}
	}
	fmt.Println(len(pwdk)) // prints 3, never 4
}
```

The Python reference implementation (`coffeehat/BIT-srun-login-script`) pads correctly in this spot: `range(4 - len(key))` evaluates its bounds **once** up front, so it fills up to 4 entries — the discrepancy was introduced in the Go port.

**Why it has been latent:** when the gateway token is ≥ 16 bytes, `sensCode(key, false)` already yields exactly 4 groups and this branch is never entered. Any gateway returning a shorter token would panic on the first login attempt. I could not observe that scenario against a live gateway, so the trigger remains a code-level deduction.

**Suggested fix** (no behavior change for tokens ≥ 16 bytes):

```go
if len(pwdk) < 4 {
    for len(pwdk) < 4 {
        pwdk = append(pwdk, 0)
    }
}
```

---

### Bug 2 (same file, lower priority): `XDecode` is not invertible

`XDecode(XEncode("sample-pass-0000", "12345678"), "12345678")` returns an empty string when run against the upstream code as-is (the length check in `lenCode(pwd, true)` rejects the result). Since the login flow only uses `XEncode`, this has never been triggered in practice.

Happy to help locate the inversion logic bug and prepare a PR if there is interest in fixing it.

---

**Verification boundary:** both findings are from static analysis + standalone local repros on the current `main`; not confirmed against a live gateway with short tokens. Willing to follow up with whatever reproduction the maintainers prefer.