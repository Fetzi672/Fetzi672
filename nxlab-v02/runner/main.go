package main

import (
	"bufio"
	"bytes"
	"fmt"
	"image"
	"image/color"
	_ "image/png"
	"math"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"time"

	"github.com/Dasongzi1366/AutoGo/app"
	"github.com/Dasongzi1366/AutoGo/device"
	"github.com/Dasongzi1366/AutoGo/motion"
	"github.com/Dasongzi1366/AutoGo/utils"
	"github.com/ZingYao/autogo_scriptengine/lua_engine"
	lua "github.com/yuin/gopher-lua"
)

const version = "FAC NX Lab Runner v0.2"
const stopFile = "/data/local/tmp/fac_nxlab/STOP"

type Template struct {
	ID      string
	Label   string
	File    string
	Image   image.Image
	W, H    int
	Anchors []Anchor
}

type Anchor struct {
	X, Y int
	C    color.NRGBA
}

type Match struct {
	X, Y  int
	Score float64
}

func event(kind string, parts ...interface{}) {
	vals := []string{"NXEVT", kind}
	for _, p := range parts {
		s := fmt.Sprint(p)
		s = strings.ReplaceAll(s, "|", "/")
		s = strings.ReplaceAll(s, "\n", " ")
		vals = append(vals, s)
	}
	fmt.Println(strings.Join(vals, "|"))
}

func shellInt(command string) int {
	s := strings.TrimSpace(utils.Shell(command))
	re := regexp.MustCompile(`[-]?\d+`)
	m := re.FindString(s)
	n, _ := strconv.Atoi(m)
	return n
}

func registerCompat(engine *lua_engine.LuaEngine) {
	L := engine.GetState()
	L.SetGlobal("getPackageName", L.NewFunction(func(L *lua.LState) int {
		L.Push(lua.LString(app.CurrentPackage()))
		return 1
	}))
	L.SetGlobal("getActivityName", L.NewFunction(func(L *lua.LState) int {
		L.Push(lua.LString(app.CurrentActivity()))
		return 1
	}))
	L.SetGlobal("getBatteryLevel", L.NewFunction(func(L *lua.LState) int {
		L.Push(lua.LNumber(device.GetBattery()))
		return 1
	}))
	L.SetGlobal("getWorkPath", L.NewFunction(func(L *lua.LState) int {
		path, _ := os.Getwd()
		L.Push(lua.LString(path))
		return 1
	}))
	L.SetGlobal("getSystemInfo", L.NewFunction(func(L *lua.LState) int {
		t := L.NewTable()
		t.RawSetString("width", lua.LNumber(device.Width))
		t.RawSetString("height", lua.LNumber(device.Height))
		t.RawSetString("dpi", lua.LNumber(shellInt("wm density | tail -n 1")))
		t.RawSetString("rotation", lua.LNumber(shellInt("dumpsys input | grep -m1 SurfaceOrientation")))
		t.RawSetString("cpuAbi", lua.LString(strings.TrimSpace(device.CpuAbi)))
		t.RawSetString("brand", lua.LString(device.Brand))
		t.RawSetString("model", lua.LString(device.Model))
		t.RawSetString("sdkInt", lua.LNumber(device.SdkInt))
		t.RawSetString("release", lua.LString(device.Release))
		L.Push(t)
		return 1
	}))
	L.SetGlobal("tap", L.NewFunction(func(L *lua.LState) int {
		motion.Click(L.CheckInt(1), L.CheckInt(2), 1)
		return 0
	}))
	L.SetGlobal("swipe", L.NewFunction(func(L *lua.LState) int {
		motion.Swipe(L.CheckInt(1), L.CheckInt(2), L.CheckInt(3), L.CheckInt(4), L.OptInt(5, 500))
		L.Push(lua.LBool(true))
		return 1
	}))
	touch := L.NewTable()
	touch.RawSetString("isRooted", L.NewFunction(func(L *lua.LState) int {
		L.Push(lua.LBool(os.Geteuid() == 0))
		return 1
	}))
	L.SetGlobal("touch", touch)
}

func newEngine() *lua_engine.LuaEngine {
	cfg := lua_engine.DefaultConfig()
	engine := lua_engine.NewLuaEngine(&cfg)
	registerCompat(engine)
	return engine
}

func selfTest() error {
	engine := newEngine()
	defer engine.Close()
	return engine.ExecuteString(`
print("=== FAC NX LAB v0.2 ===")
print("lua=OK")
local info=getSystemInfo()
print("screen="..tostring(info.width).."x"..tostring(info.height))
print("cpuAbi="..tostring(info.cpuAbi))
print("package="..tostring(getPackageName()))
print("root="..tostring(touch.isRooted()))
print("RESULT=PASS")
`)
}

