package main

import (
    "fmt"
    "os"
    "strconv"

    "github.com/ZingYao/autogo_scriptengine/lua_engine"
    lrdevice "github.com/ZingYao/autogo_scriptengine/lua_engine/model/lrappsoft/device"
    lrtouch "github.com/ZingYao/autogo_scriptengine/lua_engine/model/lrappsoft/touch"
)

const version = "FAC NX Lab Runner v0.1"

func newEngine() *lua_engine.LuaEngine {
    cfg := lua_engine.DefaultConfig()
    engine := lua_engine.NewLuaEngine(&cfg)

    // Keep candidate #1 intentionally small: only the compatibility layers
    // needed to prove Android bridge + Lua + read-only device APIs + touch.
    engine.RegisterModule(
        &lrdevice.DeviceModule{},
        lrtouch.New(),
    )
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
