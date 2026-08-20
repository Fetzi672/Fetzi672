#include <jni.h>
#include <GLES3/gl3.h>
#include <algorithm>
#include <cctype>
#include <map>
#include <set>
#include <sstream>
#include <string>
#include <vector>
#include "imgui.h"
#include "backends/imgui_impl_opengl3.h"
#include "misc/cpp/imgui_stdlib.h"

struct Field {
    std::string category,label,type,key,value;
    std::vector<std::string> options;
};

static bool g_init=false;
static int g_w=1,g_h=1;
static float g_density=1.0f;
static std::vector<Field> g_fields;
static std::vector<std::string> g_categories;
static int g_category=0;
static std::string g_search;
static std::map<std::string,std::string> g_status;
static int g_actions=0;
static bool g_dirty=false;

static std::string jstr(JNIEnv* env,jstring s){
    if(!s)return {};
    const char* p=env->GetStringUTFChars(s,nullptr);
    std::string r=p?p:"";
    if(p)env->ReleaseStringUTFChars(s,p);
    return r;
}

static std::vector<std::string> split(const std::string& s,char d){
    std::vector<std::string> out;std::string cur;
    for(char c:s){if(c==d){out.push_back(cur);cur.clear();}else cur.push_back(c);}out.push_back(cur);return out;
}

static int b64v(unsigned char c){
    if(c>='A'&&c<='Z')return c-'A';if(c>='a'&&c<='z')return c-'a'+26;if(c>='0'&&c<='9')return c-'0'+52;if(c=='+')return 62;if(c=='/')return 63;return -1;
}
static std::string b64dec(const std::string& in){
    std::string out;int val=0,bits=-8;
    for(unsigned char c:in){if(c=='=')break;int v=b64v(c);if(v<0)continue;val=(val<<6)+v;bits+=6;if(bits>=0){out.push_back(char((val>>bits)&0xff));bits-=8;}}
    return out;
}
static const char* B64="ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
static std::string b64enc(const std::string& in){
    std::string out;int val=0,bits=-6;
    for(unsigned char c:in){val=(val<<8)+c;bits+=8;while(bits>=0){out.push_back(B64[(val>>bits)&0x3f]);bits-=6;}}
    if(bits>-6)out.push_back(B64[((val<<8)>>(bits+8))&0x3f]);while(out.size()%4)out.push_back('=');return out;
}

static void parse_status(const std::string& p){
    g_status.clear();std::stringstream ss(p);std::string line;
    while(std::getline(ss,line)){
        auto x=split(line,'\t');if(x.size()>=2)g_status[x[0]]=b64dec(x[1]);
    }
}
static void parse_settings(const std::string& p){
    g_fields.clear();g_categories.clear();g_category=0;g_dirty=false;
    std::set<std::string> seen;std::stringstream ss(p);std::string line;
    while(std::getline(ss,line)){
        if(line.empty())continue;auto x=split(line,'\t');if(x.size()<6)continue;
        Field f;f.category=b64dec(x[0]);f.label=b64dec(x[1]);f.type=x[2];f.key=b64dec(x[3]);f.value=b64dec(x[4]);
        std::string opts=b64dec(x[5]);if(!opts.empty())f.options=split(opts,'\x1f');
        if(seen.insert(f.category).second)g_categories.push_back(f.category);
        g_fields.push_back(std::move(f));
    }
}
static std::string dump_settings(){
    std::string out;
    for(const auto& f:g_fields){
        std::string opts;for(size_t i=0;i<f.options.size();++i){if(i)opts.push_back('\x1f');opts+=f.options[i];}
        out+=b64enc(f.category)+"\t"+b64enc(f.label)+"\t"+f.type+"\t"+b64enc(f.key)+"\t"+b64enc(f.value)+"\t"+b64enc(opts)+"\n";
    }
    return out;
}
static std::string status(const char* k,const char* fallback="—"){
    auto it=g_status.find(k);return it==g_status.end()||it->second.empty()?fallback:it->second;
}
static std::string lower(std::string s){for(char& c:s)c=(char)std::tolower((unsigned char)c);return s;}
static bool match(const Field& f){if(g_search.empty())return true;std::string q=lower(g_search);return lower(f.label).find(q)!=std::string::npos||lower(f.key).find(q)!=std::string::npos;}
static bool is_true(const std::string& v){std::string x=lower(v);return x=="true"||x=="1"||x=="yes"||x=="on";}

