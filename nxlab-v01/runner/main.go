package main

import (
    "fmt"
    "os"
    "os/exec"
    "regexp"
    "strconv"
    "strings"

    "github.com/Dasongzi1366/AutoGo/app"
    "github.com/Dasongzi1366/AutoGo/device"
    "github.com/Dasongzi1366/AutoGo/motion"
    "github.com/Dasongzi1366/AutoGo/utils"
    "github.com/ZingYao/autogo_scriptengine/lua_engine"
    lua "github.com/yuin/gopher-lua"
)

const version = "FAC NX Lab Runner v0.1"

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
        t.RawSetString("buildId", lua.LString(device.BuildId))
        t.RawSetString("brand", lua.LString(device.Brand))
        t.RawSetString("device", lua.LString(device.Device))
        t.RawSetString("model", lua.LString(device.Model))
        t.RawSetString("product", lua.LString(device.Product))
        t.RawSetString("sdkInt", lua.LNumber(device.SdkInt))
        t.RawSetString("release", lua.LString(device.Release))
        L.Push(t)
        return 1
    }))

    L.SetGlobal("tap", L.NewFunction(func(L *lua.LState) int {
        x := L.CheckInt(1)
        y := L.CheckInt(2)
        motion.Click(x, y, 1)
        return 0
    }))
    L.SetGlobal("swipe", L.NewFunction(func(L *lua.LState) int {
        x1 := L.CheckInt(1)
        y1 := L.CheckInt(2)
        x2 := L.CheckInt(3)
        y2 := L.CheckInt(4)
        duration := L.OptInt(5, 500)
        motion.Swipe(x1, y1, x2, y2, duration)
        L.Push(lua.LBool(true))
        return 1
    }))

    touch := L.NewTable()
    touch.RawSetString("isRooted", L.NewFunction(func(L *lua.LState) int {
        out, err := exec.Command("su", "-c", "id").CombinedOutput()
        rooted := err == nil && strings.Contains(string(out), "uid=0")
        L.Push(lua.LBool(rooted))
        return 1
    }))
    touch.RawSetString("click", L.NewFunction(func(L *lua.LState) int {
        x := L.CheckInt(1)
        y := L.CheckInt(2)
        motion.Click(x, y, 1)
        return 0
    }))
    touch.RawSetString("swipe", L.NewFunction(func(L *lua.LState) int {
        x1 := L.CheckInt(1)
        y1 := L.CheckInt(2)
        x2 := L.CheckInt(3)
        y2 := L.CheckInt(4)
        duration := L.OptInt(5, 500)
        motion.Swipe(x1, y1, x2, y2, duration)
        return 0
    }))
    L.SetGlobal("touch", touch)
}

func newEngine() *lua_engine.LuaEngine {
    cfg := lua_engine.DefaultConfig()
    engine := lua_engine.NewLuaEngine(&cfg)
    registerCompat(engine)
    return engine
}

func runScript(script string) error {
    engine := newEngine()
    defer engine.Close()
    return engine.ExecuteString(script)
}

func selfTest() error {
    script := `
print("=== FAC NX LAB v0.1 ===")
print("lua=OK")
print("bridge=AutoGo-direct")
print("api.getPackageName=" .. type(getPackageName))
print("api.getSystemInfo=" .. type(getSystemInfo))
print("api.tap=" .. type(tap))
print("api.swipe=" .. type(swipe))
print("api.touch=" .. type(touch))

local info = getSystemInfo()
print("screen=" .. tostring(info.width) .. "x" .. tostring(info.height))
print("dpi=" .. tostring(info.dpi))
print("rotation=" .. tostring(info.rotation))
print("cpuAbi=" .. tostring(info.cpuAbi))
print("sdkInt=" .. tostring(info.sdkInt))
print("android=" .. tostring(info.release))
print("brand=" .. tostring(info.brand))
print("model=" .. tostring(info.model))
print("foreground.package=" .. tostring(getPackageName()))
print("foreground.activity=" .. tostring(getActivityName()))
print("battery=" .. tostring(getBatteryLevel()))
print("workPath=" .. tostring(getWorkPath()))
print("root=" .. tostring(touch.isRooted()))
print("RESULT=PASS")
`
    return runScript(script)
}

func tapTest(x, y int) error {
    script := fmt.Sprintf(`
print("FAC NX Lab touch test armed")
print("tap.target=%d,%d")
print("tap.delay=3000ms")
sleep(3000)
tap(%d, %d)
print("tap.sent=YES")
`, x, y, x, y)
    return runScript(script)
}

func runFile(path string) error {
    engine := newEngine()
    defer engine.Close()
    fmt.Println("=== FAC NX LAB custom Lua ===")
    fmt.Println("script=" + path)
    if err := engine.ExecuteFile(path); err != nil {
        return err
    }
    fmt.Println("RESULT=PASS")
    return nil
}

func usage() {
    fmt.Println(version)
    fmt.Println("usage:")
    fmt.Println("  nxlab-runner selftest")
    fmt.Println("  nxlab-runner tap <x> <y>")
    fmt.Println("  nxlab-runner runfile <path>")
}

func main() {
    fmt.Println(version)
    cmd := "selftest"
    if len(os.Args) > 1 {
        cmd = os.Args[1]
    }

    var err error
    switch cmd {
    case "selftest":
        err = selfTest()
    case "tap":
        if len(os.Args) != 4 {
            usage()
            os.Exit(2)
        }
        x, e1 := strconv.Atoi(os.Args[2])
        y, e2 := strconv.Atoi(os.Args[3])
        if e1 != nil || e2 != nil || x < 0 || y < 0 {
            fmt.Println("invalid coordinates")
            os.Exit(2)
        }
        err = tapTest(x, y)
    case "runfile":
        if len(os.Args) != 3 {
            usage()
            os.Exit(2)
        }
        err = runFile(os.Args[2])
    default:
        usage()
        os.Exit(2)
    }

    if err != nil {
        fmt.Println("RESULT=FAIL")
        fmt.Println("error=" + err.Error())
        os.Exit(1)
    }
}