func runFile(path string) error {
	engine := newEngine()
	defer engine.Close()
	return engine.ExecuteFile(path)
}

func captureScreen() (image.Image, error) {
	cmds := [][]string{{"/system/bin/screencap", "-p"}, {"screencap", "-p"}}
	var last error
	for _, args := range cmds {
		out, err := exec.Command(args[0], args[1:]...).Output()
		if err != nil {
			last = err
			continue
		}
		img, _, err := image.Decode(bytes.NewReader(out))
		if err == nil {
			return img, nil
		}
		tmp := "/data/local/tmp/fac_nxlab/capture.png"
		if err2 := os.WriteFile(tmp, out, 0644); err2 == nil {
			f, err3 := os.Open(tmp)
			if err3 == nil {
				img2, _, err4 := image.Decode(f)
				f.Close()
				if err4 == nil {
					return img2, nil
				}
				last = err4
			}
		}
		last = err
	}
	return nil, fmt.Errorf("screencap failed: %v", last)
}

func loadTemplates(dir string) ([]Template, error) {
	f, err := os.Open(filepath.Join(dir, "index.tsv"))
	if err != nil {
		return nil, err
	}
	defer f.Close()
	var out []Template
	s := bufio.NewScanner(f)
	for s.Scan() {
		line := strings.TrimSpace(s.Text())
		if line == "" || strings.HasPrefix(line, "#") {
			continue
		}
		p := strings.Split(line, "\t")
		if len(p) < 3 {
			continue
		}
		tf, err := os.Open(filepath.Join(dir, p[2]))
		if err != nil {
			event("LOG", "template open failed: "+p[2])
			continue
		}
		img, _, err := image.Decode(tf)
		tf.Close()
		if err != nil {
			event("LOG", "template decode failed: "+p[2])
			continue
		}
		b := img.Bounds()
		t := Template{ID: p[0], Label: p[1], File: p[2], Image: img, W: b.Dx(), H: b.Dy()}
		t.Anchors = chooseAnchors(img, 7)
		out = append(out, t)
	}
	return out, s.Err()
}

func rgbaAt(img image.Image, x, y int) color.NRGBA {
	return color.NRGBAModel.Convert(img.At(img.Bounds().Min.X+x, img.Bounds().Min.Y+y)).(color.NRGBA)
}

func chooseAnchors(img image.Image, n int) []Anchor {
	b := img.Bounds()
	w, h := b.Dx(), b.Dy()
	var rs, gs, bs, count float64
	for y := 0; y < h; y += max(1, h/12) {
		for x := 0; x < w; x += max(1, w/12) {
			c := rgbaAt(img, x, y)
			if c.A < 80 {
				continue
			}
			rs += float64(c.R)
			gs += float64(c.G)
			bs += float64(c.B)
			count++
		}
	}
	if count == 0 {
		count = 1
	}
	mr, mg, mb := rs/count, gs/count, bs/count
	type cand struct {
		a Anchor
		d float64
	}
	var cs []cand
	for y := 1; y < h-1; y += max(1, h/10) {
		for x := 1; x < w-1; x += max(1, w/10) {
			c := rgbaAt(img, x, y)
			if c.A < 80 {
				continue
			}
			d := math.Abs(float64(c.R)-mr) + math.Abs(float64(c.G)-mg) + math.Abs(float64(c.B)-mb)
			cs = append(cs, cand{Anchor{x, y, c}, d})
		}
	}
	sort.Slice(cs, func(i, j int) bool { return cs[i].d > cs[j].d })
	var out []Anchor
	for _, c := range cs {
		ok := true
		for _, a := range out {
			if abs(a.X-c.a.X)+abs(a.Y-c.a.Y) < max(4, min(w, h)/5) {
				ok = false
				break
			}
		}
		if ok {
			out = append(out, c.a)
		}
		if len(out) >= n {
			break
		}
	}
	if len(out) == 0 {
		out = append(out, Anchor{w / 2, h / 2, rgbaAt(img, w/2, h/2)})
	}
	return out
}

func colorDiff(a, b color.NRGBA) int {
	return abs(int(a.R)-int(b.R)) + abs(int(a.G)-int(b.G)) + abs(int(a.B)-int(b.B))
}

func findTemplate(screen image.Image, t Template, threshold float64) Match {
	sb := screen.Bounds()
	sw, sh := sb.Dx(), sb.Dy()
	if t.W > sw || t.H > sh {
		return Match{X: -1, Y: -1}
	}
	best := Match{X: -1, Y: -1, Score: 0}
	anchorLimit := int((1-threshold) * 765 * 2.2)
	if anchorLimit < 55 {
		anchorLimit = 55
	}
	for y := 0; y <= sh-t.H; y++ {
		for x := 0; x <= sw-t.W; x++ {
			pass := true
			for _, a := range t.Anchors {
				sc := color.NRGBAModel.Convert(screen.At(sb.Min.X+x+a.X, sb.Min.Y+y+a.Y)).(color.NRGBA)
				if colorDiff(sc, a.C) > anchorLimit {
					pass = false
					break
				}
			}
			if !pass {
				continue
			}
			score := fullScore(screen, t, x, y)
			if score > best.Score {
				best = Match{x, y, score}
			}
			if score >= threshold {
				return best
			}
		}
	}
	return best
}