static ImVec4 green(){return ImVec4(0.22f,0.86f,0.48f,1.f);}static ImVec4 red(){return ImVec4(1.f,0.26f,0.30f,1.f);}static ImVec4 amber(){return ImVec4(1.f,0.72f,0.24f,1.f);}
static void row(const char* label,const std::string& value,const ImVec4* color=nullptr){
    ImGui::TextDisabled("%s",label);ImGui::SameLine(185.0f*g_density);if(color)ImGui::TextColored(*color,"%s",value.c_str());else ImGui::TextUnformatted(value.c_str());
}
static void section(const char* title){ImGui::Spacing();ImGui::TextColored(ImVec4(0.95f,0.32f,0.36f,1.f),"%s",title);ImGui::Separator();}

static void draw_overview(){
    section("LICENSE");
    std::string lic=status("license");ImVec4 lc=lic=="ACTIVE"?green():(lic.find("VERIFIED")!=std::string::npos?amber():red());
    row("Status",lic,&lc);row("Expires",status("expiry"));row("Devices",status("devices"));row("Next online check",status("next_recheck"));
    if(ImGui::Button("RECHECK LICENSE",ImVec2(220*g_density,42*g_density)))g_actions|=4;
    std::string ev=status("last_event","");if(!ev.empty()){ImGui::Spacing();ImGui::TextWrapped("%s",ev.c_str());}
    section("GUARD");
    std::string guard=status("guard"),root=status("root"),runtime=status("runtime");
    ImVec4 gc=guard=="ARMED"?green():amber(),rc=root=="READY"?green():red(),rtc=runtime.find("VERIFIED")!=std::string::npos?green():red();
    row("FAC Guard",guard,&gc);row("Root",root,&rc);row("Runtime",runtime,&rtc);
    std::string se=status("settings_error","");if(!se.empty()){ImGui::Spacing();ImGui::TextColored(red(),"Bot Settings: %s",se.c_str());}
}

static void draw_device(){
    section("DEVICE STATUS");
    std::string root=status("root"),runtime=status("runtime");
    ImVec4 rc=root=="READY"?green():red(),rtc=runtime.find("VERIFIED")!=std::string::npos?green():red();
    row("Root",root,&rc);row("Runtime signer",runtime,&rtc);row("Bound devices",status("devices"));
    ImGui::Spacing();ImGui::TextDisabled("FAC Device ID");
    ImGui::PushTextWrapPos(ImGui::GetContentRegionAvail().x);ImGui::TextUnformatted(status("device_id").c_str());ImGui::PopTextWrapPos();
    ImGui::Spacing();ImGui::TextDisabled("The Device ID is generated by the FAC V6 identity logic and is never read from the protected NX/Lua app.");
}

static void draw_field(Field& f){
    ImGui::PushID(f.key.c_str());
    ImGui::TextWrapped("%s",f.label.c_str());
    ImGui::SetNextItemWidth(-1);
    bool changed=false;
    if(f.type=="BOOLEAN"){
        bool v=is_true(f.value);if(ImGui::Checkbox("##value",&v)){f.value=v?"true":"false";changed=true;}
    }else if(f.type=="SELECTOR"){
        const char* preview=f.value.empty()?"Select...":f.value.c_str();
        if(ImGui::BeginCombo("##value",preview)){
            for(const auto& opt:f.options){bool selected=(opt==f.value);if(ImGui::Selectable(opt.c_str(),selected)){f.value=opt;changed=true;}if(selected)ImGui::SetItemDefaultFocus();}
            ImGui::EndCombo();
        }
    }else{
        ImGuiInputTextFlags flags=ImGuiInputTextFlags_None;
        if(f.type=="INTEGER"||f.type=="DECIMAL")flags|=ImGuiInputTextFlags_CharsDecimal;
        if(ImGui::InputText("##value",&f.value,flags))changed=true;
    }
    if(changed)g_dirty=true;
    ImGui::Spacing();ImGui::Separator();ImGui::PopID();
}

