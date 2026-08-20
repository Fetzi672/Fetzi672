cmake_minimum_required(VERSION 3.22)
project(facui LANGUAGES CXX)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
if(NOT DEFINED IMGUI_DIR)
  message(FATAL_ERROR "IMGUI_DIR is required")
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
)
target_compile_definitions(facui PRIVATE IMGUI_IMPL_OPENGL_ES3)
target_link_libraries(facui GLESv3 log android)