func fullScore(screen image.Image, t Template, ox, oy int) float64 {
	sb := screen.Bounds()
	step := 1
	if t.W*t.H > 2500 {
		step = 2
	}
	var diff float64
	var count int
	for y := 0; y < t.H; y += step {
		for x := 0; x < t.W; x += step {
			tc := rgbaAt(t.Image, x, y)
			if tc.A < 80 {
				continue
			}
			sc := color.NRGBAModel.Convert(screen.At(sb.Min.X+ox+x, sb.Min.Y+oy+y)).(color.NRGBA)
			diff += float64(colorDiff(sc, tc))
			count++
		}
	}
	if count == 0 {
		return 0
	}
	score := 1.0 - diff/(float64(count)*765.0)
	if score < 0 {
		return 0
	}
	return score
}

func scanLoop(dir string, threshold float64, interval time.Duration) error {
	templates, err := loadTemplates(dir)
	if err != nil {
		return err
	}
	if len(templates) == 0 {
		return fmt.Errorf("no templates loaded")
	}
	_ = os.Remove(stopFile)
	event("CENTER", "MULTI-TEMPLATE SCAN STARTED")
	event("LOG", fmt.Sprintf("Loaded %d templates / threshold %.2f", len(templates), threshold))
	cycle := 0
	for {
		if _, err := os.Stat(stopFile); err == nil {
			event("CENTER", "SCRIPT STOPPED")
			event("LOG", "Stop requested from floating control")
			return nil
		}
		cycle++
		event("STATUS", fmt.Sprintf("Cycle %d: capturing screen...", cycle))
		screen, err := captureScreen()
		if err != nil {
			event("ERROR", err.Error())
			time.Sleep(interval)
			continue
		}
		sb := screen.Bounds()
		event("STATUS", fmt.Sprintf("Capture OK %dx%d", sb.Dx(), sb.Dy()))
		found := 0
		for i, t := range templates {
			if _, err := os.Stat(stopFile); err == nil {
				event("CENTER", "SCRIPT STOPPED")
				return nil
			}
			event("PROGRESS", fmt.Sprintf("Scanning %d/%d: %s", i+1, len(templates), t.Label))
			m := findTemplate(screen, t, threshold)
			if m.X >= 0 && m.Score >= threshold {
				found++
				event("MATCH", t.ID, t.Label, m.X, m.Y, t.W, t.H, fmt.Sprintf("%.3f", m.Score))
				event("CENTER", "MATCH FOUND: "+t.Label)
			}
		}
		if found == 0 {
			event("CENTER", fmt.Sprintf("NO MATCH • cycle %d", cycle))
		} else {
			event("STATUS", fmt.Sprintf("Cycle %d complete • %d match(es)", cycle, found))
		}
		time.Sleep(interval)
	}
}

func max(a, b int) int { if a > b { return a }; return b }
func min(a, b int) int { if a < b { return a }; return b }
func abs(v int) int { if v < 0 { return -v }; return v }
func usage() { fmt.Println("selftest | runfile <path> | scan <template-dir> [threshold] [interval-ms]") }

func main() {
	fmt.Println(version)
	if len(os.Args) < 2 {
		usage()
		return
	}
	var err error
	switch os.Args[1] {
	case "selftest":
		err = selfTest()
	case "runfile":
		if len(os.Args) != 3 { usage(); os.Exit(2) }
		err = runFile(os.Args[2])
	case "scan":
		if len(os.Args) < 3 { usage(); os.Exit(2) }
		th := 0.88
		ms := 1200
		if len(os.Args) > 3 { if v, e := strconv.ParseFloat(os.Args[3], 64); e == nil { th = v } }
		if len(os.Args) > 4 { if v, e := strconv.Atoi(os.Args[4]); e == nil { ms = v } }
		if th < 0.70 { th = 0.70 }
		if th > 0.99 { th = 0.99 }
		if ms < 250 { ms = 250 }
		err = scanLoop(os.Args[2], th, time.Duration(ms)*time.Millisecond)
	default:
		usage(); os.Exit(2)
	}
	if err != nil {
		event("ERROR", err.Error())
		fmt.Println("RESULT=FAIL")
		os.Exit(1)
	}
	fmt.Println("RESULT=PASS")
}