static void draw_settings(){
    if(g_fields.empty()){
        ImGui::TextColored(red(),"Bot settings are unavailable.");
        ImGui::TextWrapped("%s",status("settings_error","The config or schema could not be loaded.").c_str());
        return;
    }
    ImGui::SetNextItemWidth(-1);ImGui::InputTextWithHint("##search","Search all settings...",&g_search);
    ImGui::Spacing();
    float left=std::min(245.0f*g_density,ImGui::GetContentRegionAvail().x*0.34f);
    ImGui::BeginChild("categories",ImVec2(left,0),true);
    for(size_t i=0;i<g_categories.size();++i){
        bool selected=(int)i==g_category;if(ImGui::Selectable(g_categories[i].c_str(),selected,0,ImVec2(0,36*g_density)))g_category=(int)i;
    }
    ImGui::EndChild();ImGui::SameLine();
    ImGui::BeginChild("fields",ImVec2(0,0),true);
    const std::string cat=g_categories.empty()?"":g_categories[std::max(0,std::min(g_category,(int)g_categories.size()-1))];
    ImGui::TextColored(ImVec4(0.95f,0.32f,0.36f,1.f),"%s",cat.c_str());ImGui::Separator();
    int shown=0;
    for(auto& f:g_fields){if(f.category!=cat||!match(f))continue;draw_field(f);++shown;}
    if(shown==0)ImGui::TextDisabled("No matching settings in this category.");
    ImGui::Spacing();
    if(g_dirty)ImGui::TextColored(amber(),"Unsaved changes");
    if(ImGui::Button("SAVE & CLOSE",ImVec2(190*g_density,44*g_density)))g_actions|=2;
    ImGui::SameLine();if(ImGui::Button("CLOSE",ImVec2(120*g_density,44*g_density)))g_actions|=1;
    ImGui::EndChild();
}

static void apply_style(){
    ImGuiStyle& s=ImGui::GetStyle();s.WindowRounding=14*g_density;s.ChildRounding=10*g_density;s.FrameRounding=8*g_density;s.PopupRounding=9*g_density;s.ScrollbarRounding=10*g_density;s.WindowPadding=ImVec2(16*g_density,14*g_density);s.FramePadding=ImVec2(10*g_density,8*g_density);s.ItemSpacing=ImVec2(10*g_density,9*g_density);
    s.Colors[ImGuiCol_WindowBg]=ImVec4(0.055f,0.058f,0.070f,0.98f);s.Colors[ImGuiCol_ChildBg]=ImVec4(0.075f,0.078f,0.092f,0.96f);s.Colors[ImGuiCol_Border]=ImVec4(0.28f,0.29f,0.34f,0.65f);s.Colors[ImGuiCol_Button]=ImVec4(0.62f,0.10f,0.14f,1.f);s.Colors[ImGuiCol_ButtonHovered]=ImVec4(0.78f,0.14f,0.19f,1.f);s.Colors[ImGuiCol_ButtonActive]=ImVec4(0.48f,0.08f,0.11f,1.f);s.Colors[ImGuiCol_Header]=ImVec4(0.50f,0.09f,0.12f,0.8f);s.Colors[ImGuiCol_HeaderHovered]=ImVec4(0.72f,0.13f,0.17f,0.9f);s.Colors[ImGuiCol_CheckMark]=ImVec4(0.95f,0.25f,0.30f,1.f);s.Colors[ImGuiCol_Tab]=ImVec4(0.12f,0.12f,0.15f,1.f);s.Colors[ImGuiCol_TabHovered]=ImVec4(0.65f,0.12f,0.16f,1.f);s.Colors[ImGuiCol_TabSelected]=ImVec4(0.55f,0.10f,0.14f,1.f);
}

