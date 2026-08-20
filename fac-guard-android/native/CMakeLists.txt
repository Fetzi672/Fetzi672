cmake_minimum_required(VERSION 3.22)
project(facui LANGUAGES CXX)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
if(NOT DEFINED IMGUI_DIR)
  message(FATAL_ERROR "IMGUI_DIR is required")
endif()
if(NOT DEFINED IMGUI_NOTIFY_DIR)
  message(FATAL_ERROR "IMGUI_NOTIFY_DIR is required")
endif()
add_library(facui SHARED
  facui.cpp
  ${IMGUI_DIR}/imgui.cpp
  ${IMGUI_DIR}/imgui_draw.cpp
  ${IMGUI_DIR}/imgui_tables.cpp
  ${IMGUI_DIR}/imgui_widgets.cpp
  ${IMGUI_DIR}/backends/imgui_impl_opengl3.cpp
  ${IMGUI_DIR}/misc/cpp/imgui_stdlib.cpp
)
target_include_directories(facui PRIVATE
  ${IMGUI_DIR}
  ${IMGUI_DIR}/backends
  ${IMGUI_DIR}/misc/cpp
  ${IMGUI_NOTIFY_DIR}/example/src
)
target_compile_definitions(facui PRIVATE IMGUI_IMPL_OPENGL_ES3)
# imgui-notify upstream uses runtime strings with ImGui::Text/TextColored.
# Android NDK enables -Werror=format-security globally; keep the upstream
# header unchanged and relax only this warning for the FAC native UI target.
target_compile_options(facui PRIVATE -Wno-format-security)
target_link_libraries(facui GLESv3 log android)