extern "C" JNIEXPORT void JNICALL Java_fac_guard_ImGuiOverlayView_nativeInit(JNIEnv* env,jclass,jfloat density,jstring statusP,jstring settingsP){
    if(g_init){ImGui_ImplOpenGL3_Shutdown();ImGui::DestroyContext();g_init=false;}
    g_density=std::max(1.0f,(float)density);IMGUI_CHECKVERSION();ImGui::CreateContext();ImGuiIO& io=ImGui::GetIO();io.IniFilename=nullptr;io.LogFilename=nullptr;io.FontGlobalScale=std::max(1.0f,g_density*0.82f);apply_style();ImGui_ImplOpenGL3_Init("#version 300 es");parse_status(jstr(env,statusP));parse_settings(jstr(env,settingsP));g_init=true;
}
extern "C" JNIEXPORT void JNICALL Java_fac_guard_ImGuiOverlayView_nativeResize(JNIEnv*,jclass,jint w,jint h){g_w=std::max(1,(int)w);g_h=std::max(1,(int)h);if(g_init)ImGui::GetIO().DisplaySize=ImVec2((float)g_w,(float)g_h);}
extern "C" JNIEXPORT jint JNICALL Java_fac_guard_ImGuiOverlayView_nativeRender(JNIEnv*,jclass){
    if(!g_init)return 0;glViewport(0,0,g_w,g_h);glClearColor(0,0,0,0);glClear(GL_COLOR_BUFFER_BIT);ImGui_ImplOpenGL3_NewFrame();ImGuiIO& io=ImGui::GetIO();io.DisplaySize=ImVec2((float)g_w,(float)g_h);io.DeltaTime=1.0f/60.0f;ImGui::NewFrame();
    ImGui::SetNextWindowPos(ImVec2(g_w*0.045f,g_h*0.055f),ImGuiCond_Always);ImGui::SetNextWindowSize(ImVec2(g_w*0.91f,g_h*0.86f),ImGuiCond_Always);ImGui::SetNextWindowBgAlpha(0.98f);
    ImGuiWindowFlags wf=ImGuiWindowFlags_NoCollapse|ImGuiWindowFlags_NoResize|ImGuiWindowFlags_NoMove|ImGuiWindowFlags_NoSavedSettings;
    ImGui::Begin("FAC Guard V15.2",nullptr,wf);
    ImGui::TextColored(ImVec4(0.96f,0.30f,0.34f,1.f),"FAC Guard V15.2");ImGui::SameLine();ImGui::TextDisabled("Dear ImGui Control Panel");
    if(ImGui::BeginTabBar("mainTabs",ImGuiTabBarFlags_None)){
        if(ImGui::BeginTabItem("Overview")){draw_overview();ImGui::EndTabItem();}
        if(ImGui::BeginTabItem("Device")){draw_device();ImGui::EndTabItem();}
        if(ImGui::BeginTabItem("Bot Settings")){draw_settings();ImGui::EndTabItem();}
        ImGui::EndTabBar();
    }
    ImGui::End();ImGui::Render();ImGui_ImplOpenGL3_RenderDrawData(ImGui::GetDrawData());int a=g_actions;g_actions=0;return a;
}
extern "C" JNIEXPORT void JNICALL Java_fac_guard_ImGuiOverlayView_nativeTouch(JNIEnv*,jclass,jint action,jfloat x,jfloat y){if(!g_init)return;ImGuiIO& io=ImGui::GetIO();io.AddMousePosEvent(x,y);if(action==0)io.AddMouseButtonEvent(0,true);else if(action==1||action==3)io.AddMouseButtonEvent(0,false);}
static ImGuiKey keymap(int k){switch(k){case 67:return ImGuiKey_Backspace;case 66:return ImGuiKey_Enter;case 61:return ImGuiKey_Tab;case 19:return ImGuiKey_UpArrow;case 20:return ImGuiKey_DownArrow;case 21:return ImGuiKey_LeftArrow;case 22:return ImGuiKey_RightArrow;case 111:case 4:return ImGuiKey_Escape;default:return ImGuiKey_None;}}
extern "C" JNIEXPORT void JNICALL Java_fac_guard_ImGuiOverlayView_nativeKey(JNIEnv*,jclass,jint key,jint action,jint unicodeChar){if(!g_init)return;ImGuiIO& io=ImGui::GetIO();ImGuiKey ik=keymap(key);if(ik!=ImGuiKey_None)io.AddKeyEvent(ik,action==0);if(action==0&&unicodeChar>31)io.AddInputCharacter((unsigned int)unicodeChar);}
extern "C" JNIEXPORT void JNICALL Java_fac_guard_ImGuiOverlayView_nativeAddText(JNIEnv* env,jclass,jstring text){if(!g_init)return;std::string s=jstr(env,text);ImGui::GetIO().AddInputCharactersUTF8(s.c_str());}
extern "C" JNIEXPORT jboolean JNICALL Java_fac_guard_ImGuiOverlayView_nativeWantsTextInput(JNIEnv*,jclass){return g_init&&ImGui::GetIO().WantTextInput?JNI_TRUE:JNI_FALSE;}
extern "C" JNIEXPORT jstring JNICALL Java_fac_guard_ImGuiOverlayView_nativeDumpSettings(JNIEnv* env,jclass){std::string s=dump_settings();return env->NewStringUTF(s.c_str());}
extern "C" JNIEXPORT void JNICALL Java_fac_guard_ImGuiOverlayView_nativeSetStatus(JNIEnv* env,jclass,jstring p){if(g_init)parse_status(jstr(env,p));}
extern "C" JNIEXPORT void JNICALL Java_fac_guard_ImGuiOverlayView_nativeShutdown(JNIEnv*,jclass){if(g_init){ImGui_ImplOpenGL3_Shutdown();ImGui::DestroyContext();g_init=false;}g_fields.clear();g_categories.clear();g_status.clear();}
